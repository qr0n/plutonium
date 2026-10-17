package plutonium.module.modules;

import net.minecraft.client.settings.KeyBinding;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;

public class Sprint extends Module {

    public static Sprint instance;

    private final BoolSetting keepOnWalls = reg(new BoolSetting("Keep On Walls",
            "Don't stop sprinting while rubbing against a wall", true));

    public Sprint() {
        super("Auto Sprint", Category.MOVEMENT);
        instance = this;
    }

    // Called from EntityPlayerSP's sprint-cancel check.
    public static boolean keepSprintOnWalls() {
        return instance != null && instance.isEnabled() && instance.keepOnWalls.getValue();
    }

    @Override
    public void onTick() {
        if (mc.thePlayer == null || mc.currentScreen != null) return;
        if (mc.thePlayer.moveForward <= 0.0f) return;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), true);
    }

    @Override
    public void onDisable() {
        if (mc.thePlayer != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
        }
    }
}
