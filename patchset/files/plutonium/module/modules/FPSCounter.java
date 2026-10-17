package plutonium.module.modules;

import net.minecraft.client.Minecraft;
import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.ColorSetting;

public class FPSCounter extends TextHudModule {

    private final ColorSetting textColor = reg(new ColorSetting("Color", "Text color", 0xFFE6EAF2));

    public FPSCounter() {
        super("FPS Counter", Category.VISUAL);
    }

    @Override
    protected String[] getHudLines() {
        return new String[]{Minecraft.getDebugFPS() + " FPS"};
    }

    @Override protected int getLineColor(int index) { return textColor.getValue(); }
    @Override protected int defaultHudX(int sw, int sh) { return sw - getHudWidthScaled() - 2; }
    @Override protected int defaultHudY(int sw, int sh) { return 2; }
}
