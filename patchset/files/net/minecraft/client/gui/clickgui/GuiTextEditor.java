package net.minecraft.client.gui.clickgui;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.opengl.GL11;
import plutonium.gpu.Batches;
import plutonium.gpu.KeyboardShim;
import plutonium.gpu.MouseShim;
import plutonium.setting.StringSetting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Pop-out multi-line text editor for a {@link StringSetting} (used by the persona
 * prompt). Full editing model: word-wrapped soft lines + hard newlines, a caret,
 * arrow / Home / End navigation, click-to-place and click-drag / shift selection,
 * &#8984;A/C/X/V on the selection, mouse-wheel scroll, and Save / Cancel.
 *
 * The setting is only written back on Save (or Esc, which saves) — Cancel discards.
 * Reuses the ClickGUI's scissor + {@link Batches#flushAll()} recipe so the scrolled
 * text stays clipped without eating the surrounding chrome's glyphs.
 */
public class GuiTextEditor extends GuiScreen {

    private final GuiScreen parent;
    private final StringSetting target;
    private final int maxLength;

    private final StringBuilder text = new StringBuilder();
    private int caret;             // 0..text.length()
    private int selAnchor = -1;    // other end of selection, or -1 / == caret when none
    private int scroll;            // vertical pixel offset
    private int goalX = -1;        // preferred caret x (px from text left) for vertical moves
    private boolean dragging;
    private boolean hoverSave, hoverCancel;

    // Line = {startIndex, endIndex} into text (end exclusive, excludes the '\n').
    private final List<int[]> lines = new ArrayList<int[]>();

    // Geometry, computed in initGui().
    private int panelX, panelY, panelW, panelH;
    private int textX, textY, textW, textH, wrapW, lineH;
    private int btnY, saveX, cancelX;
    private static final int PAD = 10, TITLE_H = 18, BTN_W = 62, BTN_H = 18;

    public GuiTextEditor(GuiScreen parent, StringSetting target) {
        this.parent = parent;
        this.target = target;
        this.maxLength = target.getMaxLength();
        String v = target.getValue();
        if (v != null) text.append(v);
        this.caret = text.length();
    }

    @Override
    public void initGui() {
        KeyboardShim.enableRepeatEvents(true);
        panelW = Math.min(480, width - 40);
        panelH = Math.min(300, height - 60);
        panelX = (width - panelW) / 2;
        panelY = (height - panelH) / 2;
        textX = panelX + PAD;
        textY = panelY + TITLE_H + 6;
        textW = panelW - 2 * PAD;
        int footerH = PAD + BTN_H + 6;
        textH = panelH - (TITLE_H + 6) - footerH;
        wrapW = textW - 6;
        lineH = fontRendererObj.FONT_HEIGHT + 3;
        btnY = panelY + panelH - PAD - BTN_H;
        saveX = panelX + panelW - PAD - BTN_W;
        cancelX = saveX - 8 - BTN_W;
        layout();
        ensureCaretVisible();
    }

