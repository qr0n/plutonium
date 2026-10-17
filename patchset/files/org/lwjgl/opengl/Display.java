package org.lwjgl.opengl;

import plutonium.gpu.WindowManager;

/**
 * The read-mostly part of LWJGL 2's Display, for mods and tools compiled against LWJGL 2.
 * Plutonium's window is a GLFW window owned by {@link WindowManager}; creating or
 * re-moding the display stays Minecraft's job, so those calls are no-ops here.
 */
public final class Display {

    private static String title = "Minecraft 1.8.9";

    private Display() {
    }

    public static boolean isCreated() {
        return WindowManager.isCreated();
    }

    public static boolean isActive() {
        return WindowManager.isActive();
    }

    public static boolean isVisible() {
        return !WindowManager.isMinimized();
    }

    public static boolean isCloseRequested() {
        return WindowManager.isCloseRequested();
    }

    public static boolean wasResized() {
        return WindowManager.wasResized();
    }

    /** Window size, the same as Minecraft's displayWidth and the mouse coordinate space. */
    public static int getWidth() {
        return WindowManager.getWindowWidth();
    }

    public static int getHeight() {
        return WindowManager.getWindowHeight();
    }

    public static float getPixelScaleFactor() {
        return WindowManager.getScaleFactor();
    }

    public static String getTitle() {
        return title;
    }

    public static void setTitle(String newTitle) {
        title = newTitle;
        WindowManager.setTitle(newTitle);
    }

    public static void setVSyncEnabled(boolean vsync) {
        WindowManager.setVSyncEnabled(vsync);
    }

    public static void sync(int fps) {
        WindowManager.sync(fps);
    }

    /** Minecraft presents each frame itself; a mod calling this must not swap again. */
    public static void update() {
    }

    public static void update(boolean processMessages) {
    }

    public static void processMessages() {
    }
}
