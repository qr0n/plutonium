package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.ColorSetting;

import java.util.ArrayDeque;
import java.util.Deque;

public class CpsCounter extends TextHudModule {

    public static CpsCounter instance;

    private final Deque<Long> leftClicks = new ArrayDeque<Long>();
    private final Deque<Long> rightClicks = new ArrayDeque<Long>();

    private final ColorSetting textColor = reg(new ColorSetting("Color", "CPS text color", 0xFFE6EAF2));

    public CpsCounter() {
        super("CPS Counter", Category.VISUAL);
        instance = this;
        initEnabled(true);
    }

    public static void onLeftClick() {
        if (instance != null && instance.isEnabled())
            instance.leftClicks.addLast(System.currentTimeMillis());
    }

    public static void onRightClick() {
        if (instance != null && instance.isEnabled())
            instance.rightClicks.addLast(System.currentTimeMillis());
    }

    @Override
    protected String[] getHudLines() {
        long now = System.currentTimeMillis();
        long window = 1000L;
        while (!leftClicks.isEmpty() && now - leftClicks.peekFirst() > window) leftClicks.pollFirst();
        while (!rightClicks.isEmpty() && now - rightClicks.peekFirst() > window) rightClicks.pollFirst();
        return new String[]{leftClicks.size() + " L / " + rightClicks.size() + " R"};
    }

    @Override protected int getLineColor(int index) { return textColor.getValue(); }
    @Override protected int defaultHudX(int sw, int sh) { return sw / 2 - getHudWidthScaled() / 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh - 45; }
}
