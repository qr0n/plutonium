package plutonium;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import plutonium.module.Module;
import plutonium.module.ModuleManager;

import java.io.*;
import java.nio.charset.StandardCharsets;

public class Plutonium {
    private static Plutonium instance;

    private ModuleManager moduleManager;
    private ClickGUIConfig guiConfig;

    private boolean debugMode = false;

    private Plutonium() {}

    public void init() {
        File gameDir = Minecraft.getMinecraft().mcDataDir;
        Config.init(gameDir);
        AccountManager.init(gameDir);
        MicrosoftAuth.init(gameDir);
        plutonium.module.modules.ChatFilter.initFile(gameDir);

        moduleManager = new ModuleManager();
        guiConfig     = new ClickGUIConfig();

        Config.load();
        Theme.apply(); // derive the DrawUtils palette (Config.load also applies if a file exists)

    }

    private boolean readDebugFlag(File gameDir) {
        File settingsFile = new File(gameDir, "launcher_settings.json");
        if (!settingsFile.exists()) return false;
        try (Reader r = new InputStreamReader(new FileInputStream(settingsFile), StandardCharsets.UTF_8)) {
            JsonObject obj = new JsonParser().parse(r).getAsJsonObject();
            return obj.has("debug") && obj.get("debug").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    public void onTick() {

        for (Module m : moduleManager.getModules()) {
            if (m.isEnabled()) m.onTick();
        }
    }

    /** Mouse click with no screen open. Coordinates are scaled GUI pixels. */
    public void onMouseClick(int button, int scaledX, int scaledY) {
        if (moduleManager == null) return;
        for (Module m : moduleManager.getModules()) {
            if (m.isEnabled()) m.onHudClick(button, scaledX, scaledY);
        }
    }

    /**
     * Toggle every module bound to {@code keyCode}. Key 0 means "unbound" and never matches.
     * A module whose own key setting uses the same key (e.g. Freelook's hold key) is left
     * alone, or every press would toggle it off before the key could do its job.
     */
    public void onKeyPress(int keyCode) {
        if (keyCode == 0) return;
        for (Module m : moduleManager.getModules()) {
            if (m.getKeyCode() == keyCode && !m.usesKey(keyCode)) m.toggle();
        }
    }

    public void onPreWalkingUpdate() {
        for (Module m : moduleManager.getModules()) {
            if (m.isEnabled()) m.onPreWalkingUpdate();
        }
    }

    public void onPreMovementUpdate() {
        for (Module m : moduleManager.getModules()) {
            if (m.isEnabled()) m.onPreMovementUpdate();
        }
    }

    public void onRenderHUD(int screenWidth, int screenHeight) {
        for (Module m : moduleManager.getModules()) {
            if (!m.isEnabled()) continue;
            float scale = m.hudScale;
            if (scale != 1.0f && m.getHudWidth() > 0) {
                // Scale around the element's resolved top-left so the on-screen box stays
                // at (rx, ry, w*scale, h*scale) — matches getHudWidthScaled hit-testing.
                // All GUI batchers bake the modelview at emit time, so no flush is needed.
                int rx = m.resolveHudX(screenWidth, screenHeight);
                int ry = m.resolveHudY(screenWidth, screenHeight);
                net.minecraft.client.renderer.GlStateManager.pushMatrix();
                net.minecraft.client.renderer.GlStateManager.translate(rx, ry, 0);
                net.minecraft.client.renderer.GlStateManager.scale(scale, scale, 1.0f);
                net.minecraft.client.renderer.GlStateManager.translate(-rx, -ry, 0);
                m.onRenderHUD(screenWidth, screenHeight);
                net.minecraft.client.renderer.GlStateManager.popMatrix();
            } else {
                m.onRenderHUD(screenWidth, screenHeight);
            }
        }
    }

    public void onRenderWorld(float partialTicks) {
        for (Module m : moduleManager.getModules()) {
            if (m.isEnabled()) m.onRenderWorld(partialTicks);
        }
    }

    public void shutdown() {
        plutonium.rpc.DiscordRP.shutdown();
    }

    public ModuleManager getModuleManager() { return moduleManager; }
    public ClickGUIConfig getGuiConfig()    { return guiConfig; }
    public boolean isDebugMode()            { return debugMode; }

    public static Plutonium getInstance() {
        if (instance == null) instance = new Plutonium();
        return instance;
    }
}
