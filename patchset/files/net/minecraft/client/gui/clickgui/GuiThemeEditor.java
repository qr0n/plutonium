package net.minecraft.client.gui.clickgui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import plutonium.Theme;
import plutonium.gpu.BlurFx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Live theme editor: pick a built-in preset, fine-tune every colour via R/G/B sliders, adjust
 * corner radius / surface opacity / shadow, and save named profiles. All edits apply instantly
 * through {@link Theme} (which re-derives the DrawUtils palette and persists), so the editor —
 * and the ClickGUI behind it — recolour live. Sliders are click-to-set (no drag required).
 */
public class GuiThemeEditor extends GuiScreen {

    private final GuiScreen parent;
    private static final int PW = 308, PH = 286;
    private int px, py;

    // Editable colour knobs, in row order.
    private static final String[] COLOR_LABELS = {"Accent", "Surface", "Base", "Text", "Border"};
    private static final int[] CH_COL = {0xFFE05050, 0xFF50E050, 0xFF5080E0}; // R/G/B track tints

    // Slider kinds
    private static final int K_COLOR = 0, K_RADIUS = 1, K_OPACITY = 2, K_STRENGTH = 3;

    private static final class Slider { int x, y, w, kind, ci, ch; }
    private final List<Slider> sliders = new ArrayList<>();
    private final List<int[]> swatches = new ArrayList<>();   // {x,y,w,h} per colour row
    private final List<int[]> presetHits = new ArrayList<>(); // {x,y,w,h}
    private List<String> presetNames = new ArrayList<>();
    private int shadowTogX, shadowTogY;
    private int nameFx, nameFy, nameFw, saveBx, saveBy, saveBw;

    private String newName = "";
    private boolean nameFocused = false;

    public GuiThemeEditor(GuiScreen parent) { this.parent = parent; }

    @Override public void initGui() { px = (width - PW) / 2; py = (height - PH) / 2; }
    @Override public boolean doesGuiPauseGame() { return false; }

    // ── layout (shared by draw + click) ──────────────────────────────────
    private void layout() {
        sliders.clear(); swatches.clear(); presetHits.clear();
        presetNames = Theme.profileNames();

        // Presets row(s) — wrap chips within the panel width.
        int cx = px + 12, cy = py + 26;
        for (String n : presetNames) {
            int w = fontRendererObj.getStringWidth(n) + 12 + (Theme.isBuiltIn(n) ? 0 : 10);
            if (cx + w > px + PW - 12) { cx = px + 12; cy += 18; }
            presetHits.add(new int[]{cx, cy, w, 14});
            cx += w + 4;
        }
        int colorY = cy + 24;

        // Five colour rows: label + swatch + R/G/B sliders.
        int sx = px + 78, sw = 62, gap = 4;
        for (int ci = 0; ci < 5; ci++) {
            int rowY = colorY + ci * 20;
            swatches.add(new int[]{px + 54, rowY + 1, 16, 13});
            for (int ch = 0; ch < 3; ch++) {
                Slider s = new Slider();
                s.x = sx + ch * (sw + gap); s.y = rowY + 6; s.w = sw;
                s.kind = K_COLOR; s.ci = ci; s.ch = ch;
                sliders.add(s);
            }
        }

        // Radius / opacity / shadow-strength full-width sliders.
        int fullX = px + 78, fullW = PW - 78 - 12;
        int y = colorY + 5 * 20 + 6;
        addFull(fullX, y, fullW, K_RADIUS);    y += 16;
        addFull(fullX, y, fullW, K_OPACITY);   y += 16;
        addFull(fullX, y, fullW, K_STRENGTH);  y += 18;

        // Shadow toggle.
        shadowTogX = px + 78; shadowTogY = y;  y += 22;

        // Save-as name field + button (button right-aligned to the 12px panel margin).
        saveBw = 60;
        nameFx = px + 12; nameFy = y; saveBy = y;
        saveBx = px + PW - 12 - saveBw;
        nameFw = saveBx - 8 - nameFx;
    }

    private void addFull(int x, int y, int w, int kind) {
        Slider s = new Slider(); s.x = x; s.y = y + 3; s.w = w; s.kind = kind; sliders.add(s);
    }

    // ── colour knob accessors ────────────────────────────────────────────
    private int colorOf(int ci) {
        switch (ci) { case 0: return Theme.accent(); case 1: return Theme.surface();
            case 2: return Theme.base(); case 3: return Theme.textPrimary(); default: return Theme.border(); }
    }
    private void setColor(int ci, int v) {
        switch (ci) { case 0: Theme.setAccent(v); break; case 1: Theme.setSurface(v); break;
            case 2: Theme.setBase(v); break; case 3: Theme.setTextPrimary(v); break; default: Theme.setBorder(v); }
    }
    private static int chan(int argb, int ch) { return (argb >> (16 - 8 * ch)) & 0xFF; }
    private static int setChan(int argb, int ch, int v) {
        int sh = 16 - 8 * ch; return (argb & ~(0xFF << sh)) | ((v & 0xFF) << sh);
    }

