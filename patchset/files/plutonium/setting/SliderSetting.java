package plutonium.setting;

public class SliderSetting extends Setting<Float> {
    private final float min;
    private final float max;
    private final float increment;

    public SliderSetting(String name, String description, float defaultValue, float min, float max, float increment) {
        super(name, description, defaultValue);
        this.min       = min;
        this.max       = max;
        this.increment = increment;
    }

    public float getMin()       { return min; }
    public float getMax()       { return max; }
    public float getIncrement() { return increment; }

    @Override
    public void setValue(Float v) {
        super.setValue(Math.max(min, Math.min(max, v)));
    }

    @Override public String toJsonValue()         { return Float.toString(getValue()); }
    @Override public void   fromJsonValue(String s) {
        try { setValue(Float.parseFloat(s)); } catch (NumberFormatException ignored) {}
    }
}
