package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.ColorSetting;

public class Hitbox extends Module {

    private final ColorSetting boxColor = reg(new ColorSetting("Color", "Hitbox line color", 0xFFFFFFFF));

    public Hitbox() {
        super("Hitbox", Category.VISUAL);
    }

    @Override
    public void onEnable() {
        if (mc.getRenderManager() != null)
            mc.getRenderManager().setDebugBoundingBox(true);
    }

    @Override
    public void onDisable() {
        if (mc.getRenderManager() != null)
            mc.getRenderManager().setDebugBoundingBox(false);
    }
}
