package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.BoolSetting;

public class SneakToggle extends TextHudModule {

    private boolean sneaking = false;
    private boolean prevKeyDown = false;

    private final BoolSetting indicator = reg(new BoolSetting("Indicator", "Show sneak status on HUD", true));

    public SneakToggle() {
        super("Sneak Toggle", Category.MOVEMENT);
    }

    @Override
    public void onDisable() {
        sneaking = false;
        if (mc.thePlayer != null) mc.thePlayer.setSneaking(false);
    }

    @Override
    public void onTick() {
        if (mc.thePlayer == null || mc.currentScreen != null) return;

        boolean keyDown = mc.gameSettings.keyBindSneak.isKeyDown();
        if (keyDown && !prevKeyDown) {
            sneaking = !sneaking;
        }
        prevKeyDown = keyDown;

        mc.thePlayer.setSneaking(sneaking);
    }

    @Override
    protected String[] getHudLines() {
        if (!indicator.getValue() || !sneaking) return null;
        return new String[]{"Sneaking"};
    }

    @Override protected int getLineColor(int index) { return 0xFFFFAA00; }
    @Override protected int defaultHudY(int sw, int sh) { return sh - 52; }
}
