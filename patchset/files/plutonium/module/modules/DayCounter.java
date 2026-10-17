package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.TextHudModule;

public class DayCounter extends TextHudModule {

    public DayCounter() {
        super("Day Counter", Category.MISC);
        initEnabled(true);
    }

    @Override
    protected String[] getHudLines() {
        if (mc.theWorld == null) return null;
        return new String[]{"Day " + mc.theWorld.getWorldTime() / 24000L};
    }

    @Override protected int defaultHudY(int sw, int sh) { return sh - 42; }
}
