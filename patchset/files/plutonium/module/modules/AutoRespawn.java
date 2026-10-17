package plutonium.module.modules;

import net.minecraft.client.gui.GuiGameOver;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class AutoRespawn extends Module {

    private final SliderSetting delay = reg(new SliderSetting("Delay", "Ticks before respawning", 5f, 0f, 40f, 1f));
    private int tickCounter = 0;

    public AutoRespawn() {
        super("Auto Respawn", Category.PLAYER);
    }

    @Override
    public void onTick() {
        if (mc.thePlayer == null) return;
        if (!(mc.currentScreen instanceof GuiGameOver)) {
            tickCounter = 0;
            return;
        }
        tickCounter++;
        if (tickCounter >= (int) delay.getValue().floatValue()) {
            mc.thePlayer.respawnPlayer();
            mc.displayGuiScreen(null);
            tickCounter = 0;
        }
    }
}
