package plutonium.module.modules;

import net.minecraft.util.MathHelper;
import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.BoolSetting;
import plutonium.setting.ColorSetting;

public class Coordinates extends TextHudModule {

    private final BoolSetting showDecimals = reg(new BoolSetting("Decimals", "Show decimal precision", false));
    private final ColorSetting textColor = reg(new ColorSetting("Color", "Text color", 0xFFE6EAF2));

    public Coordinates() {
        super("Coordinates", Category.VISUAL);
    }

    @Override
    protected String[] getHudLines() {
        if (mc.thePlayer == null) return null;
        if (showDecimals.getValue()) {
            return new String[]{
                String.format("X: %.1f", mc.thePlayer.posX),
                String.format("Y: %.1f", mc.thePlayer.posY),
                String.format("Z: %.1f", mc.thePlayer.posZ)
            };
        }
        return new String[]{
            "X: " + MathHelper.floor_double(mc.thePlayer.posX),
            "Y: " + MathHelper.floor_double(mc.thePlayer.posY),
            "Z: " + MathHelper.floor_double(mc.thePlayer.posZ)
        };
    }

    @Override protected int getLineColor(int index) { return textColor.getValue(); }
    @Override protected int defaultHudX(int sw, int sh) { return 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh - getHudHeightScaled() - 2; }
}
