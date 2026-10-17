package net.minecraft.client.gui.clickgui;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import plutonium.gpu.Batches;
import plutonium.gpu.RoundRectBatch;

public final class DrawUtils {

    private DrawUtils() {}

    // ── Rounded-rect rendering defaults (CORNER_RADIUS + SHADOW_COLOR are theme-driven; see applyTheme) ──
    public static       float CORNER_RADIUS = 4f;  // px corner radius for panels/chips
    public static final float EDGE_AA       = 1.0f; // px anti-alias softness on edges
    public static       int   SHADOW_COLOR  = 0x66000000; // soft drop shadow tint (alpha 0 = disabled)
    public static final float SHADOW_BLUR   = 5f;  // px shadow falloff
    public static final int   SHADOW_OFF_Y  = 2;   // px shadow drop

    /**
     * Routes a rounded fill through the anti-aliased SDF batch when available, flushing
     * immediately on screens (no HUD batching) so glScissor clipping and draw order hold.
     * Falls back to the legacy corner-cut when the SDF path has self-disabled.
     */
    private static void sdfRect(int x, int y, int w, int h, float radius, int color) {
        RoundRectBatch.addRect(x, y, w, h, radius, EDGE_AA, color);
        if (!Batches.guiBatchingActive()) RoundRectBatch.flush();
    }

    private static void sdfBorder(int x, int y, int w, int h, float radius, float borderW, int color) {
        RoundRectBatch.addBorder(x, y, w, h, radius, EDGE_AA, borderW, color);
        if (!Batches.guiBatchingActive()) RoundRectBatch.flush();
    }

    /** Soft drop shadow behind a rounded panel/chip of the given bounds. */
    public static void drawShadow(int x, int y, int w, int h, float radius) {
        if (!RoundRectBatch.isAvailable()) return;   // shadow is a nicety; skip on fallback
        if ((SHADOW_COLOR >>> 24) == 0) return;       // shadow disabled via theme
        RoundRectBatch.addRect(x, y + SHADOW_OFF_Y, w, h, radius + SHADOW_BLUR, SHADOW_BLUR, SHADOW_COLOR);
        if (!Batches.guiBatchingActive()) RoundRectBatch.flush();
    }

    // Vertical gradient. Colors are ARGB.
    public static void drawGradientRect(int x, int y, int w, int h, int topColor, int bottomColor) {
        if (w <= 0 || h <= 0) return;

        float aT = (topColor >> 24 & 255) / 255f,    rT = (topColor >> 16 & 255) / 255f;
        float gT = (topColor >>  8 & 255) / 255f,    bT = (topColor       & 255) / 255f;
        float aB = (bottomColor >> 24 & 255) / 255f, rB = (bottomColor >> 16 & 255) / 255f;
        float gB = (bottomColor >>  8 & 255) / 255f, bB = (bottomColor       & 255) / 255f;

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(7425); // GL_SMOOTH

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x,     y,     0).color(rT, gT, bT, aT).endVertex();
        wr.pos(x + w, y,     0).color(rT, gT, bT, aT).endVertex();
        wr.pos(x + w, y + h, 0).color(rB, gB, bB, aB).endVertex();
        wr.pos(x,     y + h, 0).color(rB, gB, bB, aB).endVertex();
        tess.draw();

        GlStateManager.shadeModel(7424); // GL_FLAT
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    // Horizontal gradient. Colors are ARGB.
    public static void drawGradientRectH(int x, int y, int w, int h, int leftColor, int rightColor) {
        if (w <= 0 || h <= 0) return;

        float aL = (leftColor >> 24 & 255) / 255f,  rL = (leftColor >> 16 & 255) / 255f;
        float gL = (leftColor >>  8 & 255) / 255f,  bL = (leftColor       & 255) / 255f;
        float aR = (rightColor >> 24 & 255) / 255f, rR = (rightColor >> 16 & 255) / 255f;
        float gR = (rightColor >>  8 & 255) / 255f, bR = (rightColor       & 255) / 255f;

        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableTexture2D();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(7425);

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(x,     y,     0).color(rL, gL, bL, aL).endVertex();
        wr.pos(x + w, y,     0).color(rR, gR, bR, aR).endVertex();
        wr.pos(x + w, y + h, 0).color(rR, gR, bR, aR).endVertex();
        wr.pos(x,     y + h, 0).color(rL, gL, bL, aL).endVertex();
        tess.draw();

        GlStateManager.shadeModel(7424);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }

    // Fills a rectangle. Color is ARGB.
    public static void drawRect(int x, int y, int w, int h, int color) {
        Gui.drawRect(x, y, x + w, y + h, color);
    }

