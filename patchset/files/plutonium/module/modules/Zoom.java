package plutonium.module.modules;

import net.minecraft.client.Minecraft;
import plutonium.gpu.KeyboardShim;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class Zoom extends Module {

    public static Zoom instance;

    private static float smoothFOV = 1.0f;
    private static float targetFOV = 1.0f;
    private static float zoomDivisor = 4.0f;

    private final SliderSetting defaultZoom = reg(new SliderSetting("Default Zoom", "Starting zoom level", 4.0f, 1.5f, 10.0f, 0.5f));
    private final SliderSetting smoothSpeed = reg(new SliderSetting("Smooth Speed", "Zoom transition speed", 0.15f, 0.05f, 1.0f, 0.05f));

    public Zoom() {
        super("Zoom", Category.VISUAL, 46);
        instance = this;
        initEnabled(true);
    }

    public static float processFOV(float fov) {
        Minecraft mc = Minecraft.getMinecraft();
        if (instance == null || !instance.isEnabled() || mc.thePlayer == null) {
            smoothFOV = lerp(smoothFOV, 1.0f, 0.25f);
            return fov * smoothFOV;
        }

        boolean zooming = mc.currentScreen == null && KeyboardShim.isKeyDown(instance.getKeyCode());
        if (zooming && zoomDivisor != instance.defaultZoom.getValue() && targetFOV == 1.0f) {
            zoomDivisor = instance.defaultZoom.getValue();
        }
        targetFOV = zooming ? (1.0f / zoomDivisor) : 1.0f;

        float speed = zooming ? instance.smoothSpeed.getValue() : 0.25f;
        smoothFOV = lerp(smoothFOV, targetFOV, speed);
        return fov * smoothFOV;
    }

    public static boolean handleScroll(int delta) {
        Minecraft mc = Minecraft.getMinecraft();
        if (instance == null || !instance.isEnabled() || mc.thePlayer == null) return false;
        if (mc.currentScreen != null) return false;
        if (!KeyboardShim.isKeyDown(instance.getKeyCode())) return false;

        if (delta > 0) zoomDivisor = Math.min(zoomDivisor + 0.5f, 10.0f);
        else           zoomDivisor = Math.max(zoomDivisor - 0.5f, 1.5f);

        targetFOV = 1.0f / zoomDivisor;
        return true;
    }

    @Override
    public void onDisable() {
        smoothFOV = 1.0f;
        targetFOV = 1.0f;
        zoomDivisor = defaultZoom.getValue();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
