package plutonium.setting;

public class ColorSetting extends Setting<Integer> {
    public ColorSetting(String name, String description, int defaultArgb) {
        super(name, description, defaultArgb);
    }

    public int getR() { return (getValue() >> 16) & 0xFF; }
    public int getG() { return (getValue() >>  8) & 0xFF; }
    public int getB() { return  getValue()         & 0xFF; }
    public int getA() { return (getValue() >> 24) & 0xFF; }

    @Override public String toJsonValue()         { return Integer.toHexString(getValue()); }
    @Override public void   fromJsonValue(String s) {
        try { setValue((int) Long.parseLong(s, 16)); } catch (NumberFormatException ignored) {}
    }
}