    // Anti-aliased rounded rectangle fill (SDF), falling back to the corner-cut below.
    public static void drawRoundedRect(int x, int y, int w, int h, int color) {
        if (RoundRectBatch.isAvailable()) { sdfRect(x, y, w, h, CORNER_RADIUS, color); return; }
        legacyRoundedRect(x, y, w, h, color);
    }

    // 2-pixel rounded rectangle fill (legacy corner-cut fallback).
    public static void legacyRoundedRect(int x, int y, int w, int h, int color) {
        Gui.drawRect(x + 2, y,     x + w - 2, y + h,     color); // center body
        Gui.drawRect(x,     y + 2, x + 2,     y + h - 2, color); // left strip
        Gui.drawRect(x + w - 2, y + 2, x + w, y + h - 2, color); // right strip
        Gui.drawRect(x + 1, y + 1, x + 2,     y + 2,     color); // top-left corner
        Gui.drawRect(x + w - 2, y + 1, x + w - 1, y + 2, color); // top-right corner
        Gui.drawRect(x + 1, y + h - 2, x + 2, y + h - 1, color); // bot-left corner
        Gui.drawRect(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color); // bot-right corner
    }

    // Anti-aliased rounded outline (SDF), falling back to the corner-cut below.
    public static void drawRoundedRectBorder(int x, int y, int w, int h, int color) {
        if (RoundRectBatch.isAvailable()) { sdfBorder(x, y, w, h, CORNER_RADIUS, 1f, color); return; }
        legacyRoundedRectBorder(x, y, w, h, color);
    }

    // 2-pixel rounded rectangle outline (1px border, legacy fallback).
    public static void legacyRoundedRectBorder(int x, int y, int w, int h, int color) {
        Gui.drawRect(x + 2, y,         x + w - 2, y + 1,     color); // top
        Gui.drawRect(x + 2, y + h - 1, x + w - 2, y + h,     color); // bottom
        Gui.drawRect(x,     y + 2,     x + 1,     y + h - 2, color); // left
        Gui.drawRect(x + w - 1, y + 2, x + w,     y + h - 2, color); // right
        Gui.drawRect(x + 1, y + 1, x + 2, y + 2,             color); // top-left
        Gui.drawRect(x + w - 2, y + 1, x + w - 1, y + 2,     color); // top-right
        Gui.drawRect(x + 1, y + h - 2, x + 2, y + h - 1,     color); // bot-left
        Gui.drawRect(x + w - 2, y + h - 2, x + w - 1, y + h - 1, color); // bot-right
    }

    // ── Shared theme palette ───────────────────────────────────────────
    // Non-final fields are derived from the active Theme by applyTheme(); the values below are
    // just initial defaults (overwritten at startup by Theme.apply()). Theme-independent
    // tints (gloss, separator, faint card) stay constant.
    // Window / panel
    public static int T_SHADOW        = 0x55000000;
    public static int T_WIN_BG        = 0xCC080B16;
    public static int T_BORDER        = 0x886C63FF;

    // Header gradient
    public static int T_HEADER_TOP    = 0xFF5D50F0;
    public static int T_HEADER_BOT    = 0xFF3C31C0;
    public static final int T_GLOSS_TOP     = 0x22FFFFFF;
    public static final int T_GLOSS_BOT     = 0x00FFFFFF;

    // Text
    public static int T_TEXT_PRIMARY   = 0xFFEEECFF;
    public static int T_TEXT_SECONDARY = 0xFF8E8BB8;
    public static int T_TEXT_DISABLED  = 0xFF44445A;

    // Accents / interactive
    public static final int T_SEPARATOR     = 0x28FFFFFF;
    public static int T_ACCENT        = 0xFF5D50F0;
    public static int T_ACCENT_LIGHT  = 0xFF7B6FFF;
    public static int T_ACCENT_DARK   = 0xFF3D35B5;
    public static int T_ACCENT_MUTED  = 0x335D50F0;

    // Buttons
    public static int T_BTN_NORMAL    = 0xCC3D35B5;
    public static int T_BTN_HOVER     = 0xFF5249CC;
    public static int T_BTN_TEXT      = 0xFFEEECFF;
    public static final int T_BTN_DISABLED  = 0xFF222233;

    // Misc
    public static final int T_CARD_OFF_BG   = 0x22FFFFFF;
    public static int T_SLIDER_BG     = 0xFF1A1A2E;

    // ── HUD chip style (shared by all HUD elements) ─────────────────
    public static int CHIP_BG     = 0xC0080B16; // translucent base
    public static int CHIP_ACCENT = T_ACCENT;
    public static int CHIP_TEXT   = T_TEXT_PRIMARY;
    public static int CHIP_DIM    = T_TEXT_SECONDARY;

