package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class Fullbright extends Module {

    private float savedGamma;
    private final SliderSetting brightness = reg(new SliderSetting("Brightness", "Gamma level", 10.0f, 1.0f, 15.0f, 0.5f));

    public Fullbright() {
        super("Fullbright", Category.VISUAL);
    }

    @Override
    public void onEnable() {
        savedGamma = mc.gameSettings.gammaSetting;
        mc.gameSettings.gammaSetting = brightness.getValue();
    }

    @Override
    public void onDisable() {
        mc.gameSettings.gammaSetting = savedGamma;
    }

    @Override
    public void onTick() {
        mc.gameSettings.gammaSetting = brightness.getValue();
    }
}