    private float sliderFrac(Slider s) {
        switch (s.kind) {
            case K_RADIUS:   return Theme.radius() / 12f;
            case K_OPACITY:  return Theme.surfaceAlpha() / 255f;
            case K_STRENGTH: return Theme.shadowStrength() / 255f;
            default:         return chan(colorOf(s.ci), s.ch) / 255f;
        }
    }
    private int sliderFill(Slider s) {
        switch (s.kind) {
            case K_RADIUS: case K_OPACITY: case K_STRENGTH: return DrawUtils.T_ACCENT;
            default: return CH_COL[s.ch];
        }
    }

    // ── draw ──────────────────────────────────────────────────────────────
    @Override
    public void drawScreen(int mx, int my, float pt) {
        layout();
        if (BlurFx.drawBackground(width, height)) drawRect(0, 0, width, height, 0x66000000);
        else drawRect(0, 0, width, height, 0xAA000000);

        DrawUtils.drawShadow(px, py, PW, PH, DrawUtils.CORNER_RADIUS);
        DrawUtils.drawRoundedRect(px, py, PW, PH, DrawUtils.T_WIN_BG);
        DrawUtils.drawGradientRect(px + 4, py + 2, PW - 8, 14, DrawUtils.T_GLOSS_TOP, DrawUtils.T_GLOSS_BOT);
        DrawUtils.drawRoundedRectBorder(px, py, PW, PH, DrawUtils.T_BORDER);

        fontRendererObj.drawString("Theme", px + 12, py + 9, DrawUtils.T_TEXT_PRIMARY);
        boolean overX = DrawUtils.inBox(mx, my, px + PW - 16, py + 8, 10, 10);
        fontRendererObj.drawString("x", px + PW - 14, py + 9, overX ? DrawUtils.T_TEXT_PRIMARY : DrawUtils.T_TEXT_SECONDARY);

        // Presets
        for (int i = 0; i < presetHits.size(); i++) {
            int[] r = presetHits.get(i);
            String n = presetNames.get(i);
            boolean active = n.equals(Theme.activeProfile());
            boolean hover = DrawUtils.inBox(mx, my, r[0], r[1], r[2], r[3]);
            DrawUtils.drawRoundedRect(r[0], r[1], r[2], r[3], active ? DrawUtils.T_ACCENT
                    : (hover ? 0x33FFFFFF : 0x18FFFFFF));
            fontRendererObj.drawString(n, r[0] + 6, r[1] + 3,
                    active ? 0xFFFFFFFF : DrawUtils.T_TEXT_PRIMARY);
            if (!Theme.isBuiltIn(n))
                fontRendererObj.drawString("x", r[0] + r[2] - 8, r[1] + 3, DrawUtils.T_TEXT_SECONDARY);
        }

        // Colour rows
        for (int ci = 0; ci < 5; ci++) {
            int[] sw = swatches.get(ci);
            fontRendererObj.drawString(COLOR_LABELS[ci], px + 12, sw[1] + 2, DrawUtils.T_TEXT_SECONDARY);
            DrawUtils.drawRoundedRect(sw[0], sw[1], sw[2], sw[3], colorOf(ci) | 0xFF000000);
            DrawUtils.drawRoundedRectBorder(sw[0], sw[1], sw[2], sw[3], 0x66FFFFFF);
        }

        // Sliders
        for (Slider s : sliders) drawSlider(s);

        // Labels + value text for the full-width sliders
        labelFull(K_RADIUS,   "Radius",  Theme.radius() + "px");
        labelFull(K_OPACITY,  "Opacity", (int) (Theme.surfaceAlpha() / 255f * 100) + "%");
        labelFull(K_STRENGTH, "Shadow",  (int) (Theme.shadowStrength() / 255f * 100) + "%");

        // Shadow on/off toggle
        fontRendererObj.drawString("Shadow on", px + 12, shadowTogY + 1, DrawUtils.T_TEXT_SECONDARY);
        drawToggle(shadowTogX, shadowTogY, Theme.shadow());

        // Save-as field + button
        DrawUtils.drawRoundedRect(nameFx, nameFy, nameFw, 16, DrawUtils.T_SLIDER_BG);
        DrawUtils.drawRoundedRectBorder(nameFx, nameFy, nameFw, 16, nameFocused ? DrawUtils.T_ACCENT : 0x44FFFFFF);
        boolean caret = nameFocused && (System.currentTimeMillis() / 500) % 2 == 0;
        String shown = newName.isEmpty() && !nameFocused ? "Save as…" : newName + (caret ? "_" : "");
        int col = newName.isEmpty() && !nameFocused ? DrawUtils.T_TEXT_DISABLED : DrawUtils.T_TEXT_PRIMARY;
        fontRendererObj.drawString(shown, nameFx + 5, nameFy + 4, col);
        boolean overSave = DrawUtils.inBox(mx, my, saveBx, saveBy, saveBw, 16);
        DrawUtils.drawButton(fontRendererObj, "Save", saveBx, saveBy, saveBw, 16, overSave);
    }

