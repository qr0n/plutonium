package org.lwjgl.input;

import plutonium.gpu.MouseShim;

/**
 * LWJGL 2's Mouse, for mods and tools compiled against LWJGL 2. Plutonium runs on
 * LWJGL 3 (GLFW), so this class only forwards to {@link MouseShim}, which owns the real
 * event queue and button state. Coordinates use LWJGL 2's bottom-left origin.
 */
public final class Mouse {

    public static final int EVENT_SIZE = 22;

    private static final int BUTTON_COUNT = 8;
    private static boolean clipToWindow = true;

    private Mouse() {
    }

    public static void create() {
    }

    public static void destroy() {
    }

    public static boolean isCreated() {
        return MouseShim.isCreated();
    }

    /** GLFW events are pumped by the window once per frame; nothing to do here. */
    public static void poll() {
    }

    public static boolean next() {
        return MouseShim.next();
    }

    public static boolean isButtonDown(int button) {
        return MouseShim.isButtonDown(button);
    }

    public static int getEventButton() {
        return MouseShim.getEventButton();
    }

    public static boolean getEventButtonState() {
        return MouseShim.getEventButtonState();
    }

    public static int getEventX() {
        return MouseShim.getEventX();
    }

    public static int getEventY() {
        return MouseShim.getEventY();
    }

    public static int getEventDX() {
        return MouseShim.getEventDX();
    }

    public static int getEventDY() {
        return MouseShim.getEventDY();
    }

    public static int getEventDWheel() {
        return MouseShim.getEventDWheel();
    }

    public static long getEventNanoseconds() {
        return MouseShim.getEventNanoseconds();
    }

    public static int getX() {
        return MouseShim.getX();
    }

    public static int getY() {
        return MouseShim.getY();
    }

    public static int getDX() {
        return MouseShim.getDX();
    }

    public static int getDY() {
        return MouseShim.getDY();
    }

    public static int getDWheel() {
        return MouseShim.getDWheel();
    }

    public static int getButtonCount() {
        return BUTTON_COUNT;
    }

    public static boolean hasWheel() {
        return true;
    }

    public static String getButtonName(int button) {
        return button >= 0 && button < BUTTON_COUNT ? "BUTTON" + button : null;
    }

    public static int getButtonIndex(String name) {
        for (int button = 0; button < BUTTON_COUNT; button++) {
            if (("BUTTON" + button).equals(name)) {
                return button;
            }
        }
        return -1;
    }

    public static boolean isGrabbed() {
        return MouseShim.isGrabbed();
    }

    public static void setGrabbed(boolean grab) {
        MouseShim.setGrabbed(grab);
    }

    public static void setCursorPosition(int x, int y) {
        MouseShim.setCursorPosition(x, y);
    }

    public static boolean isInsideWindow() {
        return MouseShim.isInsideWindow();
    }

    public static boolean isClipMouseCoordinatesToWindow() {
        return clipToWindow;
    }

    public static void setClipMouseCoordinatesToWindow(boolean clip) {
        clipToWindow = clip;
    }

    public static void updateCursor() {
    }
}
