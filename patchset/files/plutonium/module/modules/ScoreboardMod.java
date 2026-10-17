package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;

public class ScoreboardMod extends Module {

    public static ScoreboardMod instance;

    private final BoolSetting showNumbers = reg(new BoolSetting("Show Numbers", "Show red score numbers", false));
    private final BoolSetting showBackground = reg(new BoolSetting("Background", "Show scoreboard background", true));

    public ScoreboardMod() {
        super("Scoreboard", Category.VISUAL);
        instance = this;
        initEnabled(true);
    }

    public static boolean shouldHideScoreboard() {
        return instance != null && !instance.isEnabled();
    }

    public static boolean shouldShowNumbers() {
        return instance != null && instance.isEnabled() && instance.showNumbers.getValue();
    }

    public static boolean shouldShowBackground() {
        return instance == null || !instance.isEnabled() || instance.showBackground.getValue();
    }
}
