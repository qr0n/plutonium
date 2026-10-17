package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.TextHudModule;

public class MemoryUsage extends TextHudModule {

    private int lastPct = 0;

    public MemoryUsage() {
        super("Memory Usage", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    protected String[] getHudLines() {
        Runtime rt = Runtime.getRuntime();
        long total = rt.totalMemory();
        long free = rt.freeMemory();
        long used = (total - free) / (1024 * 1024);
        long max = rt.maxMemory() / (1024 * 1024);
        lastPct = (int) (used * 100 / max);
        return new String[]{used + "MB / " + max + "MB (" + lastPct + "%)"};
    }

    @Override
    protected int getLineColor(int index) {
        if (lastPct < 60) return 0xFF55FF55;
        if (lastPct < 85) return 0xFFFFFF55;
        return 0xFFFF5555;
    }
}
