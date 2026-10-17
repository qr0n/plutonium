package net.minecraft.client.gui.clickgui;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.opengl.GL11;
import plutonium.Plutonium;
import plutonium.Theme;
import plutonium.gpu.BlurFx;
import plutonium.gpu.MouseShim;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;
import plutonium.setting.ColorSetting;
import plutonium.setting.KeySetting;
import plutonium.setting.Setting;
import plutonium.setting.SliderSetting;
import plutonium.setting.StringSetting;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Two-pane ClickGUI: category sidebar on the left, searchable module list with
 * inline-expanding settings on the right.
 */
public class GuiClickGUI extends GuiScreen {

    // ── colors ──────────────────────────────────────────────────────
    // Window chrome — intentionally denser/opaquer than the shared panel style
    // Theme-derived colours — refreshed from the active Theme each frame by syncTheme(),
    // so edits in the theme editor recolour the ClickGUI live. (Row hover / settings backdrop
    // are theme-independent white/black tints.)
    private static int C_WIN_BG, C_WIN_BORDER, C_SIDEBAR_BG, C_SHADOW, C_ACCENT, C_ACCENT_DARK,
            C_TEXT_MAIN, C_TEXT_DIM, C_TEXT_FAINT, C_TOGGLE_ON, C_TOGGLE_OFF, C_FIELD_BG,
            C_BTN_BG, C_BTN_HOVER;
    private static final int C_ROW_HOVER   = 0x14FFFFFF;
    private static final int C_SETTINGS_BG = 0x40000000;

    private static void syncTheme() {
        C_ACCENT      = DrawUtils.T_ACCENT;
        C_ACCENT_DARK = DrawUtils.T_ACCENT_DARK;
        C_TEXT_MAIN   = DrawUtils.T_TEXT_PRIMARY;
        C_TEXT_DIM    = DrawUtils.T_TEXT_SECONDARY;
        C_TEXT_FAINT  = DrawUtils.T_TEXT_DISABLED;
        C_SHADOW      = DrawUtils.T_SHADOW;
        C_TOGGLE_ON   = DrawUtils.T_ACCENT;
        C_BTN_BG      = DrawUtils.T_BTN_NORMAL;
        C_BTN_HOVER   = DrawUtils.T_BTN_HOVER;
        C_WIN_BG      = Theme.withAlpha(Theme.surface(), 0xD8);          // glassy slate
        C_WIN_BORDER  = Theme.darken(Theme.border(), 0.35f) | 0xFF000000; // steel border
        C_SIDEBAR_BG  = Theme.lighten(Theme.base(), 0.04f) | 0xFF000000;
        C_FIELD_BG    = Theme.darken(Theme.surface(), 0.20f) | 0xFF000000;
        C_TOGGLE_OFF  = Theme.lighten(Theme.base(), 0.14f) | 0xFF000000;
    }

    // Theme accent / border at a given alpha (0..255) — for translucent accents and faded edges.
    private static int accentA(int a)  { return (C_ACCENT & 0xFFFFFF) | (a << 24); }
    private static int borderA(int a)  { return (C_WIN_BORDER & 0xFFFFFF) | (a << 24); }

    // Color setting click-to-cycle palette
    private static final int[] COLOR_PRESETS = {
            0xFFEEECFF, 0xFF5D50F0, 0xFF55FF55, 0xFFFFFF55,
            0xFFFF5555, 0xFFFFAA00, 0xFF55FFFF, 0xFFFF55AA
    };

    // ── geometry ─────────────────────────────────────────────────────
    private static final int WIN_W   = 480;
    private static final int WIN_H   = 300;
    private static final int SB_W    = 104;   // sidebar width
    private static final int TITLE_H = 26;    // sidebar title / drag region
    private static final int TOP_H   = 28;    // right-pane search row
    private static final int FOOT_H  = 14;
    private static final int ROW_H   = 20;
    private static final int SET_H   = 18;
    private static final int SUGGEST_H   = 12;  // one completion row
    private static final int SUGGEST_MAX = 6;   // rows shown before the list is truncated

    // Window position persists for the session
    private static int savedWinX = Integer.MIN_VALUE;
    private static int savedWinY = Integer.MIN_VALUE;

    private int winX, winY, winW, winH;

    // ── state ────────────────────────────────────────────────────────
    private Category activeTab = Category.VISUAL;
    private Module   expanded  = null;
    private Module   focusOnOpen = null;

    private String  search = "";
    private int     scrollY = 0;
    private int     maxScrollY = 0;

