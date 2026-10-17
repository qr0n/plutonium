package plutonium.setting;

/**
 * A free-text setting edited in-place in the ClickGUI (see GuiClickGUI's StringSetting
 * rendering / click-to-edit / keyTyped handling). The value is never null — an empty
 * string is used instead, because Config round-trips the value through Gson's
 * getAsString(), which would throw on a JSON null.
 */
public class StringSetting extends Setting<String> {

    /** Supplies live completion candidates for a field (e.g. the names on the server player list). */
    public interface SuggestionSource {
        java.util.List<String> suggest();
    }

    private final int maxLength;
    private final boolean multiline;
    private SuggestionSource suggestionSource;

    public StringSetting(String name, String description, String defaultValue, int maxLength) {
        this(name, description, defaultValue, maxLength, false);
    }

    public StringSetting(String name, String description, String defaultValue, int maxLength, boolean multiline) {
        super(name, description, defaultValue == null ? "" : defaultValue);
        this.maxLength = maxLength;
        this.multiline = multiline;
    }

    public int getMaxLength() { return maxLength; }

    /** Attach a completion source; the ClickGUI then drops a filtered list under the focused field. */
    public StringSetting withSuggestions(SuggestionSource source) {
        this.suggestionSource = source;
        return this;
    }

    public SuggestionSource getSuggestionSource() { return suggestionSource; }

    /** Multiline fields open the pop-out {@code GuiTextEditor} instead of inline editing. */
    public boolean isMultiline() { return multiline; }

    @Override public void setValue(String v) { super.setValue(v == null ? "" : v); }

    @Override public String toJsonValue()          { return getValue(); }
    @Override public void   fromJsonValue(String s) { setValue(s == null ? "" : s); }
}
