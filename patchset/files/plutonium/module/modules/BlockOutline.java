package plutonium.module.modules;

import net.minecraft.client.renderer.GlStateManager;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.ColorSetting;
import plutonium.setting.SliderSetting;

public class BlockOutline extends Module {

    public static BlockOutline instance;

    private final ColorSetting outlineColor = reg(new ColorSetting("Color", "Outline color", 0x66FFFFFF));
    private final SliderSetting lineWidth = reg(new SliderSetting("Line Width", "Outline thickness", 2.0f, 0.5f, 6.0f, 0.5f));

    public BlockOutline() {
        super("Block Outline", Category.VISUAL);
        instance = this;
        initEnabled(true);
    }

    public static void applyColor() {
        if (instance == null || !instance.isEnabled()) return;
        int c = instance.outlineColor.getValue();
        float r = ((c >> 16) & 0xFF) / 255f;
        float g = ((c >> 8) & 0xFF) / 255f;
        float b = (c & 0xFF) / 255f;
        float a = ((c >> 24) & 0xFF) / 255f;
        GlStateManager.color(r, g, b, a);
        // Line Width has no effect under Vulkan: Metal rasterizes lines at 1px and MoltenVK
        // clamps lineWidth to 1, so the setting stays for config compatibility only.
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }
}
