package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;
import plutonium.setting.StringSetting;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ChatFilter extends Module {

    public static ChatFilter instance;

    /** Leading marker of the placeholder line; the reveal ID follows it. */
    private static final String MARKER = "[FILTERED #";

    private static final List<String>        filters          = new CopyOnWriteArrayList<String>();
    private static final Map<Integer,String> filteredMessages = new LinkedHashMap<Integer,String>();
    private static int   nextId     = 0;
    private static File  filterFile = null;

    /** Set while pushing the list into {@link #filterList}, so the write-back is skipped. */
    private static boolean syncing = false;

    private final BoolSetting showPlaceholder = reg(new BoolSetting("Show Placeholder",
        "Leave a [FILTERED] line you can right-click to reveal. Off: drop the message entirely.", true));

    private final StringSetting filterList = reg(new FilterListSetting());

    public ChatFilter() {
        super("Chat Filter", Category.MISC);
        instance = this;
        pushListToSetting();
    }

    /**
     * Live view of the filter list for the ClickGUI. {@code plutonium_filters.txt} stays
     * the source of truth, so this never round-trips through plutonium_config.json.
     */
    private static class FilterListSetting extends StringSetting {
        FilterListSetting() {
            super("Filters", "One phrase per line. Messages containing any of them are hidden.", "", 4000, true);
        }

        @Override public void setValue(String v) {
            super.setValue(v);
            if (!syncing) setFilters(v);
        }

        @Override public void fromJsonValue(String s) { /* the phrase file is authoritative */ }
    }

    // ── static API used by GuiNewChat / GuiChat ──────────────────────

    /** Returns true if the raw (unformatted) text should be hidden. */
    public static boolean isFiltered(String text) {
        if (instance == null || !instance.isEnabled()) return false;
        if (text == null) return false;
        String lower = text.toLowerCase();
        for (String f : filters) {
            if (lower.contains(f)) return true;
        }
        return false;
    }

    /** False when filtered messages should vanish outright instead of leaving a placeholder. */
    public static boolean showsPlaceholder() {
        return instance != null && instance.showPlaceholder.getValue();
    }

    /**
     * Stores the original formatted text and returns a unique ID used in
     * the placeholder that replaces it in chat.
     */
    public static int storeFiltered(String formattedText) {
        int id = ++nextId;
        filteredMessages.put(id, formattedText);
        // Keep a cap to avoid unbounded memory use
        if (filteredMessages.size() > 500) {
            Iterator<Integer> it = filteredMessages.keySet().iterator();
            if (it.hasNext()) { it.next(); it.remove(); }
        }
        return id;
    }

    /** The chat line shown in place of a filtered message. */
    public static String placeholderFor(int id) {
        return "§7[§eFILTERED §8#" + id + "§7] §8Right-click to reveal";
    }

    /**
     * Returns the original text for the given ID, or null once it has aged out of the
     * store. The entry is kept, so a line can be revealed more than once.
     */
    public static String revealFiltered(int id) {
        return filteredMessages.get(id);
    }

    public static boolean isFilteredPlaceholder(String strippedText) {
        return strippedText != null && strippedText.indexOf(MARKER) >= 0;
    }

    /** Parses the ID out of a stripped placeholder like "[FILTERED #42] Right-click…". */
    public static int extractFilterId(String strippedText) {
        try {
            int hash = strippedText.indexOf(MARKER);
            if (hash < 0) return -1;
            hash += MARKER.length() - 1; // land on the '#'
            int bracket = strippedText.indexOf(']', hash);
            if (bracket > hash) {
                return Integer.parseInt(strippedText.substring(hash + 1, bracket).trim());
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    // ── filter management ────────────────────────────────────────────

    public static boolean addFilter(String raw) {
        String s = raw.trim().toLowerCase();
        if (s.isEmpty() || filters.contains(s)) return false;
        filters.add(s);
        saveFilters();
        pushListToSetting();
        return true;
    }

    public static boolean removeFilter(int index) {
        if (index < 0 || index >= filters.size()) return false;
        filters.remove(index);
        saveFilters();
        pushListToSetting();
        return true;
    }

    public static void clearFilters() {
        filters.clear();
        saveFilters();
        pushListToSetting();
    }

    public static List<String> getFilters() {
        return Collections.unmodifiableList(filters);
    }

    /** Replaces the whole list from newline-separated text (the ClickGUI editor's output). */
    private static void setFilters(String text) {
        filters.clear();
        for (String line : text.split("\n")) {
            String s = line.trim().toLowerCase();
            if (!s.isEmpty() && !filters.contains(s)) filters.add(s);
        }
        saveFilters();
    }

    private static void pushListToSetting() {
        if (instance == null) return;
        StringBuilder sb = new StringBuilder();
        for (String f : filters) sb.append(f).append('\n');
        syncing = true;
        try { instance.filterList.setValue(sb.toString()); }
        finally { syncing = false; }
    }

    // ── persistence ──────────────────────────────────────────────────

    public static void initFile(File gameDir) {
        filterFile = new File(gameDir, "plutonium_filters.txt");
        loadFilters();
    }

    private static void loadFilters() {
        if (filterFile == null || !filterFile.exists()) return;
        filters.clear();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(filterFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) filters.add(line);
            }
        } catch (IOException ignored) {}
        pushListToSetting();
    }

    private static void saveFilters() {
        if (filterFile == null) return;
        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(filterFile), StandardCharsets.UTF_8)) {
            for (String f : filters) { w.write(f); w.write('\n'); }
        } catch (IOException ignored) {}
    }
}
