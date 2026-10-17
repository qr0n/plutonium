package plutonium.setting;

public abstract class Setting<T> {
    private final String name;
    private final String description;
    private T value;
    private final T defaultValue;

    protected Setting(String name, String description, T defaultValue) {
        this.name         = name;
        this.description  = description;
        this.defaultValue = defaultValue;
        this.value        = defaultValue;
    }

    public String getName()        { return name; }
    public String getDescription() { return description; }
    public T      getValue()       { return value; }
    public T      getDefault()     { return defaultValue; }

    public void setValue(T v) {
        this.value = v;
        plutonium.Config.save();
    }

    public void reset() { setValue(defaultValue); }

    public abstract String toJsonValue();
    public abstract void   fromJsonValue(String json);
}
