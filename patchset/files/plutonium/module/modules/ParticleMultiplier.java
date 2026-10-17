package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class ParticleMultiplier extends Module {

    public static ParticleMultiplier instance;

    private final SliderSetting multiplier = reg(new SliderSetting("Multiplier", "Particle count multiplier", 2.0f, 0.0f, 4.0f, 0.5f));

    public ParticleMultiplier() {
        super("Particle Multiplier", Category.VISUAL);
        instance = this;
    }

    public static int getExtraSpawns() {
        if (instance == null || !instance.isEnabled()) return 0;
        float m = instance.multiplier.getValue();
        if (m <= 0f) return -1;
        return Math.max(0, (int) m - 1);
    }

    public static boolean isSuppressed() {
        return instance != null && instance.isEnabled() && instance.multiplier.getValue() <= 0f;
    }
}
