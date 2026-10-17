package plutonium.setting;

public class BoolSetting extends Setting<Boolean> {
    public BoolSetting(String name, String description, boolean defaultValue) {
        super(name, description, defaultValue);
    }

    public void toggle() { setValue(!getValue()); }

    @Override public String toJsonValue()         { return Boolean.toString(getValue()); }
    @Override public void   fromJsonValue(String s) { setValue(Boolean.parseBoolean(s)); }
}
