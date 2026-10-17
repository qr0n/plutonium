package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class NameTags extends Module {

    public static NameTags instance;

    private final SliderSetting scale = reg(new SliderSetting("Scale", "Name tag size multiplier", 1.5f, 0.5f, 4.0f, 0.1f));
    private final SliderSetting range = reg(new SliderSetting("Range", "Visible range multiplier", 2.0f, 1.0f, 8.0f, 0.5f));

    public NameTags() {
        super("NameTags", Category.VISUAL);
        instance = this;
        initEnabled(true);
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }

    public static float getScale() {
        return instance != null ? instance.scale.getValue() : 1.0f;
    }

    public static float getRangeMultiplier() {
        return instance != null ? instance.range.getValue() : 1.0f;
    }
}
