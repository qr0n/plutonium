package plutonium.gpu;

import java.util.ArrayDeque;
import java.util.Deque;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;

/**
 * Drop-in replacement for org.lwjgl.input.Mouse (LWJGL 2).
 * Uses GLFW mouse callbacks to replicate the event queue.
 */
public class MouseShim {

    private static final int BUTTON_COUNT = 8;
    private static final boolean[] buttonStates = new boolean[BUTTON_COUNT];
    private static final Deque<MouseEvent> eventQueue = new ArrayDeque<MouseEvent>();
    private static MouseEvent currentEvent = null;
    private static boolean initialized = false;

    private static double cursorX, cursorY;
    private static double lastEventX, lastEventY;  // for per-event deltas (getEventDX/DY)
    private static double prevCursorX, prevCursorY;
    private static int deltaX, deltaY;
    private static int scrollDWheel;
    private static boolean grabbed = false;

    public static void init(long window) {
        if (initialized) return;
        initialized = true;

        GLFW.glfwSetMouseButtonCallback(window, new GLFWMouseButtonCallback() {
            @Override
            public void invoke(long w, int button, int action, int mods) {
                if (button < 0 || button >= BUTTON_COUNT) return;
                boolean pressed = action == GLFW.GLFW_PRESS;
                buttonStates[button] = pressed;
                synchronized (eventQueue) {
                    eventQueue.add(new MouseEvent(button, pressed, (int) cursorX, (int) cursorY, 0));
                }
            }
        });

        GLFW.glfwSetCursorPosCallback(window, new GLFWCursorPosCallback() {
            @Override
            public void invoke(long w, double xpos, double ypos) {
                cursorX = xpos;
                cursorY = ypos;
                MouseEvent e = new MouseEvent(-1, false, (int) xpos, (int) ypos, 0);
                e.dx = (int) (xpos - lastEventX);
                e.dy = (int) (lastEventY - ypos);
                lastEventX = xpos;
                lastEventY = ypos;
                synchronized (eventQueue) {
                    eventQueue.add(e);
                }
            }
        });

        GLFW.glfwSetScrollCallback(window, new GLFWScrollCallback() {
            @Override
            public void invoke(long w, double xoffset, double yoffset) {
                int dw = (int) (yoffset * 120);
                scrollDWheel += dw;
                synchronized (eventQueue) {
                    eventQueue.add(new MouseEvent(-1, false, (int) cursorX, (int) cursorY, dw));
                }
            }
        });

        double[] xArr = new double[1];
        double[] yArr = new double[1];
        GLFW.glfwGetCursorPos(window, xArr, yArr);
        cursorX = xArr[0];
        cursorY = yArr[0];
        lastEventX = cursorX;
        lastEventY = cursorY;
        prevCursorX = cursorX;
        prevCursorY = cursorY;
    }

    public static boolean isButtonDown(int button) {
        if (button < 0 || button >= BUTTON_COUNT) return false;
        return buttonStates[button];
    }

    public static boolean next() {
        synchronized (eventQueue) {
            currentEvent = eventQueue.poll();
        }
        return currentEvent != null;
    }

    public static int getEventButton() {
        return currentEvent != null ? currentEvent.button : -1;
    }

    public static boolean getEventButtonState() {
        return currentEvent != null && currentEvent.pressed;
    }

    // Cursor coords from GLFW are in logical screen points, which now match the logical
    // render size (displayWidth == window size). No HiDPI scale factor is applied; the
    // 2x Retina backing is handled purely by the upscale-blit at present time.
    public static int getEventX() {
        return currentEvent != null ? (int) currentEvent.x : (int) cursorX;
    }

    public static int getEventY() {
        int h = WindowManager.getWindowHeight();
        return currentEvent != null ? (int)(h - currentEvent.y) : (int)(h - cursorY);
    }

    public static int getEventDWheel() {
        return currentEvent != null ? currentEvent.dwheel : 0;
    }

    public static int getEventDX() {
        return currentEvent != null ? currentEvent.dx : 0;
    }

    public static int getEventDY() {
        return currentEvent != null ? currentEvent.dy : 0;
    }

    public static long getEventNanoseconds() {
        return currentEvent != null ? currentEvent.nanos : 0L;
    }

    /** Test/puppet hook: move the virtual cursor (GLFW window coords, top-left origin). */
    public static void setCursorPos(double x, double y) {
        cursorX = x;
        cursorY = y;
    }

    public static int getX() {
        return (int) cursorX;
    }

    public static int getY() {
        return (int) (WindowManager.getWindowHeight() - cursorY);
    }

    public static int getDX() {
        int dx = (int) (cursorX - prevCursorX);
        prevCursorX = cursorX;
        return dx;
    }

    public static int getDY() {
        int dy = (int) (prevCursorY - cursorY);
        prevCursorY = cursorY;
        return dy;
    }

    public static int getDWheel() {
        int dw = scrollDWheel;
        scrollDWheel = 0;
        return dw;
    }

    public static void setGrabbed(boolean grab) {
        grabbed = grab;
        long window = WindowManager.getHandle();
        GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR,
                grab ? GLFW.GLFW_CURSOR_DISABLED : GLFW.GLFW_CURSOR_NORMAL);
        if (grab) {
            prevCursorX = cursorX;
            prevCursorY = cursorY;
        }
    }

    public static boolean isGrabbed() {
        return grabbed;
    }

    public static void setCursorPosition(int x, int y) {
        long window = WindowManager.getHandle();
        GLFW.glfwSetCursorPos(window, x, y);
        cursorX = x;
        cursorY = y;
        prevCursorX = x;
        prevCursorY = y;
    }

    public static boolean isCreated() {
        return initialized;
    }

    public static int debugQueueSize() {
        synchronized (eventQueue) {
            return eventQueue.size();
        }
    }

    public static boolean isInsideWindow() {
        return true;
    }

    private static class MouseEvent {
        final int button;
        final boolean pressed;
        final int x, y;
        final int dwheel;
        final long nanos = System.nanoTime();
        int dx, dy;

        MouseEvent(int button, boolean pressed, int x, int y, int dwheel) {
            this.button = button;
            this.pressed = pressed;
            this.x = x;
            this.y = y;
            this.dwheel = dwheel;
        }
    }
}
