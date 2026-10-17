package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class FOVChanger extends Module {

    public static FOVChanger instance;

    private final SliderSetting fov = reg(new SliderSetting("FOV", "Field of view", 90f, 30f, 150f, 1f));

    public FOVChanger() {
        super("FOV Changer", Category.VISUAL);
        instance = this;
    }

    public static float processFOV(float fov) {
        if (instance == null || !instance.isEnabled()) return fov;
        return instance.fov.getValue();
    }
}