    /**
     * Re-derives the full palette from the active {@link plutonium.Theme} knobs. Called at
     * startup and whenever a theme value changes, so all GUI surfaces recolour live.
     * shadowAlpha 0 disables drop shadows.
     */
    public static void applyTheme(int accent, int surface, int base, int textPrimary,
                                  int textDim, int border, int radius, int surfaceAlpha,
                                  int shadowAlpha) {
        T_ACCENT       = accent;
        T_ACCENT_LIGHT = plutonium.Theme.lighten(accent, 0.25f);
        T_ACCENT_DARK  = plutonium.Theme.darken(accent, 0.35f);
        T_ACCENT_MUTED = plutonium.Theme.withAlpha(accent, 0x33);
        T_HEADER_TOP   = accent;
        T_HEADER_BOT   = plutonium.Theme.darken(accent, 0.30f);

        T_TEXT_PRIMARY   = textPrimary;
        T_TEXT_SECONDARY = textDim;
        T_TEXT_DISABLED  = plutonium.Theme.darken(textDim, 0.45f);

        T_WIN_BG = plutonium.Theme.withAlpha(surface, surfaceAlpha);
        T_BORDER = plutonium.Theme.withAlpha(border, 0x88);
        T_SHADOW = plutonium.Theme.withAlpha(0x000000, Math.max(shadowAlpha, 0x40));

        T_BTN_NORMAL = plutonium.Theme.withAlpha(plutonium.Theme.darken(accent, 0.30f), 0xCC);
        T_BTN_HOVER  = accent;
        T_BTN_TEXT   = textPrimary;
        T_SLIDER_BG  = plutonium.Theme.darken(surface, 0.25f) | 0xFF000000;

        CHIP_BG     = plutonium.Theme.withAlpha(base, 0xC0);
        CHIP_ACCENT = accent;
        CHIP_TEXT   = textPrimary;
        CHIP_DIM    = textDim;

        CORNER_RADIUS = radius;
        SHADOW_COLOR  = plutonium.Theme.withAlpha(0x000000, shadowAlpha);
    }

    // ── Hit-test utility ────────────────────────────────────────────
    public static boolean inBox(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    // ── Themed button ───────────────────────────────────────────────
    // Draws a rounded button with border, centered label, and hover highlight.
    public static void drawButton(net.minecraft.client.gui.FontRenderer fr,
                                  String label, int x, int y, int w, int h, boolean hover) {
        drawRoundedRect(x, y, w, h, hover ? T_BTN_HOVER : T_BTN_NORMAL);
        drawRoundedRectBorder(x, y, w, h, T_BORDER);
        fr.drawString(label, x + (w - fr.getStringWidth(label)) / 2, y + (h - 8) / 2, T_BTN_TEXT);
    }

    // Standard HUD chip: rounded translucent panel with an accent bar on the left edge.
    public static void drawChip(int x, int y, int w, int h) {
        drawChip(x, y, w, h, CHIP_ACCENT);
    }

    public static void drawChip(int x, int y, int w, int h, int accent) {
        drawShadow(x, y, w, h, CORNER_RADIUS);          // soft shadow (under the fill)
        drawRoundedRect(x, y, w, h, CHIP_BG);           // rounded glass fill
        // Sharp accent bar, inset by the corner radius so it stays inside the rounding.
        // RoundRectBatch flushes before RectBatch, so this sits cleanly on top of the fill.
        int inset = (int) Math.ceil(CORNER_RADIUS);
        Gui.drawRect(x, y + inset, x + 2, y + h - inset, accent);
    }

    // Small solid triangle pointing right (collapsed) or down (expanded) — chevron stand-in,
    // since the MC font has no reliable arrow glyphs.
    public static void drawChevron(int x, int y, boolean down, int color) {
        if (down) {
            Gui.drawRect(x,     y,     x + 5, y + 1, color);
            Gui.drawRect(x + 1, y + 1, x + 4, y + 2, color);
            Gui.drawRect(x + 2, y + 2, x + 3, y + 3, color);
        } else {
            Gui.drawRect(x,     y,     x + 1, y + 5, color);
            Gui.drawRect(x + 1, y + 1, x + 2, y + 4, color);
            Gui.drawRect(x + 2, y + 2, x + 3, y + 3, color);
        }
    }

    // Multiply alpha of a color by a factor.
    public static int alphaMultiply(int color, float factor) {
        int a = Math.min(255, (int)(((color >> 24) & 255) * factor));
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
