package plutonium;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.clickgui.DrawUtils;

/**
 * Central visual theme. A small set of editable knobs — accent, surface, base, text, border,
 * corner radius, surface opacity and shadow — from which {@link DrawUtils} derives its full
 * palette. The default is the "Steel / cool grey" look. Knobs persist via {@link Config};
 * changing any one re-derives the DrawUtils palette live and saves.
 */
public final class Theme {
    private Theme() {}

    // ── editable knobs (Steel / cool grey defaults) ──
    private static int     accent         = 0xFF5B7CA8; // steel-blue
    private static int     surface        = 0xFF161B26; // panel base (RGB; alpha from surfaceAlpha)
    private static int     base           = 0xFF0E1118; // deepest background / chip base
    private static int     textPrimary    = 0xFFE6EAF2;
    private static int     textDim         = 0xFF8A93A6;
    private static int     border         = 0xFF566072; // hairline cool grey (RGB; alpha derived)
    private static int     radius         = 4;          // corner radius (px)
    private static int     surfaceAlpha   = 0xD4;       // glass opacity 0..255
    private static boolean shadow         = true;
    private static int     shadowStrength = 0x66;       // shadow alpha 0..255

    // ── getters ──
    public static int     accent()         { return accent; }
    public static int     surface()        { return surface; }
    public static int     base()           { return base; }
    public static int     textPrimary()    { return textPrimary; }
    public static int     textDim()        { return textDim; }
    public static int     border()         { return border; }
    public static int     radius()         { return radius; }
    public static int     surfaceAlpha()   { return surfaceAlpha; }
    public static boolean shadow()         { return shadow; }
    public static int     shadowStrength() { return shadowStrength; }

    // ── setters (re-derive palette + persist) ──
    public static void setAccent(int v)        { accent = 0xFF000000 | (v & 0xFFFFFF); onChange(); }
    public static void setSurface(int v)       { surface = 0xFF000000 | (v & 0xFFFFFF); onChange(); }
    public static void setBase(int v)          { base = 0xFF000000 | (v & 0xFFFFFF); onChange(); }
    public static void setTextPrimary(int v)   { textPrimary = 0xFF000000 | (v & 0xFFFFFF); onChange(); }
    public static void setTextDim(int v)       { textDim = 0xFF000000 | (v & 0xFFFFFF); onChange(); }
    public static void setBorder(int v)        { border = 0xFF000000 | (v & 0xFFFFFF); onChange(); }
    public static void setRadius(int v)        { radius = Math.max(0, Math.min(12, v)); onChange(); }
    public static void setSurfaceAlpha(int v)  { surfaceAlpha = clampByte(v); onChange(); }
    public static void setShadow(boolean v)    { shadow = v; onChange(); }
    public static void setShadowStrength(int v){ shadowStrength = clampByte(v); onChange(); }

    private static void onChange() { apply(); Config.save(); }

    /** Re-derive the DrawUtils palette from the current knobs. Safe to call any time. */
    public static void apply() {
        DrawUtils.applyTheme(accent, surface, base, textPrimary, textDim, border,
                radius, surfaceAlpha, shadow ? shadowStrength : 0);
    }

    // ── persistence (called by Config) ──
    public static void save(JsonObject root) {
        JsonObject t = new JsonObject();
        t.addProperty("accent", accent);
        t.addProperty("surface", surface);
        t.addProperty("base", base);
        t.addProperty("textPrimary", textPrimary);
        t.addProperty("textDim", textDim);
        t.addProperty("border", border);
        t.addProperty("radius", radius);
        t.addProperty("surfaceAlpha", surfaceAlpha);
        t.addProperty("shadow", shadow);
        t.addProperty("shadowStrength", shadowStrength);
        t.addProperty("active", activeProfile);

        // Persist only user-saved (non-built-in) profiles; presets are recreated at startup.
        com.google.gson.JsonObject profs = new com.google.gson.JsonObject();
        for (java.util.Map.Entry<String, int[]> e : profiles.entrySet()) {
            if (builtIns.contains(e.getKey())) continue;
            com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
            for (int v : e.getValue()) arr.add(new com.google.gson.JsonPrimitive(v));
            profs.add(e.getKey(), arr);
        }
        t.add("profiles", profs);
        root.add("theme", t);
    }

