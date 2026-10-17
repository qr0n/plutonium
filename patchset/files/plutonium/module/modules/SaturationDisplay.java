package plutonium.module.modules;

import net.minecraft.client.gui.Gui;
import net.minecraft.util.FoodStats;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.ColorSetting;

public class SaturationDisplay extends Module {

    private final ColorSetting barColor = reg(new ColorSetting("Color", "Saturation bar fill color", 0xFFFFAA00));

    public SaturationDisplay() {
        super("Saturation", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    public void onRenderHUD(int sw, int sh) {
        if (mc.thePlayer == null) return;

        FoodStats food = mc.thePlayer.getFoodStats();
        float saturation = food.getSaturationLevel();

        int barW = 81;
        int barH = 3;
        int x = sw / 2 + 10;
        int y = sh - 22 - barH - 1;

        float frac = Math.min(saturation / 20f, 1f);
        int fillW = (int) (frac * barW);

        Gui.drawRect(x, y, x + barW, y + barH, 0x88000000);
        Gui.drawRect(x, y, x + fillW, y + barH, barColor.getValue());
    }
}