    private void drawSlider(Slider s) {
        drawRect(s.x, s.y, s.x + s.w, s.y + 6, 0x55000000);
        int fillW = (int) (sliderFrac(s) * s.w);
        DrawUtils.drawRoundedRect(s.x, s.y, Math.max(2, fillW), 6, sliderFill(s));
        int knobX = s.x + Math.min(s.w - 2, Math.max(0, fillW - 1));
        drawRect(knobX, s.y - 2, knobX + 3, s.y + 8, 0xFFFFFFFF);
    }

    private void labelFull(int kind, String label, String val) {
        for (Slider s : sliders) if (s.kind == kind) {
            fontRendererObj.drawString(label, px + 12, s.y - 2, DrawUtils.T_TEXT_SECONDARY);
            fontRendererObj.drawString(val, s.x + s.w - fontRendererObj.getStringWidth(val), s.y - 11, DrawUtils.T_TEXT_PRIMARY);
            return;
        }
    }

    private void drawToggle(int x, int y, boolean on) {
        DrawUtils.drawRoundedRect(x, y - 1, 20, 10, on ? DrawUtils.T_ACCENT : 0x44FFFFFF);
        DrawUtils.drawRoundedRect(on ? x + 11 : x + 1, y, 8, 8, 0xFFFFFFFF);
    }

    // ── input ───────────────────────────────────────────────────────────
    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        layout();
        nameFocused = false;

        if (DrawUtils.inBox(mx, my, px + PW - 16, py + 8, 12, 12)) { close(); return; }

        // Presets: click name to apply; click trailing x to delete a custom profile.
        for (int i = 0; i < presetHits.size(); i++) {
            int[] r = presetHits.get(i);
            if (!DrawUtils.inBox(mx, my, r[0], r[1], r[2], r[3])) continue;
            String n = presetNames.get(i);
            if (!Theme.isBuiltIn(n) && mx >= r[0] + r[2] - 10) Theme.deleteProfile(n);
            else Theme.applyProfile(n);
            return;
        }

        // Sliders (click-to-set)
        for (Slider s : sliders) {
            if (mx < s.x || mx >= s.x + s.w || my < s.y - 3 || my >= s.y + 9) continue;
            float frac = (mx - s.x) / (float) s.w;
            frac = Math.max(0f, Math.min(1f, frac));
            switch (s.kind) {
                case K_RADIUS:   Theme.setRadius(Math.round(frac * 12)); break;
                case K_OPACITY:  Theme.setSurfaceAlpha(Math.round(frac * 255)); break;
                case K_STRENGTH: Theme.setShadowStrength(Math.round(frac * 255)); break;
                default:         setColor(s.ci, setChan(colorOf(s.ci), s.ch, Math.round(frac * 255))); break;
            }
            return;
        }

        if (DrawUtils.inBox(mx, my, shadowTogX, shadowTogY - 1, 20, 10)) { Theme.setShadow(!Theme.shadow()); return; }
        if (DrawUtils.inBox(mx, my, nameFx, nameFy, nameFw, 16)) { nameFocused = true; return; }
        if (DrawUtils.inBox(mx, my, saveBx, saveBy, saveBw, 16)) {
            if (!newName.trim().isEmpty()) { Theme.saveProfile(newName); newName = ""; }
            return;
        }
    }

    @Override
    protected void keyTyped(char ch, int keyCode) throws IOException {
        if (keyCode == 1) { close(); return; } // ESC
        if (nameFocused) {
            if (keyCode == 14) { // backspace
                if (!newName.isEmpty()) newName = newName.substring(0, newName.length() - 1);
            } else if (keyCode == 28) { // enter
                if (!newName.trim().isEmpty()) { Theme.saveProfile(newName); newName = ""; }
                nameFocused = false;
            } else if (ChatAllowedCharacters.isAllowedCharacter(ch) && newName.length() < 16) {
                newName += ch;
            }
            return;
        }
        super.keyTyped(ch, keyCode);
    }

    private void close() { mc.displayGuiScreen(parent); }
}