    private boolean dragging = false;
    private int     dragOffX, dragOffY;

    private SliderSetting draggingSlider = null;
    private KeySetting    awaitingKey = null;
    private Module        awaitingModuleKey = null;
    private StringSetting editingString = null;

    // Tooltip captured during row drawing, rendered last
    private Setting<?> tooltipSetting = null;
    private int tooltipX, tooltipY;

    // Completion popup for the focused StringSetting. Like the tooltip it is captured while
    // its row draws and rendered after the list scissor is off, so it can overhang the window.
    private List<String> suggestions = Collections.emptyList();
    private int suggestX, suggestY, suggestW;
    private int suggestSel = -1;   // -1 = nothing highlighted, so Enter just commits what was typed

    public GuiClickGUI() {}

    /** Open focused on one module (HUD editor right-click). */
    public GuiClickGUI(Module focus) {
        this.focusOnOpen = focus;
    }

    // ── layout ───────────────────────────────────────────────────────
    private static class Hit {
        Module module;          // module row, or owner of the setting
        Setting<?> setting;     // null for module rows
        int x, y, w, h;
        boolean contains(int mx, int my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    private final List<Hit> hits = new ArrayList<Hit>();

    @Override
    public void initGui() {
        // Text-input screens must enable key repeat so held keys (backspace, chars)
        // repeat instead of firing once — vanilla text GUIs do this in initGui.
        plutonium.gpu.KeyboardShim.enableRepeatEvents(true);
        winW = Math.min(WIN_W, width - 10);
        winH = Math.min(WIN_H, height - 10);
        winX = savedWinX != Integer.MIN_VALUE ? clamp(savedWinX, 0, width - winW)  : (width  - winW) / 2;
        winY = savedWinY != Integer.MIN_VALUE ? clamp(savedWinY, 0, height - winH) : (height - winH) / 2;
        if (focusOnOpen != null) {
            activeTab = focusOnOpen.getCategory();
            expanded = focusOnOpen;
            search = "";
        }
    }

    @Override
    public void onGuiClosed() {
        // Restore the global repeat state so menus don't inherit key-repeat.
        plutonium.gpu.KeyboardShim.enableRepeatEvents(false);
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    private List<Module> visibleModules() {
        List<Module> all = Plutonium.getInstance().getModuleManager().getModules();
        List<Module> out = new ArrayList<Module>();
        if (search.isEmpty()) {
            for (Module m : all) if (m.getCategory() == activeTab) out.add(m);
        } else {
            String q = search.toLowerCase(Locale.ROOT);
            for (Module m : all) {
                if (m.getName().toLowerCase(Locale.ROOT).contains(q)
                        || m.getCategory().getDisplayName().toLowerCase(Locale.ROOT).contains(q)) {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Rebuild hit rects for the current scroll/expansion state. */
    private void computeLayout() {
        hits.clear();
        int paneX = winX + SB_W;
        int paneW = winW - SB_W;
        int listTop = winY + TOP_H;
        int listH = winH - TOP_H - FOOT_H;

        int y = listTop - scrollY;
        for (Module m : visibleModules()) {
            Hit row = new Hit();
            row.module = m;
            row.x = paneX + 4; row.y = y; row.w = paneW - 8; row.h = ROW_H;
            hits.add(row);
            y += ROW_H;

            if (expanded == m) {
                for (Setting<?> s : m.getSettings()) {
                    Hit sr = new Hit();
                    sr.module = m; sr.setting = s;
                    sr.x = paneX + 14; sr.y = y; sr.w = paneW - 22; sr.h = SET_H;
                    hits.add(sr);
                    y += SET_H;
                }
            }
        }
        int totalContentH = (y + scrollY) - listTop;
        maxScrollY = Math.max(0, totalContentH - listH);
        if (scrollY > maxScrollY) scrollY = maxScrollY;
    }

    // ── drawing ──────────────────────────────────────────────────────

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        tooltipSetting = null;
        suggestions = Collections.emptyList();  // refilled by the focused field's row, if it is on screen
        syncTheme();

        // Blurred game behind the window; flat dark fallback if blur is unavailable
        if (BlurFx.drawBackground(width, height)) {
            drawRect(0, 0, width, height, 0x66000000);
        } else {
            drawRect(0, 0, width, height, 0xAA000000);
        }

        // Window chrome: soft drop shadow, glass fill, top gloss sheen, border
        DrawUtils.drawShadow(winX, winY, winW, winH, DrawUtils.CORNER_RADIUS);
        DrawUtils.drawRoundedRect(winX, winY, winW, winH, C_WIN_BG);
        DrawUtils.drawGradientRect(winX + 4, winY + 2, winW - 8, 14, DrawUtils.T_GLOSS_TOP, DrawUtils.T_GLOSS_BOT);
        DrawUtils.drawRoundedRectBorder(winX, winY, winW, winH, C_WIN_BORDER);

        drawSidebar(mouseX, mouseY);
        drawSearchRow(mouseX, mouseY);

        // Module list under scissor. drawString defers glyphs into FontBatch, so flush
        // everything drawn so far BEFORE enabling the scissor (else header/sidebar text
        // gets clipped), and again before disabling it (so list text stays clipped).
        plutonium.gpu.Batches.flushAll();

        int listTop = winY + TOP_H;
        int listBottom = winY + winH - FOOT_H;
        int sf = new ScaledResolution(mc).getScaleFactor();
        GlStateManager.enableScissor();
        GlStateManager.scissor((winX + SB_W) * sf, (height - listBottom) * sf,
                (winW - SB_W) * sf, (listBottom - listTop) * sf);

        computeLayout();
        boolean searching = !search.isEmpty();
        for (Hit h : hits) {
            if (h.y + h.h < listTop || h.y > listBottom) continue;
            if (h.setting == null) drawModuleRow(h, mouseX, mouseY, searching);
            else drawSettingRow(h, mouseX, mouseY);
        }
        if (hits.isEmpty()) {
            String none = searching ? "No modules match \"" + search + "\"" : "No modules";
            fontRendererObj.drawString(none,
                    winX + SB_W + (winW - SB_W - fontRendererObj.getStringWidth(none)) / 2,
                    listTop + 20, C_TEXT_FAINT);
        }

        plutonium.gpu.Batches.flushAll();
        GlStateManager.disableScissor();

        // Scrollbar
        if (maxScrollY > 0) {
            int listH = listBottom - listTop;
            int barH = Math.max(10, listH * listH / (listH + maxScrollY));
            int barY = listTop + (listH - barH) * scrollY / maxScrollY;
            DrawUtils.drawRoundedRect(winX + winW - 4, barY, 2, barH, accentA(0x99));
        }

        // Footer hint
        String hint = awaitingModuleKey != null ? "Press a key to bind (ESC = unbind)"
                : awaitingKey != null ? "Press a key (ESC = unbind)"
                : "Click: toggle   ·   Right-click: settings   ·   Middle-click: bind";
        fontRendererObj.drawString(hint,
                winX + SB_W + (winW - SB_W - fontRendererObj.getStringWidth(hint)) / 2,
                winY + winH - FOOT_H + 3,
                (awaitingKey != null || awaitingModuleKey != null) ? 0xFFFFAA00 : C_TEXT_FAINT);

        // Completion popup and tooltip on top of everything
        if (!suggestions.isEmpty()) drawSuggestions(mouseX, mouseY);
        if (tooltipSetting != null) drawTooltip(tooltipSetting, tooltipX, tooltipY);
    }

    /**
     * Candidates for the focused field: prefix matches first, then substring matches, dropping
     * an exact hit (nothing left to complete). Recomputed per use — the source is live.
     */
    private List<String> suggestionsFor(StringSetting ss) {
        StringSetting.SuggestionSource source = ss.getSuggestionSource();
        if (source == null) return Collections.emptyList();
        String q = ss.getValue().toLowerCase(Locale.ROOT);
        List<String> prefixed = new ArrayList<String>();
        List<String> contained = new ArrayList<String>();
        for (String candidate : source.suggest()) {
            String lc = candidate.toLowerCase(Locale.ROOT);
            if (lc.equals(q)) continue;
            if (lc.startsWith(q)) prefixed.add(candidate);
            else if (!q.isEmpty() && lc.contains(q)) contained.add(candidate);
        }
        prefixed.addAll(contained);
        return prefixed.size() > SUGGEST_MAX ? prefixed.subList(0, SUGGEST_MAX) : prefixed;
    }

    private void drawSuggestions(int mx, int my) {
        int popupH = suggestions.size() * SUGGEST_H + 2;
        DrawUtils.drawShadow(suggestX, suggestY, suggestW, popupH, DrawUtils.CORNER_RADIUS);
        DrawUtils.drawRoundedRect(suggestX, suggestY, suggestW, popupH, 0xF60E1118);
        DrawUtils.drawRoundedRectBorder(suggestX, suggestY, suggestW, popupH, C_WIN_BORDER);
        for (int i = 0; i < suggestions.size(); i++) {
            int ry = suggestY + 1 + i * SUGGEST_H;
            boolean active = i == suggestSel || (i == suggestIndexAt(mx, my));
            if (active) DrawUtils.drawRect(suggestX + 1, ry, suggestW - 2, SUGGEST_H, accentA(0x44));
            fontRendererObj.drawString(suggestions.get(i), suggestX + 5, ry + 2, active ? C_TEXT_MAIN : C_TEXT_DIM);
        }
    }

    /** Index of the completion row under the cursor, or -1. */
    private int suggestIndexAt(int mx, int my) {
        if (suggestions.isEmpty() || mx < suggestX || mx >= suggestX + suggestW) return -1;
        int i = (my - suggestY - 1) / SUGGEST_H;
        return (my >= suggestY + 1 && i >= 0 && i < suggestions.size()) ? i : -1;
    }

    /** Put a completion in the field and close the popup; {@code defocus} also drops the caret. */
    private void applySuggestion(String value, boolean defocus) {
        editingString.setValue(value);
        suggestSel = -1;
        suggestions = Collections.emptyList();
        if (defocus) editingString = null;
    }

    private void drawSidebar(int mouseX, int mouseY) {
        // Sidebar fill + separator
        DrawUtils.drawRect(winX + 1, winY + 1, SB_W - 1, winH - 2, C_SIDEBAR_BG);
        drawRect(winX + SB_W - 1, winY + 1, winX + SB_W, winY + winH - 1, 0x33FFFFFF);

        // Title with accent underline
        fontRendererObj.drawString("Plutonium", winX + 10, winY + 9, C_TEXT_MAIN);
        drawRect(winX + 10, winY + 19, winX + 10 + fontRendererObj.getStringWidth("Plutonium"), winY + 20, C_ACCENT);

        // Categories
        int y = winY + TITLE_H + 6;
        for (Category cat : Category.values()) {
            boolean active = search.isEmpty() && cat == activeTab;
            boolean hover = mouseX >= winX && mouseX < winX + SB_W - 1 && mouseY >= y && mouseY < y + 18;
            if (active) {
                DrawUtils.drawGradientRectH(winX + 1, y, SB_W - 2, 18, accentA(0x33), accentA(0x05));
                drawRect(winX + 1, y + 2, winX + 3, y + 16, C_ACCENT);
            } else if (hover) {
                drawRect(winX + 1, y, winX + SB_W - 1, y + 18, C_ROW_HOVER);
            }
            fontRendererObj.drawString(cat.getDisplayName(), winX + 12, y + 5,
                    active ? C_TEXT_MAIN : (hover ? 0xFFCCD6E6 : C_TEXT_DIM));
            y += 18;
        }

        // Bottom buttons: Theme / Accounts / HUD Editor
        drawSidebarButton("Theme", winX + 8, winY + winH - 66, SB_W - 16, 16, mouseX, mouseY);
        drawSidebarButton("Accounts", winX + 8, winY + winH - 46, SB_W - 16, 16, mouseX, mouseY);
        drawSidebarButton("HUD Editor", winX + 8, winY + winH - 26, SB_W - 16, 16, mouseX, mouseY);
    }

    private void drawSidebarButton(String label, int x, int y, int w, int h, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        DrawUtils.drawRoundedRect(x, y, w, h, hover ? C_BTN_HOVER : C_BTN_BG);
        fontRendererObj.drawString(label, x + (w - fontRendererObj.getStringWidth(label)) / 2,
                y + (h - 8) / 2 + 1, C_TEXT_MAIN);
    }

    private void drawSearchRow(int mouseX, int mouseY) {
        int paneX = winX + SB_W;
        int paneW = winW - SB_W;

        // Search field
        int fx = paneX + 8, fy = winY + 6, fw = paneW - 8 - 22, fh = 16;
        DrawUtils.drawRoundedRect(fx, fy, fw, fh, C_FIELD_BG);
        DrawUtils.drawRoundedRectBorder(fx, fy, fw, fh, search.isEmpty() ? borderA(0x66) : accentA(0xCC));
        if (search.isEmpty()) {
            fontRendererObj.drawString("Search modules...", fx + 6, fy + 4, C_TEXT_FAINT);
        } else {
            String shown = search;
            boolean caret = (System.currentTimeMillis() / 500) % 2 == 0;
            fontRendererObj.drawString(shown + (caret ? "_" : ""), fx + 6, fy + 4, C_TEXT_MAIN);
        }

        // Close button
        int closeX = paneX + paneW - 16, closeY = winY + 9;
        boolean hoverClose = mouseX >= closeX - 2 && mouseX < closeX + 9 && mouseY >= closeY - 2 && mouseY < closeY + 10;
        fontRendererObj.drawString("x", closeX, closeY, hoverClose ? 0xFFFF5555 : C_TEXT_DIM);
    }

    private void drawModuleRow(Hit h, int mx, int my, boolean searching) {
        Module m = h.module;
        boolean on = m.isEnabled();
        boolean hover = h.contains(mx, my);
        boolean hasSettings = !m.getSettings().isEmpty();

        if (hover) DrawUtils.drawRoundedRect(h.x, h.y, h.w, h.h, C_ROW_HOVER);
        if (on) drawRect(h.x, h.y + 4, h.x + 2, h.y + h.h - 4, C_ACCENT);

        fontRendererObj.drawString(m.getName(), h.x + 8, h.y + 6, on ? C_TEXT_MAIN : C_TEXT_DIM);

        int rightEdge = h.x + h.w;

        // Toggle
        drawToggle(rightEdge - 24, h.y + 5, on);

        // Expand chevron
        if (hasSettings) {
            DrawUtils.drawChevron(rightEdge - 36, h.y + 7, expanded == m,
                    hover ? C_TEXT_MAIN : C_TEXT_DIM);
        }

        // Keybind chip / awaiting state
        String keyLabel = null;
        int keyColor = C_TEXT_FAINT;
        if (awaitingModuleKey == m) {
            keyLabel = "[...]";
            keyColor = 0xFFFFAA00;
        } else if (m.getKeyCode() != 0) {
            String kn = plutonium.gpu.KeyboardShim.getKeyName(m.getKeyCode());
            if (kn != null) keyLabel = "[" + kn + "]";
        }
        if (keyLabel != null) {
            fontRendererObj.drawString(keyLabel,
                    rightEdge - 44 - fontRendererObj.getStringWidth(keyLabel), h.y + 6, keyColor);
        }

        // Category label when searching across tabs
        if (searching) {
            String cat = m.getCategory().getDisplayName();
            int catX = rightEdge - 44 - (keyLabel != null ? fontRendererObj.getStringWidth(keyLabel) + 6 : 0)
                    - fontRendererObj.getStringWidth(cat);
            fontRendererObj.drawString(cat, catX, h.y + 6, C_TEXT_FAINT);
        }
    }

    private void drawSettingRow(Hit h, int mx, int my) {
        Setting<?> s = h.setting;
        // Settings block backdrop + guide line
        drawRect(h.x - 4, h.y, h.x + h.w + 4, h.y + h.h, C_SETTINGS_BG);
        drawRect(h.x - 2, h.y, h.x - 1, h.y + h.h, accentA(0x55));

        fontRendererObj.drawString(s.getName(), h.x + 6, h.y + 5, C_TEXT_DIM);

        int rightEdge = h.x + h.w;

        if (s instanceof BoolSetting) {
            drawToggle(rightEdge - 22, h.y + 4, ((BoolSetting) s).getValue());

        } else if (s instanceof SliderSetting) {
            SliderSetting ss = (SliderSetting) s;
            float frac = (ss.getValue() - ss.getMin()) / (ss.getMax() - ss.getMin());
            String valStr = String.format("%.1f", ss.getValue());
            fontRendererObj.drawString(valStr, rightEdge - 4 - fontRendererObj.getStringWidth(valStr), h.y + 5, C_TEXT_MAIN);

            int tx = sliderTrackX(h), tw = sliderTrackW(h);
            int ty = h.y + 7;
            drawRect(tx, ty, tx + tw, ty + 3, C_TOGGLE_OFF);
            drawRect(tx, ty, tx + (int) (frac * tw), ty + 3, C_ACCENT);
            int knobX = tx + (int) (frac * tw) - 2;
            drawRect(knobX, ty - 2, knobX + 4, ty + 5, 0xFFFFFFFF);

        } else if (s instanceof KeySetting) {
            KeySetting ks = (KeySetting) s;
            String keyName = awaitingKey == ks ? "..." : ks.getKeyName();
            int bw = Math.max(28, fontRendererObj.getStringWidth(keyName) + 10);
            int bx = rightEdge - 4 - bw;
            boolean hoverKey = mx >= bx && mx < bx + bw && my >= h.y + 2 && my < h.y + h.h - 2;
            DrawUtils.drawRoundedRect(bx, h.y + 2, bw, h.h - 4, hoverKey ? 0x55FFFFFF : 0x33FFFFFF);
            fontRendererObj.drawString(keyName, bx + (bw - fontRendererObj.getStringWidth(keyName)) / 2,
                    h.y + 5, awaitingKey == ks ? 0xFFFFAA00 : C_TEXT_MAIN);

        } else if (s instanceof ColorSetting) {
            ColorSetting cs = (ColorSetting) s;
            DrawUtils.drawRoundedRect(rightEdge - 20, h.y + 3, 16, h.h - 6, cs.getValue() | 0xFF000000);
            DrawUtils.drawRoundedRectBorder(rightEdge - 20, h.y + 3, 16, h.h - 6, 0x66FFFFFF);

        } else if (s instanceof StringSetting) {
            StringSetting ss = (StringSetting) s;
            boolean editing = editingString == ss;
            int fw = h.w / 2 - 6;
            int fx = rightEdge - 4 - fw;
            DrawUtils.drawRoundedRect(fx, h.y + 2, fw, h.h - 4, C_FIELD_BG);
            DrawUtils.drawRoundedRectBorder(fx, h.y + 2, fw, h.h - 4, editing ? accentA(0xCC) : borderA(0x66));
            String val = ss.getValue() == null ? "" : ss.getValue();
            boolean caret = editing && (System.currentTimeMillis() / 500) % 2 == 0;
            String shown = (val.isEmpty() && !editing) ? "<none>" : val;
            // Right-trim the visible text so it fits the field, keeping the caret in view
            while (shown.length() > 0
                    && fontRendererObj.getStringWidth(shown + (caret ? "_" : "")) > fw - 8) {
                shown = shown.substring(1);
            }
            int col = (val.isEmpty() && !editing) ? C_TEXT_FAINT : C_TEXT_MAIN;
            fontRendererObj.drawString(shown + (caret ? "_" : ""), fx + 4, h.y + 5, col);

            if (editing) {
                suggestions = suggestionsFor(ss);
                if (suggestSel >= suggestions.size()) suggestSel = suggestions.size() - 1;
                int popupH = suggestions.size() * SUGGEST_H + 2;
                suggestX = fx;
                suggestW = fw;
                // Below the field, flipping above it when there is no room left in the window
                suggestY = h.y + h.h - 2;
                if (suggestY + popupH > winY + winH) suggestY = h.y + 2 - popupH;
            }
        }

        // Tooltip when hovering the label half of the row
        if (h.contains(mx, my) && mx < h.x + h.w / 2
                && s.getDescription() != null && !s.getDescription().isEmpty()) {
            tooltipSetting = s;
            tooltipX = mx;
            tooltipY = my;
        }
    }

    private int sliderTrackX(Hit h) { return h.x + 6 + fontRendererObj.getStringWidth(h.setting.getName()) + 8; }
    private int sliderTrackW(Hit h) { return (h.x + h.w - 34) - sliderTrackX(h); }

    private void drawToggle(int x, int y, boolean on) {
        DrawUtils.drawRoundedRect(x, y, 20, 10, on ? C_TOGGLE_ON : C_TOGGLE_OFF);
        int knobX = on ? x + 11 : x + 1;
        DrawUtils.drawRoundedRect(knobX, y + 1, 8, 8, 0xFFFFFFFF);
    }

    private void drawTooltip(Setting<?> s, int mx, int my) {
        List<String> lines = fontRendererObj.listFormattedStringToWidth(s.getDescription(), 140);
        int tw = 0;
        for (String l : lines) tw = Math.max(tw, fontRendererObj.getStringWidth(l));
        int th = lines.size() * 10 + 6;
        int x = Math.min(mx + 10, width - tw - 12);
        int y = Math.min(my - th - 2 < 2 ? my + 12 : my - th - 2, height - th - 2);

        DrawUtils.drawShadow(x, y, tw + 10, th, DrawUtils.CORNER_RADIUS);
        DrawUtils.drawRoundedRect(x, y, tw + 10, th, 0xF60E1118);
        DrawUtils.drawRoundedRectBorder(x, y, tw + 10, th, C_WIN_BORDER);
        int ly = y + 4;
        for (String l : lines) {
            fontRendererObj.drawString(l, x + 5, ly, C_TEXT_DIM);
            ly += 10;
        }
    }

    // ── input ────────────────────────────────────────────────────────

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        // The completion popup overhangs the rows below it, so it claims the click first
        if (editingString != null) {
            int picked = suggestIndexAt(mouseX, mouseY);
            if (picked >= 0) {
                applySuggestion(suggestions.get(picked), true);
                return;
            }
        }

        // Any click defocuses the text field; handleSettingClick re-focuses if a field was hit
        editingString = null;
        suggestSel = -1;

        // Sidebar buttons
        if (DrawUtils.inBox(mouseX, mouseY, winX + 8, winY + winH - 66, SB_W - 16, 16)) {
            mc.displayGuiScreen(new GuiThemeEditor(this));
            return;
        }
        if (DrawUtils.inBox(mouseX, mouseY, winX + 8, winY + winH - 46, SB_W - 16, 16)) {
            mc.displayGuiScreen(new GuiNameChanger(this));
            return;
        }
        if (DrawUtils.inBox(mouseX, mouseY, winX + 8, winY + winH - 26, SB_W - 16, 16)) {
            mc.displayGuiScreen(new GuiHUDEditor());
            return;
        }

        // Categories
        int cy = winY + TITLE_H + 6;
        for (Category cat : Category.values()) {
            if (DrawUtils.inBox(mouseX, mouseY, winX + 1, cy, SB_W - 2, 18)) {
                activeTab = cat;
                search = "";
                scrollY = 0;
                expanded = null;
                return;
            }
            cy += 18;
        }

        // Close button
        if (DrawUtils.inBox(mouseX, mouseY, winX + winW - 18, winY + 7, 12, 12)) {
            mc.displayGuiScreen(null);
            return;
        }

        // Module list
        int listTop = winY + TOP_H;
        int listBottom = winY + winH - FOOT_H;
        if (mouseY >= listTop && mouseY < listBottom && mouseX >= winX + SB_W && mouseX < winX + winW) {
            computeLayout();
            for (Hit h : hits) {
                if (!h.contains(mouseX, mouseY)) continue;
                if (h.setting == null) handleModuleClick(h, mouseX, button);
                else handleSettingClick(h, mouseX, mouseY, button);
                return;
            }
            return;
        }

        // Window drag from the sidebar title region or the gap around the search field
        if (DrawUtils.inBox(mouseX, mouseY, winX, winY, SB_W, TITLE_H)) {
            dragging = true;
            dragOffX = mouseX - winX;
            dragOffY = mouseY - winY;
        }
    }

    private void handleModuleClick(Hit h, int mouseX, int button) {
        Module m = h.module;
        int rightEdge = h.x + h.w;
        boolean hasSettings = !m.getSettings().isEmpty();

        if (button == 2) {
            awaitingModuleKey = m;
            return;
        }
        // Toggle zone
        if (mouseX >= rightEdge - 28) {
            m.toggle();
            return;
        }
        // Chevron zone
        if (hasSettings && mouseX >= rightEdge - 42 && mouseX < rightEdge - 28) {
            expanded = expanded == m ? null : m;
            return;
        }
        if (button == 0) {
            m.toggle();
        } else if (button == 1 && hasSettings) {
            expanded = expanded == m ? null : m;
        }
    }

    private void handleSettingClick(Hit h, int mouseX, int mouseY, int button) {
        Setting<?> s = h.setting;
        if (s instanceof BoolSetting) {
            ((BoolSetting) s).toggle();
        } else if (s instanceof SliderSetting) {
            // Only grab from the track area — a click on the label must not slam the value
            if (mouseX < sliderTrackX(h) - 4) return;
            draggingSlider = (SliderSetting) s;
            updateSlider(h, mouseX);
        } else if (s instanceof KeySetting) {
            awaitingKey = (KeySetting) s;
        } else if (s instanceof ColorSetting) {
            if (mouseX < h.x + h.w / 2) return; // label half = tooltip, not cycle
            ColorSetting cs = (ColorSetting) s;
            int cur = cs.getValue();
            int idx = 0;
            for (int i = 0; i < COLOR_PRESETS.length; i++) {
                if (COLOR_PRESETS[i] == cur) { idx = button == 1 ? i - 1 : i + 1; break; }
                if (i == COLOR_PRESETS.length - 1) idx = 0; // current color not in palette
            }
            idx = ((idx % COLOR_PRESETS.length) + COLOR_PRESETS.length) % COLOR_PRESETS.length;
            cs.setValue(COLOR_PRESETS[idx]);
        } else if (s instanceof StringSetting) {
            StringSetting ss = (StringSetting) s;
            if (ss.isMultiline()) {
                mc.displayGuiScreen(new GuiTextEditor(this, ss));
            } else {
                editingString = ss;
            }
        }
    }

    private void updateSlider(Hit h, int mouseX) {
        SliderSetting ss = (SliderSetting) h.setting;
        int tx = sliderTrackX(h), tw = sliderTrackW(h);
        float frac = (float) (mouseX - tx) / tw;
        frac = Math.max(0, Math.min(1, frac));
        float raw = ss.getMin() + frac * (ss.getMax() - ss.getMin());
        float inc = ss.getIncrement();
        if (inc > 0) raw = Math.round(raw / inc) * inc;
        ss.setValue(raw);
    }



    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long timeSinceLastClick) {
        if (dragging) {
            winX = clamp(mouseX - dragOffX, 0, width - winW);
            winY = clamp(mouseY - dragOffY, 0, height - winH);
            savedWinX = winX;
            savedWinY = winY;
        }
        if (draggingSlider != null) {
            computeLayout();
            for (Hit h : hits) {
                if (h.setting == draggingSlider) {
                    updateSlider(h, mouseX);
                    break;
                }
            }
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        dragging = false;
        draggingSlider = null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (editingString != null) {
            List<String> matches = suggestionsFor(editingString);
            if (keyCode == 200 || keyCode == 208) { // up / down: walk the completion list
                if (keyCode == 208) suggestSel = Math.min(suggestSel + 1, matches.size() - 1);
                else                suggestSel = Math.max(suggestSel - 1, -1);
                return;
            }
            if (keyCode == 15 && !matches.isEmpty()) { // tab: take the highlighted completion
                applySuggestion(matches.get(Math.max(suggestSel, 0)), false);
                return;
            }
            if (keyCode == 28 && suggestSel >= 0 && suggestSel < matches.size()) { // enter on a highlight
                applySuggestion(matches.get(suggestSel), true);
                return;
            }
            if (keyCode == 1 || keyCode == 28) { // ESC or Enter: commit & defocus
                editingString = null;
                suggestSel = -1;
                return;
            }
            suggestSel = -1; // the text is about to change, so the old highlight is meaningless
            if (keyCode == 14) { // backspace
                String v = editingString.getValue();
                if (v != null && !v.isEmpty()) editingString.setValue(v.substring(0, v.length() - 1));
                return;
            }
            if (isKeyComboCtrlV(keyCode)) { // paste (e.g. a long API key) — filter + cap to maxLength
                String v = editingString.getValue();
                if (v == null) v = "";
                StringBuilder sb = new StringBuilder(v);
                String clip = getClipboardString();
                for (int i = 0; clip != null && i < clip.length() && sb.length() < editingString.getMaxLength(); i++) {
                    char c = clip.charAt(i);
                    if (ChatAllowedCharacters.isAllowedCharacter(c)) sb.append(c);
                }
                editingString.setValue(sb.toString());
                return;
            }
            if (isKeyComboCtrlC(keyCode)) { // copy current value
                setClipboardString(editingString.getValue() == null ? "" : editingString.getValue());
                return;
            }
            if (isKeyComboCtrlX(keyCode)) { // cut current value
                setClipboardString(editingString.getValue() == null ? "" : editingString.getValue());
                editingString.setValue("");
                return;
            }
            if (ChatAllowedCharacters.isAllowedCharacter(typedChar)) {
                String v = editingString.getValue();
                if (v == null) v = "";
                if (v.length() < editingString.getMaxLength()) editingString.setValue(v + typedChar);
            }
            return;
        }
        if (awaitingKey != null) {
            awaitingKey.setValue(keyCode == 1 ? 0 : keyCode);
            awaitingKey = null;
            return;
        }
        if (awaitingModuleKey != null) {
            awaitingModuleKey.setKeyCode(keyCode == 1 ? 0 : keyCode);
            plutonium.Config.save();
            awaitingModuleKey = null;
            return;
        }
        if (keyCode == 1) { // ESC: clear search → collapse → close
            if (!search.isEmpty()) { search = ""; scrollY = 0; return; }
            if (expanded != null) { expanded = null; return; }
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == 14) { // backspace
            if (!search.isEmpty()) {
                search = search.substring(0, search.length() - 1);
                scrollY = 0;
            }
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(typedChar) && search.length() < 24) {
            search += typedChar;
            scrollY = 0;
            expanded = null;
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int scroll = MouseShim.getEventDWheel();
        if (scroll != 0) {
            scrollY = clamp(scrollY - scroll / 10, 0, maxScrollY);
        }
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}