    @Override
    public void onGuiClosed() {
        KeyboardShim.enableRepeatEvents(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    // ---------- line layout (word wrap) ----------

    private int charW(int i) {
        return fontRendererObj.getCharWidth(text.charAt(i));
    }

    private int widthOf(int from, int to) {
        int w = 0;
        for (int i = from; i < to; i++) w += fontRendererObj.getCharWidth(text.charAt(i));
        return w;
    }

    private void layout() {
        lines.clear();
        int n = text.length();
        int start = 0;
        while (true) {
            int end = start;
            int lastSpace = -1;
            int wpx = 0;
            boolean hardBreak = false;
            while (end < n) {
                char ch = text.charAt(end);
                if (ch == '\n') { hardBreak = true; break; }
                int cw = fontRendererObj.getCharWidth(ch);
                if (wpx + cw > wrapW && end > start) break; // soft wrap before this char
                wpx += cw;
                if (ch == ' ') lastSpace = end;
                end++;
            }
            int lineEnd, nextStart;
            if (hardBreak) {
                lineEnd = end;
                nextStart = end + 1;         // skip the newline
            } else if (end >= n) {
                lineEnd = n;
                nextStart = n + 1;           // sentinel: stop
            } else if (lastSpace >= start) { // soft wrap, break at last space
                lineEnd = lastSpace;
                nextStart = lastSpace + 1;    // skip the space
            } else {                          // single long word, hard char break
                lineEnd = end;
                nextStart = end;
            }
            lines.add(new int[]{start, lineEnd});
            if (nextStart > n) break;
            start = nextStart;
        }
        if (lines.isEmpty()) lines.add(new int[]{0, 0});
    }

    private int rowOfIndex(int idx) {
        for (int r = 0; r < lines.size(); r++) {
            if (idx <= lines.get(r)[1]) return r;
        }
        return lines.size() - 1;
    }

    private int colXOf(int idx) {
        int[] L = lines.get(rowOfIndex(idx));
        return widthOf(L[0], idx);
    }

    /** Nearest caret index in a row for a target x (px from text left). */
    private int indexAtRowX(int row, int px) {
        int[] L = lines.get(row);
        int ls = L[0], le = L[1];
        int x = 0, best = ls, bestD = Math.abs(px - x);
        for (int i = ls; i < le; i++) {
            x += charW(i);
            int d = Math.abs(px - x);
            if (d < bestD) { bestD = d; best = i + 1; }
        }
        return best;
    }

    private int indexAtPoint(int mx, int my) {
        int row = (int) Math.floor((my - textY + scroll) / (double) lineH);
        if (row < 0) row = 0;
        if (row > lines.size() - 1) row = lines.size() - 1;
        return indexAtRowX(row, mx - textX);
    }

    // ---------- selection helpers ----------

    private boolean hasSel() { return selAnchor >= 0 && selAnchor != caret; }
    private int selMin() { return Math.min(selAnchor, caret); }
    private int selMax() { return Math.max(selAnchor, caret); }

    private void moveCaret(int idx, boolean shift) {
        if (idx < 0) idx = 0;
        if (idx > text.length()) idx = text.length();
        if (shift) { if (selAnchor < 0) selAnchor = caret; }
        else selAnchor = -1;
        caret = idx;
    }

    private void deleteSelection() {
        int a = selMin(), b = selMax();
        text.delete(a, b);
        caret = a;
        selAnchor = -1;
    }

    // ---------- edits ----------

    private void insert(String ins) {
        if (ins == null || ins.isEmpty()) return;
        if (hasSel()) deleteSelection();
        else selAnchor = -1;
        int room = maxLength - text.length();
        if (room <= 0) { layout(); return; }
        if (ins.length() > room) ins = ins.substring(0, room);
        text.insert(caret, ins);
        caret += ins.length();
        layout();
        goalX = -1;
        ensureCaretVisible();
    }

    private void backspace() {
        if (hasSel()) deleteSelection();
        else if (caret > 0) { text.delete(caret - 1, caret); caret--; }
        selAnchor = -1;
        layout();
        goalX = -1;
        ensureCaretVisible();
    }

    private void deleteForward() {
        if (hasSel()) deleteSelection();
        else if (caret < text.length()) text.delete(caret, caret + 1);
        selAnchor = -1;
        layout();
        goalX = -1;
        ensureCaretVisible();
    }

    private String filterClip(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n' || ChatAllowedCharacters.isAllowedCharacter(c)) sb.append(c);
            else if (c == '\r') { /* drop; \n handles the break */ }
        }
        return sb.toString();
    }

    private void ensureCaretVisible() {
        int row = rowOfIndex(caret);
        int top = row * lineH;
        int bottom = top + lineH;
        if (top < scroll) scroll = top;
        else if (bottom > scroll + textH) scroll = bottom - textH;
        int max = maxScroll();
        if (scroll > max) scroll = max;
        if (scroll < 0) scroll = 0;
    }

    private int maxScroll() {
        return Math.max(0, lines.size() * lineH - textH);
    }

    // ---------- input ----------

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        boolean shift = isShiftKeyDown();
        switch (keyCode) {
            case 1:   save();  return;          // ESC → save & close
            case 28:                             // RETURN
            case 156: insert("\n"); return;     // numpad enter
            case 14:  backspace(); return;       // backspace
            case 211: deleteForward(); return;   // delete
            case 199: moveCaret(lines.get(rowOfIndex(caret))[0], shift); goalX = -1; ensureCaretVisible(); return; // HOME
            case 207: moveCaret(lines.get(rowOfIndex(caret))[1], shift); goalX = -1; ensureCaretVisible(); return; // END
            case 203: moveCaret(caret - 1, shift); goalX = -1; ensureCaretVisible(); return; // LEFT
            case 205: moveCaret(caret + 1, shift); goalX = -1; ensureCaretVisible(); return; // RIGHT
            case 200: moveVertical(-1, shift); return; // UP
            case 208: moveVertical(1, shift);  return; // DOWN
            default: break;
        }
        if (isKeyComboCtrlA(keyCode)) { selAnchor = 0; caret = text.length(); return; }
        if (isKeyComboCtrlC(keyCode)) { if (hasSel()) setClipboardString(text.substring(selMin(), selMax())); return; }
        if (isKeyComboCtrlX(keyCode)) {
            if (hasSel()) { setClipboardString(text.substring(selMin(), selMax())); deleteSelection(); layout(); goalX = -1; ensureCaretVisible(); }
            return;
        }
        if (isKeyComboCtrlV(keyCode)) { insert(filterClip(getClipboardString())); return; }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) insert(String.valueOf(typedChar));
    }

    private void moveVertical(int dir, boolean shift) {
        int row = rowOfIndex(caret);
        if (goalX < 0) goalX = colXOf(caret);
        int newRow = row + dir;
        if (newRow < 0) { moveCaret(0, shift); ensureCaretVisible(); return; }
        if (newRow > lines.size() - 1) { moveCaret(text.length(), shift); ensureCaretVisible(); return; }
        moveCaret(indexAtRowX(newRow, goalX), shift);
        ensureCaretVisible();
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (inBox(mouseX, mouseY, saveX, btnY, BTN_W, BTN_H)) { save(); return; }
        if (inBox(mouseX, mouseY, cancelX, btnY, BTN_W, BTN_H)) { cancel(); return; }
        if (inBox(mouseX, mouseY, textX, textY, textW, textH)) {
            int idx = indexAtPoint(mouseX, mouseY);
            if (isShiftKeyDown()) { if (selAnchor < 0) selAnchor = caret; caret = idx; }
            else { caret = idx; selAnchor = idx; }
            dragging = true;
            goalX = -1;
            return;
        }
        // click outside the panel closes (saving), like dismissing a dialog
        if (!inBox(mouseX, mouseY, panelX, panelY, panelW, panelH)) save();
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (dragging) {
            caret = indexAtPoint(mouseX, mouseY);
            goalX = -1;
            ensureCaretVisible();
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = false;
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int dw = MouseShim.getEventDWheel();
        if (dw != 0) {
            scroll -= dw / 10;
            int max = maxScroll();
            if (scroll > max) scroll = max;
            if (scroll < 0) scroll = 0;
        }
    }

    private static boolean inBox(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void save() {
        target.setValue(text.toString());
        mc.displayGuiScreen(parent);
    }

    private void cancel() {
        mc.displayGuiScreen(parent);
    }

    // ---------- render ----------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xC0000000);

        DrawUtils.drawShadow(panelX, panelY, panelW, panelH, DrawUtils.CORNER_RADIUS);
        DrawUtils.drawRoundedRect(panelX, panelY, panelW, panelH, DrawUtils.T_WIN_BG);
        DrawUtils.drawGradientRect(panelX + 4, panelY + 2, panelW - 8, 14, DrawUtils.T_GLOSS_TOP, DrawUtils.T_GLOSS_BOT);
        DrawUtils.drawRoundedRectBorder(panelX, panelY, panelW, panelH, DrawUtils.T_BORDER);

        fontRendererObj.drawString("Edit " + target.getName(), panelX + PAD, panelY + 6, DrawUtils.T_TEXT_PRIMARY);
        String count = text.length() + " / " + maxLength;
        fontRendererObj.drawString(count, panelX + panelW - PAD - fontRendererObj.getStringWidth(count), panelY + 6, DrawUtils.T_TEXT_DISABLED);

        // Text field background
        DrawUtils.drawRoundedRect(textX - 4, textY - 4, textW + 8, textH + 8, DrawUtils.T_SLIDER_BG);

        // Clip the scrolled text region (flush chrome first, flush text before disabling).
        Batches.flushAll();
        int sf = new ScaledResolution(mc).getScaleFactor();
        GlStateManager.enableScissor();
        GlStateManager.scissor(textX * sf, (height - (textY + textH)) * sf, textW * sf, textH * sf);

        boolean showCaret = ((System.currentTimeMillis() / 500) & 1L) == 0L;
        int selA = hasSel() ? selMin() : -1;
        int selB = hasSel() ? selMax() : -1;
        for (int r = 0; r < lines.size(); r++) {
            int rowY = textY + r * lineH - scroll;
            if (rowY + lineH < textY || rowY > textY + textH) continue;
            int[] L = lines.get(r);
            int ls = L[0], le = L[1];
            // selection highlight
            if (selA >= 0 && selB > ls && selA <= le) {
                int a = Math.max(selA, ls), b = Math.min(selB, le);
                int xa = textX + widthOf(ls, a);
                int xb = textX + widthOf(ls, b);
                if (selB > le) xb += 4; // hint the selected line break
                if (xb > xa) DrawUtils.drawRect(xa, rowY, xb - xa, lineH - 1, DrawUtils.T_ACCENT_MUTED);
            }
            if (le > ls) fontRendererObj.drawString(text.substring(ls, le), textX, rowY + 1, DrawUtils.T_TEXT_PRIMARY);
            // caret
            if (showCaret && caret >= ls && caret <= le && rowOfIndex(caret) == r) {
                int cx = textX + widthOf(ls, caret);
                DrawUtils.drawRect(cx, rowY, 1, lineH - 1, DrawUtils.T_ACCENT_LIGHT);
            }
        }

        Batches.flushAll();
        GlStateManager.disableScissor();

        // scrollbar
        int max = maxScroll();
        if (max > 0) {
            int trackH = textH;
            int barH = Math.max(12, trackH * textH / (textH + max));
            int barY = textY + (trackH - barH) * scroll / max;
            DrawUtils.drawRoundedRect(textX + textW - 2, barY, 2, barH, (DrawUtils.T_ACCENT & 0xFFFFFF) | 0x99000000);
        }

        hoverSave = inBox(mouseX, mouseY, saveX, btnY, BTN_W, BTN_H);
        hoverCancel = inBox(mouseX, mouseY, cancelX, btnY, BTN_W, BTN_H);
        DrawUtils.drawButton(fontRendererObj, "Cancel", cancelX, btnY, BTN_W, BTN_H, hoverCancel);
        DrawUtils.drawButton(fontRendererObj, "Save", saveX, btnY, BTN_W, BTN_H, hoverSave);

        String hint = "Cmd+A all   Cmd+C/X/V   drag-select   Esc saves";
        fontRendererObj.drawString(hint, panelX + PAD, btnY + (BTN_H - 8) / 2, DrawUtils.T_TEXT_DISABLED);

        super.drawScreen(mouseX, mouseY, partialTicks);
    }
}
