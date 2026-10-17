package plutonium.module;

import net.minecraft.client.Minecraft;
import plutonium.setting.KeySetting;
import plutonium.setting.Setting;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {
    protected static final Minecraft mc = Minecraft.getMinecraft();

    private final String   name;
    private final Category category;
    private boolean        enabled;
    private int            keyCode;

    // Ordered list of this module's settings (used by config and GUI)
    private final List<Setting<?>> settings = new ArrayList<Setting<?>>();

    // HUD position, legacy absolute pixels — -1 means use computed default
    public int hudX = -1;
    public int hudY = -1;

    // HUD position, anchor-relative — survives resolution/GUI-scale changes.
    // hudAnchor: -1 = unset (use legacy hudX/hudY or default), else col(0..2) + 3*row(0..2)
    // where col/row 0=left/top, 1=center, 2=right/bottom. Offsets are measured inward from
    // the anchored edge (or from screen center for the center column/row).
    public int hudAnchor = -1;
    public int hudOffX = 0;
    public int hudOffY = 0;

    // HUD element render scale (1.0 = normal). Applied around onRenderHUD by Plutonium.
    public float hudScale = 1.0f;

    public Module(String name, Category category) { this(name, category, 0); }

    public Module(String name, Category category, int keyCode) {
        this.name    = name;
        this.category = category;
        this.keyCode  = keyCode;
    }

    public void onEnable()  {}
    public void onDisable() {}
    public void onTick()    {}
    public void onPreWalkingUpdate() {}
    /** Fires inside the player tick, immediately before movement input is sampled. */
    public void onPreMovementUpdate() {}
    public void onRenderHUD(int screenWidth, int screenHeight) {}
    public void onRenderWorld(float partialTicks) {}
    /** Mouse click while no screen is open. (mouseX, mouseY) are scaled GUI pixels. */
    public void onHudClick(int button, int mouseX, int mouseY) {}

    // Called by ClickGUI/repositioning to know how big this module's HUD element is
    public int getHudWidth()  { return 0; }
    public int getHudHeight() { return 0; }

    // On-screen footprint with hudScale applied (hit-testing, anchor math)
    public final int getHudWidthScaled()  { return Math.round(getHudWidth()  * hudScale); }
    public final int getHudHeightScaled() { return Math.round(getHudHeight() * hudScale); }

    // Default position when the user has never moved the element. Override in subclasses.
    protected int defaultHudX(int sw, int sh) { return 2; }
    protected int defaultHudY(int sw, int sh) { return 2; }

    // Actual top-left render position: anchor-relative if set, else legacy absolute, else default.
    public final int resolveHudX(int sw, int sh) {
        if (hudAnchor >= 0) {
            int col = hudAnchor % 3;
            int w = getHudWidthScaled();
            if (col == 0) return hudOffX;
            if (col == 1) return (sw - w) / 2 + hudOffX;
            return sw - w - hudOffX;
        }
        return hudX >= 0 ? hudX : defaultHudX(sw, sh);
    }

    public final int resolveHudY(int sw, int sh) {
        if (hudAnchor >= 0) {
            int row = hudAnchor / 3;
            int h = getHudHeightScaled();
            if (row == 0) return hudOffY;
            if (row == 1) return (sh - h) / 2 + hudOffY;
            return sh - h - hudOffY;
        }
        return hudY >= 0 ? hudY : defaultHudY(sw, sh);
    }

    /** Store a dragged position as anchor + offset (nearest screen third), clearing legacy coords. */
    public final void setHudPosition(int x, int y, int sw, int sh) {
        int w = getHudWidthScaled(), h = getHudHeightScaled();
        int cx = x + w / 2, cy = y + h / 2;
        int col = cx < sw / 3 ? 0 : (cx < sw * 2 / 3 ? 1 : 2);
        int row = cy < sh / 3 ? 0 : (cy < sh * 2 / 3 ? 1 : 2);
        hudAnchor = col + row * 3;
        hudOffX = col == 0 ? x : (col == 1 ? x - (sw - w) / 2 : sw - w - x);
        hudOffY = row == 0 ? y : (row == 1 ? y - (sh - h) / 2 : sh - h - y);
        hudX = -1;
        hudY = -1;
    }

    /** Clear any stored HUD position/scale back to defaults. */
    public final void resetHudPosition() {
        hudX = -1; hudY = -1;
        hudAnchor = -1; hudOffX = 0; hudOffY = 0;
        hudScale = 1.0f;
    }

    public void toggle() { setEnabled(!enabled); }

    public void setEnabled(boolean state) {
        this.enabled = state;
        if (state) onEnable(); else onDisable();
        plutonium.Config.save(); // no-op while loading or before init
    }

    /** Internal use — set enabled state without triggering save (used during module construction). */
    protected final void initEnabled(boolean state) {
        this.enabled = state;
        if (state) onEnable(); else onDisable();
    }

    // Settings helpers
    protected <T, S extends Setting<T>> S reg(S s) {
        settings.add(s);
        return s;
    }

    public List<Setting<?>> getSettings() { return settings; }

    /** Whether one of this module's own key settings is bound to {@code keyCode}. */
    public boolean usesKey(int keyCode) {
        for (Setting<?> s : settings) {
            if (s instanceof KeySetting && ((KeySetting) s).getValue() == keyCode) return true;
        }
        return false;
    }

    public String getName()     { return name; }
    public Category getCategory() { return category; }
    public boolean isEnabled()  { return enabled; }
    public int getKeyCode()     { return keyCode; }
    public void setKeyCode(int code) { this.keyCode = code; }
}
