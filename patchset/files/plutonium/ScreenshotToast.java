package plutonium;

import java.awt.Desktop;
import java.io.File;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.gui.clickgui.DrawUtils;

public class ScreenshotToast {

    public static final ScreenshotToast INSTANCE = new ScreenshotToast();

    private static final int WIDTH  = 200;
    private static final int HEIGHT = 46;
    private static final int MARGIN = 8;
    private static final int SHOW_TICKS = 100; // ~5 seconds at 20tps

    private File file = null;
    private int ticksShown = 0;
    private float animIn  = 0f; // 0..1 slide-in progress
    private float animOut = 0f; // 0..1 slide-out progress

    private ScreenshotToast() {}

    public void show(File f) {
        this.file      = f;
        this.ticksShown = 0;
        this.animIn    = 0f;
        this.animOut   = 0f;
    }

    public void tick() {
        if (file == null) return;
        ticksShown++;
        if (ticksShown > SHOW_TICKS + 10) {
            file = null;
        }
    }

    public void render(int screenW, int screenH, float partialTicks) {
        File file = this.file;
        if (file == null) return;

        // Animate in / out
        float progress = ticksShown + partialTicks;
        float IN_TICKS  = 8f;
        float OUT_START = SHOW_TICKS - 8f;

        if (progress < IN_TICKS) {
            animIn = progress / IN_TICKS;
        } else {
            animIn = 1f;
        }

        if (progress > OUT_START) {
            animOut = (progress - OUT_START) / 10f;
        }

        float easeIn  = easeOut(animIn);
        float easeOut = easeIn(Math.min(1f, animOut));
        float alpha   = easeIn * (1f - easeOut);
        if (alpha <= 0.01f) return;

        // Slide from right
        float slideX = (1f - easeIn) * (WIDTH + MARGIN + 10);

        int x = (int)(screenW - WIDTH - MARGIN - slideX);
        int y = MARGIN;

        GlStateManager.pushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        // Shadow
        DrawUtils.drawRoundedRect(x + 3, y + 4, WIDTH, HEIGHT,
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(0x55000000, alpha));

        // Panel background
        DrawUtils.drawRoundedRect(x, y, WIDTH, HEIGHT,
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(net.minecraft.client.gui.clickgui.DrawUtils.T_WIN_BG, alpha));

        // Border
        DrawUtils.drawRoundedRectBorder(x, y, WIDTH, HEIGHT,
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(net.minecraft.client.gui.clickgui.DrawUtils.T_BORDER, alpha));

        // Header gradient (top 14px)
        DrawUtils.drawGradientRect(x + 2, y + 1, WIDTH - 4, 13,
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(net.minecraft.client.gui.clickgui.DrawUtils.T_HEADER_TOP, alpha),
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(net.minecraft.client.gui.clickgui.DrawUtils.T_HEADER_BOT, alpha));

        // Gloss strip
        DrawUtils.drawGradientRect(x + 2, y + 1, WIDTH - 4, 6,
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(0x22FFFFFF, alpha),
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(0x00FFFFFF, alpha));

        Minecraft mc = Minecraft.getMinecraft();

        // Title
        mc.fontRendererObj.drawString("Screenshot saved",
            x + 6, y + 4,
            applyAlpha(net.minecraft.client.gui.clickgui.DrawUtils.T_TEXT_PRIMARY, alpha), false);

        // Separator
        net.minecraft.client.gui.Gui.drawRect(x + 1, y + 14, x + WIDTH - 1, y + 15,
            applyAlpha(0x28FFFFFF, alpha));

        // Filename (truncated)
        String name = file.getName();
        if (mc.fontRendererObj.getStringWidth(name) > WIDTH - 12) {
            while (mc.fontRendererObj.getStringWidth(name + "...") > WIDTH - 12 && name.length() > 0) {
                name = name.substring(0, name.length() - 1);
            }
            name += "...";
        }
        mc.fontRendererObj.drawString(name, x + 6, y + 19, applyAlpha(net.minecraft.client.gui.clickgui.DrawUtils.T_TEXT_SECONDARY, alpha), false);

        // "Open Folder" button
        int btnX = x + 6;
        int btnY = y + 30;
        int btnW = 90;
        int btnH = 11;
        boolean hovered = isHovered(btnX, btnY, btnW, btnH, screenW, screenH);
        DrawUtils.drawRoundedRect(btnX, btnY, btnW, btnH,
            net.minecraft.client.gui.clickgui.DrawUtils.alphaMultiply(
                hovered ? net.minecraft.client.gui.clickgui.DrawUtils.T_BTN_HOVER : net.minecraft.client.gui.clickgui.DrawUtils.T_BTN_NORMAL, alpha));
        mc.fontRendererObj.drawString("Open Folder",
            btnX + btnW / 2 - mc.fontRendererObj.getStringWidth("Open Folder") / 2,
            btnY + 2,
            applyAlpha(net.minecraft.client.gui.clickgui.DrawUtils.T_TEXT_PRIMARY, alpha), false);

        GlStateManager.disableBlend();
        GlStateManager.popMatrix();
    }

    /** Call from mouse-click handling when the toast is visible. */
    public boolean handleClick(int mouseX, int mouseY, int screenW, int screenH) {
        if (file == null) return false;

        float progress  = ticksShown;
        float slideX    = (1f - easeOut(Math.min(1f, progress / 8f))) * (WIDTH + MARGIN + 10);
        int x = (int)(screenW - WIDTH - MARGIN - slideX);
        int y = MARGIN;

        int btnX = x + 6, btnY = y + 30, btnW = 90, btnH = 11;
        if (mouseX >= btnX && mouseX <= btnX + btnW && mouseY >= btnY && mouseY <= btnY + btnH) {
            try { Desktop.getDesktop().open(file.getParentFile()); } catch (Exception ignored) {}
            file = null;
            return true;
        }
        return false;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean isHovered(int bx, int by, int bw, int bh, int sw, int sh) {
        try {
            ScaledResolution sr = new ScaledResolution(Minecraft.getMinecraft());
            int mx = plutonium.gpu.MouseShim.getX() / sr.getScaleFactor();
            int my = sh - plutonium.gpu.MouseShim.getY() / sr.getScaleFactor() - 1;
            return mx >= bx && mx <= bx + bw && my >= by && my <= by + bh;
        } catch (Exception e) { return false; }
    }

    private static int applyAlpha(int argb, float alpha) {
        int a = Math.min(255, (int)(((argb >> 24) & 0xFF) * alpha));
        return (argb & 0x00FFFFFF) | (a << 24);
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static float easeIn(float t) {
        t = Math.max(0f, Math.min(1f, t));
        return t * t * t;
    }
}
