package plutonium.module.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;
import plutonium.setting.ColorSetting;
import plutonium.setting.SliderSetting;

public class Crosshair extends Module {

    public static Crosshair instance;

    private final SliderSetting size = reg(new SliderSetting("Size", "Crosshair arm length", 6f, 2f, 16f, 1f));
    private final SliderSetting gap = reg(new SliderSetting("Gap", "Center gap size", 0f, 0f, 8f, 1f));
    private final SliderSetting thickness = reg(new SliderSetting("Thickness", "Line thickness", 1f, 1f, 4f, 1f));
    private final BoolSetting dot = reg(new BoolSetting("Center Dot", "Draw center dot", false));
    private final ColorSetting color = reg(new ColorSetting("Color", "Crosshair color", 0xFFFFFFFF));

    public Crosshair() {
        super("Crosshair", Category.VISUAL);
        instance = this;
        initEnabled(true);
    }

    public static boolean drawCustomCrosshair(int sw, int sh) {
        if (instance == null || !instance.isEnabled()) return false;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return false;

        int cx = sw / 2;
        int cy = sh / 2;
        int sz = (int) instance.size.getValue().floatValue();
        int g = (int) instance.gap.getValue().floatValue();
        int t = (int) instance.thickness.getValue().floatValue();
        int c = instance.color.getValue();
        int halfT = t / 2;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();

        // Top arm
        Gui.drawRect(cx - halfT, cy - g - sz, cx - halfT + t, cy - g, c);
        // Bottom arm
        Gui.drawRect(cx - halfT, cy + g, cx - halfT + t, cy + g + sz, c);
        // Left arm
        Gui.drawRect(cx - g - sz, cy - halfT, cx - g, cy - halfT + t, c);
        // Right arm
        Gui.drawRect(cx + g, cy - halfT, cx + g + sz, cy - halfT + t, c);

        if (instance.dot.getValue()) {
            Gui.drawRect(cx - halfT, cy - halfT, cx - halfT + t, cy - halfT + t, c);
        }

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        return true;
    }
}
