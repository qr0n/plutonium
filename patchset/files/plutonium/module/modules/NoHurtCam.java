package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

/** Removes the camera roll vanilla applies when the player takes damage. */
public class NoHurtCam extends Module {

    public static NoHurtCam instance;

    private final SliderSetting shake = reg(new SliderSetting("Shake", "Percent of the vanilla hit shake to keep", 0.0F, 0.0F, 100.0F, 5.0F));

    public NoHurtCam() {
        super("No Hurt Cam", Category.VISUAL);
        instance = this;
    }

    /** Multiplier for the hurt camera roll — 1.0 is vanilla, 0.0 removes it entirely. */
    public static float getShakeScale() {
        return instance != null && instance.isEnabled() ? instance.shake.getValue() / 100.0F : 1.0F;
    }
}
