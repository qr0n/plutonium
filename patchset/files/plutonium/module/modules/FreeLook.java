package plutonium.module.modules;

import plutonium.gpu.KeyboardShim;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.KeySetting;

public class FreeLook extends Module {

    public static FreeLook instance;

    private float yawOffset   = 0f;
    private float pitchOffset = 0f;

    // Camera pull-back animation: lerps 0→1 (key held) and 1→0 (released).
    // prevCamScale is the value at the start of the previous tick, used to
    // interpolate a smooth value at render time via partialTicks.
    private float camScale     = 0f;
    private float prevCamScale = 0f;

    private final KeySetting holdKey = reg(new KeySetting("Hold Key", "Hold to free-look", KeyboardShim.KEY_LMENU));

    public FreeLook() {
        super("Freelook", Category.MOVEMENT);
        instance = this;
    }

    private boolean isKeyActive() {
        if (mc.currentScreen != null) return false;
        int key = holdKey.getValue();
        return key != 0 && KeyboardShim.isKeyDown(key);
    }

    @Override
    public void onTick() {
        prevCamScale = camScale;

        if (isKeyActive()) {
            // Smoothly pull the camera back while the key is held.
            camScale += (1f - camScale) * 0.3f;
            if (camScale > 0.999f) camScale = 1f;
        } else {
            // On release, snap straight back to first person — no reverse
            // animation. Lerping 1→0 here left the camera zoomed out in
            // third person for a few ticks while the view had already
            // swung forward, which read as a weird perspective glitch.
            camScale     = 0f;
            prevCamScale = 0f;
            yawOffset    = 0f;
            pitchOffset  = 0f;
        }
    }

    @Override
    public void onDisable() {
        yawOffset      = 0f;
        pitchOffset    = 0f;
        camScale       = 0f;
        prevCamScale   = 0f;
    }

    // Called from EntityRenderer mouse handler — consumes mouse delta while key held.
    public static boolean onMouseInput(float deltaYaw, float deltaPitch) {
        if (instance == null || !instance.isEnabled()) return false;
        if (!instance.isKeyActive()) return false;

        instance.yawOffset   += deltaYaw;
        instance.pitchOffset += deltaPitch;
        instance.pitchOffset  = Math.max(-90f, Math.min(90f, instance.pitchOffset));
        return true;
    }

    // Render-time: interpolated camera pull scale in [0, 1].
    public static float getCameraScale(float partialTicks) {
        if (instance == null || !instance.isEnabled()) return 0f;
        return instance.prevCamScale + (instance.camScale - instance.prevCamScale) * partialTicks;
    }

    // True whenever the camera is at least partially pulled back.
    public static boolean isFreeLookCamera() {
        return instance != null && instance.isEnabled() && instance.camScale > 0f;
    }

    public static float getYawOffset() {
        if (instance == null || !instance.isEnabled()) return 0f;
        if (!instance.isKeyActive()) return 0f;
        return instance.yawOffset;
    }

    public static float getPitchOffset() {
        if (instance == null || !instance.isEnabled()) return 0f;
        if (!instance.isKeyActive()) return 0f;
        return instance.pitchOffset;
    }
}
