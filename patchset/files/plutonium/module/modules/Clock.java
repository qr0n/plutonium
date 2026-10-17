package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.BoolSetting;
import plutonium.setting.ColorSetting;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Clock extends TextHudModule {

    private final SimpleDateFormat fmt12 = new SimpleDateFormat("h:mm a");
    private final SimpleDateFormat fmt24 = new SimpleDateFormat("HH:mm");

    private final BoolSetting use24h = reg(new BoolSetting("24h Format", "Use 24-hour time format", false));
    private final ColorSetting textColor = reg(new ColorSetting("Color", "Clock text color", 0xFFE6EAF2));

    public Clock() {
        super("Clock", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    protected String[] getHudLines() {
        return new String[]{use24h.getValue() ? fmt24.format(new Date()) : fmt12.format(new Date())};
    }

    @Override protected int getLineColor(int index) { return textColor.getValue(); }
    @Override protected int defaultHudX(int sw, int sh) { return sw - getHudWidthScaled() - 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh - getHudHeightScaled() - 2; }
}
