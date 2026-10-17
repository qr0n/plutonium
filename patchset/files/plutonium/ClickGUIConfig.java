package plutonium;

import plutonium.gpu.KeyboardShim;

public class ClickGUIConfig {
    public static final int ANIM_SLIDE  = 0;
    public static final int ANIM_FADE   = 1;
    public static final int ANIM_INSTANT = 2;
    public static final String[] ANIM_NAMES = {"Slide", "Fade", "Instant"};

    private int openKey = KeyboardShim.KEY_RSHIFT;
    private int animStyle = ANIM_SLIDE;

    public int getOpenKey() { return openKey; }
    public void setOpenKey(int key) { this.openKey = key; }

    public int getAnimStyle() { return animStyle; }
    public void setAnimStyle(int style) { this.animStyle = Math.max(0, Math.min(style, ANIM_NAMES.length - 1)); }
    public void cycleAnimStyle() { animStyle = (animStyle + 1) % ANIM_NAMES.length; }

    public String getAnimName() { return ANIM_NAMES[animStyle]; }
    public String getKeyName() { return KeyboardShim.getKeyName(openKey); }
}
