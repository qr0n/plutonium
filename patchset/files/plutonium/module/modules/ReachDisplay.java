package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.TextHudModule;

public class ReachDisplay extends TextHudModule {

    public ReachDisplay() {
        super("Reach Display", Category.MISC);
        initEnabled(true);
    }

    @Override
    protected String[] getHudLines() {
        if (mc.thePlayer == null || mc.playerController == null) return null;
        float reach = mc.playerController.isNotCreative() ? 3.0f : 5.0f;
        return new String[]{String.format("%.1f blocks", reach)};
    }

    @Override protected int defaultHudX(int sw, int sh) { return sw / 2 - getHudWidthScaled() / 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh / 2 + 8; }
}