    public static void load(JsonObject root) {
        JsonObject t = root.getAsJsonObject("theme");
        if (t != null) {
            if (t.has("accent"))         accent = t.get("accent").getAsInt();
            if (t.has("surface"))        surface = t.get("surface").getAsInt();
            if (t.has("base"))           base = t.get("base").getAsInt();
            if (t.has("textPrimary"))    textPrimary = t.get("textPrimary").getAsInt();
            if (t.has("textDim"))        textDim = t.get("textDim").getAsInt();
            if (t.has("border"))         border = t.get("border").getAsInt();
            if (t.has("radius"))         radius = t.get("radius").getAsInt();
            if (t.has("surfaceAlpha"))   surfaceAlpha = t.get("surfaceAlpha").getAsInt();
            if (t.has("shadow"))         shadow = t.get("shadow").getAsBoolean();
            if (t.has("shadowStrength")) shadowStrength = t.get("shadowStrength").getAsInt();
            if (t.has("active"))         activeProfile = t.get("active").getAsString();
            if (t.has("profiles")) {
                com.google.gson.JsonObject profs = t.getAsJsonObject("profiles");
                for (java.util.Map.Entry<String, com.google.gson.JsonElement> e : profs.entrySet()) {
                    com.google.gson.JsonArray arr = e.getValue().getAsJsonArray();
                    if (arr.size() < 10 || builtIns.contains(e.getKey())) continue;
                    int[] s = new int[10];
                    for (int i = 0; i < 10; i++) s[i] = arr.get(i).getAsInt();
                    profiles.put(e.getKey(), s);
                }
            }
        }
        apply();
    }

    // ── colour helpers (also used by the theme editor / DrawUtils) ──
    private static int clampByte(int v) { return Math.max(0, Math.min(255, v)); }

    /** Blend RGB toward white by f (0..1), preserving alpha. */
    public static int lighten(int argb, float f) {
        int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        r = (int) (r + (255 - r) * f);
        g = (int) (g + (255 - g) * f);
        b = (int) (b + (255 - b) * f);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Scale RGB toward black by f (0..1), preserving alpha. */
    public static int darken(int argb, float f) {
        int a = argb >>> 24, r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255;
        r = (int) (r * (1 - f));
        g = (int) (g * (1 - f));
        b = (int) (b * (1 - f));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Replace the alpha byte (0..255) of an ARGB colour. */
    public static int withAlpha(int argb, int alpha) {
        return (clampByte(alpha) << 24) | (argb & 0xFFFFFF);
    }

    // ── named profiles (built-in presets + user-saved) ───────────────────
    // Snapshot order: accent, surface, base, textPrimary, textDim, border,
    //                 radius, surfaceAlpha, shadow(0/1), shadowStrength
    private static final java.util.LinkedHashMap<String, int[]> profiles = new java.util.LinkedHashMap<>();
    private static final java.util.Set<String> builtIns = new java.util.LinkedHashSet<>();
    private static String activeProfile = "Steel";

    static {
        preset("Steel",   0xFF5B7CA8, 0xFF161B26, 0xFF0E1118, 0xFFE6EAF2, 0xFF8A93A6, 0xFF566072);
        preset("Indigo",  0xFF6B63C9, 0xFF14151F, 0xFF0C0D14, 0xFFECEAF6, 0xFF8E8BB8, 0xFF3A3560);
        preset("Aqua",    0xFF3FB6B0, 0xFF121A1F, 0xFF0B1014, 0xFFE4F0F0, 0xFF7FA0A0, 0xFF2A4A48);
        preset("Crimson", 0xFFD0506A, 0xFF1A1416, 0xFF140C0E, 0xFFF2E6E8, 0xFFA68A8E, 0xFF6A3540);
        preset("Mono",    0xFFF2F2F5, 0xFF18181B, 0xFF0C0C0E, 0xFFFAFAFA, 0xFF8A8A90, 0xFF45454B);
    }

    private static void preset(String name, int ac, int su, int ba, int tp, int td, int bo) {
        profiles.put(name, new int[]{ac, su, ba, tp, td, bo, 4, 0xD8, 1, 0x66});
        builtIns.add(name);
    }

    private static int[] snapshot() {
        return new int[]{accent, surface, base, textPrimary, textDim, border,
                radius, surfaceAlpha, shadow ? 1 : 0, shadowStrength};
    }

    private static void applySnapshot(int[] s) {
        accent = s[0]; surface = s[1]; base = s[2]; textPrimary = s[3]; textDim = s[4];
        border = s[5]; radius = s[6]; surfaceAlpha = s[7]; shadow = s[8] != 0; shadowStrength = s[9];
    }

    public static java.util.List<String> profileNames() { return new java.util.ArrayList<>(profiles.keySet()); }
    public static String  activeProfile()      { return activeProfile; }
    public static boolean isBuiltIn(String n)   { return builtIns.contains(n); }

    /** Apply a saved/preset profile's values as the active theme. */
    public static void applyProfile(String name) {
        int[] s = profiles.get(name);
        if (s == null) return;
        applySnapshot(s);
        activeProfile = name;
        onChange();
    }

    /** Snapshot the current knobs under {@code name} (overwrites) and make it active. */
    public static void saveProfile(String name) {
        if (name == null || name.trim().isEmpty()) return;
        name = name.trim();
        if (builtIns.contains(name)) return; // don't clobber presets
        profiles.put(name, snapshot());
        activeProfile = name;
        Config.save();
    }

    public static void deleteProfile(String name) {
        if (builtIns.contains(name)) return;
        profiles.remove(name);
        if (name.equals(activeProfile)) activeProfile = "Steel";
        Config.save();
    }
}
