package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.rpc.DiscordRP;

public class DiscordRPC extends Module {
    private int tickCounter = 0;

    public DiscordRPC() {
        super("Discord RPC", Category.MISC);
        initEnabled(true);
    }

    @Override
    public void onEnable() {
        DiscordRP.start();
    }

    @Override
    public void onDisable() {
        DiscordRP.shutdown();
    }

    @Override
    public void onTick() {
        tickCounter++;
        if (tickCounter >= 100) {
            tickCounter = 0;
            DiscordRP.update();
        }
    }
}
