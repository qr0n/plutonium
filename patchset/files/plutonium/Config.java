package plutonium;

import com.google.gson.*;
import plutonium.module.Module;
import plutonium.module.ModuleManager;
import plutonium.setting.Setting;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Saves and loads all module state + settings to/from a JSON file.
 * Written immediately on any change so nothing is lost on crash.
 */
public class Config {

    private static File configFile;
    private static boolean loading = false;

    public static void init(File gameDir) {
        configFile = new File(gameDir, "plutonium_config.json");
    }

    // -------- save --------

    public static void save() {
        if (loading) return; // don't write while reading
        ModuleManager mm = Plutonium.getInstance().getModuleManager();
        if (mm == null) return;

        JsonObject root = new JsonObject();
        JsonObject modules = new JsonObject();

        for (Module m : mm.getModules()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("enabled", m.isEnabled());
            mo.addProperty("key",     m.getKeyCode());
            if (m.hudX != -1) mo.addProperty("hud_x", m.hudX);
            if (m.hudY != -1) mo.addProperty("hud_y", m.hudY);
            if (m.hudAnchor != -1) {
                mo.addProperty("hud_anchor", m.hudAnchor);
                mo.addProperty("hud_off_x", m.hudOffX);
                mo.addProperty("hud_off_y", m.hudOffY);
            }
            if (m.hudScale != 1.0f) mo.addProperty("hud_scale", m.hudScale);

            JsonObject settings = new JsonObject();
            for (Setting<?> s : m.getSettings()) {
                settings.addProperty(s.getName(), s.toJsonValue());
            }
            mo.add("settings", settings);
            modules.add(m.getName(), mo);
        }

        root.add("modules", modules);

        ClickGUIConfig guiCfg = Plutonium.getInstance().getGuiConfig();
        if (guiCfg != null) {
            JsonObject gui = new JsonObject();
            gui.addProperty("openKey", guiCfg.getOpenKey());
            gui.addProperty("animStyle", guiCfg.getAnimStyle());
            root.add("gui", gui);
        }

        Theme.save(root);

        try (Writer w = new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, w);
        } catch (IOException e) {
            System.err.println("[Plutonium] Failed to save config: " + e.getMessage());
        }
    }

    // -------- load --------

    public static void load() {
        if (configFile == null || !configFile.exists()) return;
        ModuleManager mm = Plutonium.getInstance().getModuleManager();
        if (mm == null) return;

        JsonObject root;
        try (Reader r = new InputStreamReader(new FileInputStream(configFile), StandardCharsets.UTF_8)) {
            root = new JsonParser().parse(r).getAsJsonObject();
        } catch (Exception e) {
            return;
        }

        loading = true;
        try {
            JsonObject modules = root.getAsJsonObject("modules");
            if (modules == null) return;

            for (Module m : mm.getModules()) {
                JsonObject mo = modules.getAsJsonObject(m.getName());
                if (mo == null) continue;

                if (mo.has("enabled")) m.setEnabled(mo.get("enabled").getAsBoolean());
                if (mo.has("key"))     m.setKeyCode(mo.get("key").getAsInt());
                if (mo.has("hud_x"))   m.hudX = mo.get("hud_x").getAsInt();
                if (mo.has("hud_y"))   m.hudY = mo.get("hud_y").getAsInt();
                if (mo.has("hud_anchor")) {
                    m.hudAnchor = mo.get("hud_anchor").getAsInt();
                    if (mo.has("hud_off_x")) m.hudOffX = mo.get("hud_off_x").getAsInt();
                    if (mo.has("hud_off_y")) m.hudOffY = mo.get("hud_off_y").getAsInt();
                }
                if (mo.has("hud_scale")) m.hudScale = mo.get("hud_scale").getAsFloat();

                JsonObject settings = mo.getAsJsonObject("settings");
                if (settings != null) {
                    for (Setting<?> s : m.getSettings()) {
                        if (settings.has(s.getName()))
                            s.fromJsonValue(settings.get(s.getName()).getAsString());
                    }
                }
            }
            JsonObject gui = root.getAsJsonObject("gui");
            if (gui != null) {
                ClickGUIConfig guiCfg = Plutonium.getInstance().getGuiConfig();
                if (guiCfg != null) {
                    if (gui.has("openKey")) guiCfg.setOpenKey(gui.get("openKey").getAsInt());
                    if (gui.has("animStyle")) guiCfg.setAnimStyle(gui.get("animStyle").getAsInt());
                }
            }
            Theme.load(root);
        } finally {
            loading = false;
        }
    }
}
