package plutonium.module.modules;

import plutonium.gpu.KeyboardShim;
import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.KeySetting;

public class Stopwatch extends TextHudModule {

    private long startMs = -1;
    private long savedMs = 0;
    private boolean running = false;
    private boolean keyHeld = false;
    private boolean resetHeld = false;

    private final KeySetting toggleKey = reg(new KeySetting("Toggle Key", "Start/pause the stopwatch", KeyboardShim.KEY_G));
    private final KeySetting resetKey = reg(new KeySetting("Reset Key", "Reset the stopwatch", KeyboardShim.KEY_H));

    public Stopwatch() {
        super("Stopwatch", Category.MISC);
        initEnabled(true);
    }

    @Override
    public void onTick() {
        if (mc.currentScreen != null) return;

        int tKey = toggleKey.getValue();
        if (tKey != 0 && KeyboardShim.isKeyDown(tKey)) {
            if (!keyHeld) {
                keyHeld = true;
                toggleTimer();
            }
        } else {
            keyHeld = false;
        }

        int rKey = resetKey.getValue();
        if (rKey != 0 && KeyboardShim.isKeyDown(rKey)) {
            if (!resetHeld) {
                resetHeld = true;
                resetTimer();
            }
        } else {
            resetHeld = false;
        }
    }

    private void toggleTimer() {
        if (running) {
            savedMs += System.currentTimeMillis() - startMs;
            running = false;
        } else {
            startMs = System.currentTimeMillis();
            running = true;
        }
    }

    private void resetTimer() {
        running = false;
        savedMs = 0;
        startMs = -1;
    }

    @Override
    protected String[] getHudLines() {
        long elapsed = savedMs + (running ? (System.currentTimeMillis() - startMs) : 0);
        long secs = elapsed / 1000;
        long millis = (elapsed % 1000) / 10;
        String text = String.format("%d:%02d.%02d", secs / 60, secs % 60, millis);
        return new String[]{running ? "> " + text : "|| " + text};
    }

    @Override protected int getLineColor(int index) { return running ? 0xFF55FF55 : 0xFFE6EAF2; }
    @Override protected int defaultHudX(int sw, int sh) { return sw - getHudWidthScaled() - 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh - 22; }
}
