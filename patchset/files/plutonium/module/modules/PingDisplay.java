package plutonium.module.modules;

import net.minecraft.client.network.NetworkPlayerInfo;
import plutonium.module.Category;
import plutonium.module.TextHudModule;

public class PingDisplay extends TextHudModule {

    private int lastPing = 0;

    public PingDisplay() {
        super("Ping Display", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    protected String[] getHudLines() {
        if (mc.thePlayer == null || mc.getNetHandler() == null) return null;
        NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        if (info == null) return null;
        lastPing = info.getResponseTime();
        return new String[]{lastPing + " ms"};
    }

    @Override
    protected int getLineColor(int index) {
        if (lastPing < 100) return 0xFF55FF55;
        if (lastPing < 200) return 0xFFFFFF55;
        return 0xFFFF5555;
    }

    @Override protected int defaultHudX(int sw, int sh) { return sw - getHudWidthScaled() - 2; }
    @Override protected int defaultHudY(int sw, int sh) { return 22; }
}
