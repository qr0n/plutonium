package net.minecraft.client.gui.clickgui;

import net.minecraft.client.gui.GuiScreen;
import plutonium.Config;
import plutonium.Plutonium;
import plutonium.module.Module;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * HUD layout editor: drag elements (with edge/center snapping), scale the selected
 * element with a slider, right-click an element to jump to its settings. Positions
 * are stored anchor-relative so they survive resolution changes.
 */
public class GuiHUDEditor extends GuiScreen {

    private static final int C_BG       = 0x66000000;
    private static final int C_BOX      = 0x22FFFFFF;
    private static final int C_DRAGGED  = 0xCCFFAA00;
    private static final int C_SNAP     = 0xCC55FFAA;
    private static final int C_GUIDE    = 0x14FFFFFF;
    private static final int SNAP_DIST  = 8;

    // Theme-derived colours: read live from DrawUtils each draw so live theme-editor changes
    // apply immediately. (Capturing these in static finals froze them at class-load — the
    // selected-element highlight kept showing a stale accent after the theme changed.)
    private static int cBorder()   { return (DrawUtils.T_ACCENT & 0xFFFFFF) | 0x88000000; }
    private static int cSelected() { return DrawUtils.T_ACCENT; }
    private static int cText()     { return DrawUtils.T_TEXT_PRIMARY; }
    private static int cDim()      { return (DrawUtils.T_TEXT_SECONDARY & 0xFFFFFF) | 0xAA000000; }

    private static final float SCALE_MIN = 0.5f;
    private static final float SCALE_MAX = 2.0f;

    // Selected-element panel geometry
    private static final int PANEL_W = 170;
    private static final int PANEL_H = 42;

    private final List<Module> hudModules = new ArrayList<Module>();

    private Module selected = null;
    private Module dragging = null;
    private int dragOffX, dragOffY;
    private boolean snappedX, snappedY;
    private boolean draggingScale = false;

    @Override
    public void initGui() {
        hudModules.clear();
        for (Module m : Plutonium.getInstance().getModuleManager().getModules()) {
            if (m.getHudWidth() > 0 && m.isEnabled()) {
                hudModules.add(m);
            }
        }
        if (selected != null && !hudModules.contains(selected)) selected = null;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, C_BG);

        // Rule-of-thirds anchor guides
        drawVerticalLine(width / 3, 0, height, C_GUIDE);
        drawVerticalLine(width * 2 / 3, 0, height, C_GUIDE);
        drawHorizontalLine(0, width, height / 3, C_GUIDE);
        drawHorizontalLine(0, width, height * 2 / 3, C_GUIDE);

        Plutonium.getInstance().onRenderHUD(width, height);

        // Snap guide lines while dragging
        if (dragging != null) {
            int rx = dragging.resolveHudX(width, height);
            int ry = dragging.resolveHudY(width, height);
            if (snappedX) drawVerticalLine(rx + dragging.getHudWidthScaled() / 2, 0, height, C_SNAP);
            if (snappedY) drawHorizontalLine(0, width, ry + dragging.getHudHeightScaled() / 2, C_SNAP);
        }

        for (Module m : hudModules) {
            int rx = m.resolveHudX(width, height);
            int ry = m.resolveHudY(width, height);
            int rw = m.getHudWidthScaled();
            int rh = m.getHudHeightScaled();

            int col = dragging == m ? C_DRAGGED : (selected == m ? cSelected() : cBorder());
            drawOutlinedRect(rx - 1, ry - 1, rx + rw + 1, ry + rh + 1, dragging == m ? C_BOX : 0, col);

            String label = m.getName();
            mc.fontRendererObj.drawStringWithShadow(label,
                    rx + (rw - mc.fontRendererObj.getStringWidth(label)) / 2, ry + rh + 3, cDim());
        }

        if (selected != null) drawSelectedPanel(mouseX, mouseY);

        // Reset-all button
        drawButton(resetLabel(), resetX(), resetY(), resetW(), 14, mouseX, mouseY);

        String hint = "Drag: move  ·  Right-click: settings  ·  ESC: save & exit";
        mc.fontRendererObj.drawStringWithShadow(hint,
                (width - mc.fontRendererObj.getStringWidth(hint)) / 2, height - 14, cDim());

