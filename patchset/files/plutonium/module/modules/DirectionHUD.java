package plutonium.module.modules;

import net.minecraft.client.gui.clickgui.DrawUtils;
import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.ColorSetting;

public class DirectionHUD extends TextHudModule {

    private static final String[] DIRS = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};

    private final ColorSetting accentColor = reg(new ColorSetting("Accent", "Direction text color", 0xFF5B7CA8));

    public DirectionHUD() {
        super("Direction HUD", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    protected String[] getHudLines() {
        if (mc.thePlayer == null) return null;
        float yaw = mc.thePlayer.rotationYaw % 360f;
        if (yaw < 0) yaw += 360f;
        int idx = (int) ((yaw + 22.5f) / 45f) % 8;
        int deg = ((int) yaw + 180) % 360;
        return new String[]{DIRS[idx], deg + "°"};
    }

    @Override
    protected int getLineColor(int index) {
        return index == 0 ? accentColor.getValue() : DrawUtils.CHIP_TEXT;
    }

    @Override protected boolean isCentered() { return true; }
    @Override public int getHudWidth() { return Math.max(super.getHudWidth(), 40); }
    @Override protected int defaultHudX(int sw, int sh) { return sw / 2 - getHudWidthScaled() / 2; }
    @Override protected int defaultHudY(int sw, int sh) { return 2; }
}
