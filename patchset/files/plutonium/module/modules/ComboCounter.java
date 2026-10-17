package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.ColorSetting;
import plutonium.setting.SliderSetting;

public class ComboCounter extends TextHudModule {

    public static ComboCounter instance;

    private int combo = 0;
    private long lastHitMs = 0;

    private int displayCombo = 0;
    private long displayUntil = 0;

    private final SliderSetting resetDelay = reg(new SliderSetting("Reset Delay", "Seconds before combo resets", 2.5f, 1.0f, 5.0f, 0.5f));
    private final ColorSetting textColor = reg(new ColorSetting("Color", "Combo text color", 0xFF5B7CA8));

    public ComboCounter() {
        super("Combo Counter", Category.MISC);
        instance = this;
        initEnabled(true);
    }

    public static void onEntityHit() {
        if (instance == null || !instance.isEnabled()) return;
        long now = System.currentTimeMillis();
        long resetMs = (long)(instance.resetDelay.getValue() * 1000f);
        if (now - instance.lastHitMs > resetMs) {
            instance.combo = 0;
        }
        instance.combo++;
        instance.lastHitMs = now;
        if (instance.combo > 1) {
            instance.displayCombo = instance.combo;
            instance.displayUntil = now + 2000;
        }
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        long resetMs = (long)(resetDelay.getValue() * 1000f);
        if (now - lastHitMs > resetMs && combo > 0) {
            combo = 0;
        }
    }

    @Override
    protected String[] getHudLines() {
        if (displayCombo < 2) return null;
        if (System.currentTimeMillis() > displayUntil) {
            displayCombo = 0;
            return null;
        }
        return new String[]{displayCombo + " Combo"};
    }

    @Override protected int getLineColor(int index) { return textColor.getValue(); }
    @Override protected boolean isCentered() { return true; }
    @Override protected int defaultHudX(int sw, int sh) { return sw / 2 - getHudWidthScaled() / 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh / 2 - 40; }
}