        if (hudModules.isEmpty()) {
            String empty = "No draggable HUD modules are enabled";
            mc.fontRendererObj.drawStringWithShadow(empty,
                    (width - mc.fontRendererObj.getStringWidth(empty)) / 2, height / 2, 0xFF888888);
        }
    }

    private String resetLabel() { return "Reset All"; }
    private int resetW() { return mc.fontRendererObj.getStringWidth(resetLabel()) + 12; }
    private int resetX() { return width - resetW() - 8; }
    private int resetY() { return height - 22; }

    private void drawButton(String label, int x, int y, int w, int h, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        DrawUtils.drawRoundedRect(x, y, w, h, hover ? DrawUtils.T_BTN_HOVER : DrawUtils.T_BTN_NORMAL);
        mc.fontRendererObj.drawStringWithShadow(label, x + 6, y + 3, cText());
    }

    // ── selected-element panel (bottom-left): name + scale slider ────

    private int panelX() { return 8; }
    private int panelY() { return height - PANEL_H - 8; }

    private void drawSelectedPanel(int mx, int my) {
        int px = panelX(), py = panelY();
        DrawUtils.drawChip(px, py, PANEL_W, PANEL_H);

        mc.fontRendererObj.drawStringWithShadow(selected.getName(), px + 7, py + 5, cText());
        String pct = (int) (selected.hudScale * 100) + "%";
        mc.fontRendererObj.drawStringWithShadow(pct,
                px + PANEL_W - 7 - mc.fontRendererObj.getStringWidth(pct), py + 5, cDim());

        // Scale slider
        int tx = sliderX(), tw = sliderW(), ty = sliderY();
        float frac = (selected.hudScale - SCALE_MIN) / (SCALE_MAX - SCALE_MIN);
        drawRect(tx, ty, tx + tw, ty + 3, 0xFF2A3140);
        drawRect(tx, ty, tx + (int) (frac * tw), ty + 3, DrawUtils.T_ACCENT);
        int knobX = tx + (int) (frac * tw) - 2;
        drawRect(knobX, ty - 2, knobX + 4, ty + 5, 0xFFFFFFFF);
    }

    private int sliderX() { return panelX() + 7; }
    private int sliderW() { return PANEL_W - 14; }
    private int sliderY() { return panelY() + 26; }

    private void updateScale(int mouseX) {
        float frac = (float) (mouseX - sliderX()) / sliderW();
        frac = Math.max(0, Math.min(1, frac));
        float raw = SCALE_MIN + frac * (SCALE_MAX - SCALE_MIN);
        selected.hudScale = Math.round(raw * 20f) / 20f; // 5% steps
    }

    // ── input ────────────────────────────────────────────────────────

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        // Scale slider (when panel is showing)
        if (selected != null && mouseButton == 0
                && mouseX >= panelX() && mouseX < panelX() + PANEL_W
                && mouseY >= panelY() && mouseY < panelY() + PANEL_H) {
            if (mouseY >= sliderY() - 5 && mouseY <= sliderY() + 8) {
                draggingScale = true;
                updateScale(mouseX);
            }
            return;
        }

        // Reset all
        if (mouseButton == 0 && mouseX >= resetX() && mouseX < resetX() + resetW()
                && mouseY >= resetY() && mouseY < resetY() + 14) {
            for (Module m : hudModules) m.resetHudPosition();
            Config.save();
            return;
        }

        // Elements (topmost = last drawn wins)
        for (int i = hudModules.size() - 1; i >= 0; i--) {
            Module m = hudModules.get(i);
            int rx = m.resolveHudX(width, height);
            int ry = m.resolveHudY(width, height);
            if (mouseX >= rx - 1 && mouseX <= rx + m.getHudWidthScaled() + 1
                    && mouseY >= ry - 1 && mouseY <= ry + m.getHudHeightScaled() + 1) {
                if (mouseButton == 1) {
                    mc.displayGuiScreen(new GuiClickGUI(m));
                    return;
                }
                selected = m;
                dragging = m;
                dragOffX = mouseX - rx;
                dragOffY = mouseY - ry;
                snappedX = false;
                snappedY = false;
                // Drag in absolute coords; converted back to an anchor on release
                m.hudX = rx;
                m.hudY = ry;
                m.hudAnchor = -1;
                return;
            }
        }

        if (mouseButton == 0) selected = null;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int clickedMouseButton, long timeSinceLastClick) {
        if (clickedMouseButton != 0) return;

        if (draggingScale && selected != null) {
            updateScale(mouseX);
            return;
        }
        if (dragging == null) return;

        int w = dragging.getHudWidthScaled();
        int h = dragging.getHudHeightScaled();
        int rawX = Math.max(0, Math.min(width - w, mouseX - dragOffX));
        int rawY = Math.max(0, Math.min(height - h, mouseY - dragOffY));

        int[] snapTargetsX = {0, (width - w) / 2, width - w};
        int[] snapTargetsY = {0, (height - h) / 2, height - h};

        int nx = rawX;
        snappedX = false;
        for (int sx : snapTargetsX) {
            if (Math.abs(rawX - sx) <= SNAP_DIST) {
                nx = sx;
                snappedX = true;
                break;
            }
        }

        int ny = rawY;
        snappedY = false;
        for (int sy : snapTargetsY) {
            if (Math.abs(rawY - sy) <= SNAP_DIST) {
                ny = sy;
                snappedY = true;
                break;
            }
        }

        dragging.hudX = nx;
        dragging.hudY = ny;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        if (state != 0) return;
        if (dragging != null) {
            // Convert the dropped absolute position to anchor + offset
            dragging.setHudPosition(
                    dragging.resolveHudX(width, height),
                    dragging.resolveHudY(width, height),
                    width, height);
            dragging = null;
            snappedX = false;
            snappedY = false;
            Config.save();
        }
        if (draggingScale) {
            draggingScale = false;
            Config.save();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == 1) {
            Config.save();
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }

    private void drawOutlinedRect(int x1, int y1, int x2, int y2, int fill, int border) {
        if (fill != 0) drawRect(x1, y1, x2, y2, fill);
        drawHorizontalLine(x1, x2 - 1, y1, border);
        drawHorizontalLine(x1, x2 - 1, y2 - 1, border);
        drawVerticalLine(x1, y1, y2 - 1, border);
        drawVerticalLine(x2 - 1, y1, y2 - 1, border);
    }
}
