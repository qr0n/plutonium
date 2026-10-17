package plutonium.setting;

import plutonium.gpu.KeyboardShim;

public class KeySetting extends Setting<Integer> {
    public KeySetting(String name, String description, int defaultKey) {
        super(name, description, defaultKey);
    }

    public String getKeyName() {
        int k = getValue();
        if (k == 0) return "None";
        return KeyboardShim.getKeyName(k);
    }

    @Override public String toJsonValue()         { return Integer.toString(getValue()); }
    @Override public void   fromJsonValue(String s) {
        try { setValue(Integer.parseInt(s)); } catch (NumberFormatException ignored) {}
    }
}
