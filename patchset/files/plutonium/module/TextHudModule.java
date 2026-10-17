package plutonium.module;

import net.minecraft.client.gui.clickgui.DrawUtils;
import net.minecraft.client.gui.clickgui.GuiHUDEditor;

/**
 * Base class for text HUD elements. Subclasses supply lines via {@link #getHudLines()};
 * this class draws them inside the shared chip style (translucent navy panel + accent
 * bar) with consistent padding, sizing the chip to the text each frame.
 */
public abstract class TextHudModule extends Module {

    protected static final int PAD_X_LEFT  = 7;  // accent bar (2) + gap
    protected static final int PAD_X_RIGHT = 5;
    protected static final int PAD_Y       = 4;
    protected static final int LINE_H      = 10;

    // Last computed chip size — fallback for the HUD editor when lines are unavailable
    private int lastW = 40;
    private int lastH = 16;

    protected TextHudModule(String name, Category category) {
        super(name, category);
    }

    /** Lines to display this frame, or null/empty to draw nothing. */
    protected abstract String[] getHudLines();

    /** Color of line {@code index}. */
    protected int getLineColor(int index) { return DrawUtils.CHIP_TEXT; }

    /** Center lines horizontally inside the chip instead of left-aligning. */
    protected boolean isCentered() { return false; }

    @Override
    public void onRenderHUD(int sw, int sh) {
        String[] lines = linesSafe();
        if (lines == null || lines.length == 0) {
            // In the HUD editor, conditional elements (combo, sneak...) still need a body to drag
            if (mc.currentScreen instanceof GuiHUDEditor) lines = new String[]{getName()};
            else return;
        }
        measure(lines);

        int x = resolveHudX(sw, sh);
        int y = resolveHudY(sw, sh);
        DrawUtils.drawChip(x, y, lastW, lastH);

        int innerW = lastW - PAD_X_LEFT - PAD_X_RIGHT;
        int ty = y + PAD_Y;
        for (int i = 0; i < lines.length; i++) {
            int tx = x + PAD_X_LEFT;
            if (isCentered()) tx += (innerW - mc.fontRendererObj.getStringWidth(lines[i])) / 2;
            mc.fontRendererObj.drawStringWithShadow(lines[i], tx, ty, getLineColor(i));
            ty += LINE_H;
        }
    }

    @Override
    public int getHudWidth() {
        String[] lines = linesSafe();
        if (lines != null && lines.length > 0) measure(lines);
        return lastW;
    }

    @Override
    public int getHudHeight() {
        String[] lines = linesSafe();
        if (lines != null && lines.length > 0) measure(lines);
        return lastH;
    }

    private void measure(String[] lines) {
        int max = 0;
        for (String line : lines) {
            int w = mc.fontRendererObj.getStringWidth(line);
            if (w > max) max = w;
        }
        lastW = max + PAD_X_LEFT + PAD_X_RIGHT;
        lastH = lines.length * LINE_H - 2 + PAD_Y * 2; // last line is 8px tall, not 10
    }

    private String[] linesSafe() {
        try {
            return getHudLines();
        } catch (Exception e) {
            return null;
        }
    }
}
