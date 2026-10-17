package plutonium.gpu;

import java.util.ArrayDeque;
import java.util.Deque;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharCallback;
import org.lwjgl.glfw.GLFWKeyCallback;

/**
 * Drop-in replacement for org.lwjgl.input.Keyboard (LWJGL 2).
 * Uses GLFW key callbacks and translates GLFW key codes to LWJGL 2 key codes
 * so that Minecraft's saved key bindings (options.txt) work correctly.
 */
public class KeyboardShim {

    // ---------- LWJGL 2 key code constants ----------
    // These MUST match the original org.lwjgl.input.Keyboard values because
    // Minecraft's options.txt stores key bindings using these numeric codes.
    public static final int KEY_NONE          = 0;
    public static final int KEY_ESCAPE        = 1;
    public static final int KEY_1             = 2;
    public static final int KEY_2             = 3;
    public static final int KEY_3             = 4;
    public static final int KEY_4             = 5;
    public static final int KEY_5             = 6;
    public static final int KEY_6             = 7;
    public static final int KEY_7             = 8;
    public static final int KEY_8             = 9;
    public static final int KEY_9             = 10;
    public static final int KEY_0             = 11;
    public static final int KEY_MINUS         = 12;
    public static final int KEY_EQUALS        = 13;
    public static final int KEY_BACK          = 14;
    public static final int KEY_TAB           = 15;
    public static final int KEY_Q             = 16;
    public static final int KEY_W             = 17;
    public static final int KEY_E             = 18;
    public static final int KEY_R             = 19;
    public static final int KEY_T             = 20;
    public static final int KEY_Y             = 21;
    public static final int KEY_U             = 22;
    public static final int KEY_I             = 23;
    public static final int KEY_O             = 24;
    public static final int KEY_P             = 25;
    public static final int KEY_LBRACKET      = 26;
    public static final int KEY_RBRACKET      = 27;
    public static final int KEY_RETURN        = 28;
    public static final int KEY_LCONTROL      = 29;
    public static final int KEY_A             = 30;
    public static final int KEY_S             = 31;
    public static final int KEY_D             = 32;
    public static final int KEY_F             = 33;
    public static final int KEY_G             = 34;
    public static final int KEY_H             = 35;
    public static final int KEY_J             = 36;
    public static final int KEY_K             = 37;
    public static final int KEY_L             = 38;
    public static final int KEY_SEMICOLON     = 39;
    public static final int KEY_APOSTROPHE    = 40;
    public static final int KEY_GRAVE         = 41;
    public static final int KEY_LSHIFT        = 42;
    public static final int KEY_BACKSLASH     = 43;
    public static final int KEY_Z             = 44;
    public static final int KEY_X             = 45;
    public static final int KEY_C             = 46;
    public static final int KEY_V             = 47;
    public static final int KEY_B             = 48;
    public static final int KEY_N             = 49;
    public static final int KEY_M             = 50;
    public static final int KEY_COMMA         = 51;
    public static final int KEY_PERIOD        = 52;
    public static final int KEY_SLASH         = 53;
    public static final int KEY_RSHIFT        = 54;
    public static final int KEY_MULTIPLY      = 55;
    public static final int KEY_LMENU         = 56;
    public static final int KEY_SPACE         = 57;
    public static final int KEY_CAPITAL       = 58;
    public static final int KEY_F1            = 59;
    public static final int KEY_F2            = 60;
    public static final int KEY_F3            = 61;
    public static final int KEY_F4            = 62;
    public static final int KEY_F5            = 63;
    public static final int KEY_F6            = 64;
    public static final int KEY_F7            = 65;
    public static final int KEY_F8            = 66;
    public static final int KEY_F9            = 67;
    public static final int KEY_F10           = 68;
    public static final int KEY_NUMLOCK       = 69;
    public static final int KEY_SCROLL        = 70;
    public static final int KEY_NUMPAD7       = 71;
    public static final int KEY_NUMPAD8       = 72;
    public static final int KEY_NUMPAD9       = 73;
    public static final int KEY_SUBTRACT      = 74;
    public static final int KEY_NUMPAD4       = 75;
    public static final int KEY_NUMPAD5       = 76;
    public static final int KEY_NUMPAD6       = 77;
    public static final int KEY_ADD           = 78;
    public static final int KEY_NUMPAD1       = 79;
    public static final int KEY_NUMPAD2       = 80;
    public static final int KEY_NUMPAD3       = 81;
    public static final int KEY_NUMPAD0       = 82;
    public static final int KEY_DECIMAL       = 83;
    public static final int KEY_F11           = 87;
    public static final int KEY_F12           = 88;
    public static final int KEY_F13           = 100;
    public static final int KEY_F14           = 101;
    public static final int KEY_F15           = 102;
    public static final int KEY_NUMPADENTER   = 156;
    public static final int KEY_RCONTROL      = 157;
    public static final int KEY_DIVIDE        = 181;
    public static final int KEY_RMENU         = 184;
    public static final int KEY_PAUSE         = 197;
    public static final int KEY_HOME          = 199;
    public static final int KEY_UP            = 200;
    public static final int KEY_PRIOR         = 201;
    public static final int KEY_LEFT          = 203;
    public static final int KEY_RIGHT         = 205;
    public static final int KEY_END           = 207;
    public static final int KEY_DOWN          = 208;
    public static final int KEY_NEXT          = 209;
    public static final int KEY_INSERT        = 210;
    public static final int KEY_DELETE        = 211;
    public static final int KEY_LMETA         = 219;
    public static final int KEY_RMETA         = 220;

    // ---------- Internal state ----------
    private static final int LWJGL2_KEY_COUNT = 256;
    private static final boolean[] keyStates = new boolean[LWJGL2_KEY_COUNT];
    private static final Deque<KeyEvent> eventQueue = new ArrayDeque<KeyEvent>();
    private static KeyEvent currentEvent = null;
    private static boolean repeatEnabled = false;
    private static boolean initialized = false;

    // ---------- GLFW → LWJGL 2 translation ----------

    /**
     * Convert a GLFW key code to the equivalent LWJGL 2 key code.
     * Returns KEY_NONE (0) for unmapped keys.
     */
    private static int glfwToLwjgl2(int glfwKey) {
        switch (glfwKey) {
            case GLFW.GLFW_KEY_SPACE:         return KEY_SPACE;
            case GLFW.GLFW_KEY_APOSTROPHE:    return KEY_APOSTROPHE;
            case GLFW.GLFW_KEY_COMMA:         return KEY_COMMA;
            case GLFW.GLFW_KEY_MINUS:         return KEY_MINUS;
            case GLFW.GLFW_KEY_PERIOD:        return KEY_PERIOD;
            case GLFW.GLFW_KEY_SLASH:         return KEY_SLASH;
            case GLFW.GLFW_KEY_0:             return KEY_0;
            case GLFW.GLFW_KEY_1:             return KEY_1;
            case GLFW.GLFW_KEY_2:             return KEY_2;
            case GLFW.GLFW_KEY_3:             return KEY_3;
            case GLFW.GLFW_KEY_4:             return KEY_4;
            case GLFW.GLFW_KEY_5:             return KEY_5;
            case GLFW.GLFW_KEY_6:             return KEY_6;
            case GLFW.GLFW_KEY_7:             return KEY_7;
            case GLFW.GLFW_KEY_8:             return KEY_8;
            case GLFW.GLFW_KEY_9:             return KEY_9;
            case GLFW.GLFW_KEY_SEMICOLON:     return KEY_SEMICOLON;
            case GLFW.GLFW_KEY_EQUAL:         return KEY_EQUALS;
            case GLFW.GLFW_KEY_A:             return KEY_A;
            case GLFW.GLFW_KEY_B:             return KEY_B;
            case GLFW.GLFW_KEY_C:             return KEY_C;
            case GLFW.GLFW_KEY_D:             return KEY_D;
            case GLFW.GLFW_KEY_E:             return KEY_E;
            case GLFW.GLFW_KEY_F:             return KEY_F;
            case GLFW.GLFW_KEY_G:             return KEY_G;
            case GLFW.GLFW_KEY_H:             return KEY_H;
            case GLFW.GLFW_KEY_I:             return KEY_I;
            case GLFW.GLFW_KEY_J:             return KEY_J;
            case GLFW.GLFW_KEY_K:             return KEY_K;
            case GLFW.GLFW_KEY_L:             return KEY_L;
            case GLFW.GLFW_KEY_M:             return KEY_M;
            case GLFW.GLFW_KEY_N:             return KEY_N;
            case GLFW.GLFW_KEY_O:             return KEY_O;
            case GLFW.GLFW_KEY_P:             return KEY_P;
            case GLFW.GLFW_KEY_Q:             return KEY_Q;
            case GLFW.GLFW_KEY_R:             return KEY_R;
            case GLFW.GLFW_KEY_S:             return KEY_S;
            case GLFW.GLFW_KEY_T:             return KEY_T;
            case GLFW.GLFW_KEY_U:             return KEY_U;
            case GLFW.GLFW_KEY_V:             return KEY_V;
            case GLFW.GLFW_KEY_W:             return KEY_W;
            case GLFW.GLFW_KEY_X:             return KEY_X;
            case GLFW.GLFW_KEY_Y:             return KEY_Y;
            case GLFW.GLFW_KEY_Z:             return KEY_Z;
            case GLFW.GLFW_KEY_LEFT_BRACKET:  return KEY_LBRACKET;
            case GLFW.GLFW_KEY_BACKSLASH:     return KEY_BACKSLASH;
            case GLFW.GLFW_KEY_RIGHT_BRACKET: return KEY_RBRACKET;
            case GLFW.GLFW_KEY_GRAVE_ACCENT:  return KEY_GRAVE;
            case GLFW.GLFW_KEY_ESCAPE:        return KEY_ESCAPE;
            case GLFW.GLFW_KEY_ENTER:         return KEY_RETURN;
            case GLFW.GLFW_KEY_TAB:           return KEY_TAB;
            case GLFW.GLFW_KEY_BACKSPACE:     return KEY_BACK;
            case GLFW.GLFW_KEY_INSERT:        return KEY_INSERT;
            case GLFW.GLFW_KEY_DELETE:        return KEY_DELETE;
            case GLFW.GLFW_KEY_RIGHT:         return KEY_RIGHT;
            case GLFW.GLFW_KEY_LEFT:          return KEY_LEFT;
            case GLFW.GLFW_KEY_DOWN:          return KEY_DOWN;
            case GLFW.GLFW_KEY_UP:            return KEY_UP;
            case GLFW.GLFW_KEY_PAGE_UP:       return KEY_PRIOR;
            case GLFW.GLFW_KEY_PAGE_DOWN:     return KEY_NEXT;
            case GLFW.GLFW_KEY_HOME:          return KEY_HOME;
            case GLFW.GLFW_KEY_END:           return KEY_END;
            case GLFW.GLFW_KEY_CAPS_LOCK:     return KEY_CAPITAL;
            case GLFW.GLFW_KEY_SCROLL_LOCK:   return KEY_SCROLL;
            case GLFW.GLFW_KEY_NUM_LOCK:      return KEY_NUMLOCK;
            case GLFW.GLFW_KEY_PAUSE:         return KEY_PAUSE;
            case GLFW.GLFW_KEY_F1:            return KEY_F1;
            case GLFW.GLFW_KEY_F2:            return KEY_F2;
            case GLFW.GLFW_KEY_F3:            return KEY_F3;
            case GLFW.GLFW_KEY_F4:            return KEY_F4;
            case GLFW.GLFW_KEY_F5:            return KEY_F5;
            case GLFW.GLFW_KEY_F6:            return KEY_F6;
            case GLFW.GLFW_KEY_F7:            return KEY_F7;
            case GLFW.GLFW_KEY_F8:            return KEY_F8;
            case GLFW.GLFW_KEY_F9:            return KEY_F9;
            case GLFW.GLFW_KEY_F10:           return KEY_F10;
            case GLFW.GLFW_KEY_F11:           return KEY_F11;
            case GLFW.GLFW_KEY_F12:           return KEY_F12;
            case GLFW.GLFW_KEY_F13:           return KEY_F13;
            case GLFW.GLFW_KEY_F14:           return KEY_F14;
            case GLFW.GLFW_KEY_F15:           return KEY_F15;
            case GLFW.GLFW_KEY_KP_0:          return KEY_NUMPAD0;
            case GLFW.GLFW_KEY_KP_1:          return KEY_NUMPAD1;
            case GLFW.GLFW_KEY_KP_2:          return KEY_NUMPAD2;
            case GLFW.GLFW_KEY_KP_3:          return KEY_NUMPAD3;
            case GLFW.GLFW_KEY_KP_4:          return KEY_NUMPAD4;
            case GLFW.GLFW_KEY_KP_5:          return KEY_NUMPAD5;
            case GLFW.GLFW_KEY_KP_6:          return KEY_NUMPAD6;
            case GLFW.GLFW_KEY_KP_7:          return KEY_NUMPAD7;
            case GLFW.GLFW_KEY_KP_8:          return KEY_NUMPAD8;
            case GLFW.GLFW_KEY_KP_9:          return KEY_NUMPAD9;
            case GLFW.GLFW_KEY_KP_DECIMAL:    return KEY_DECIMAL;
            case GLFW.GLFW_KEY_KP_DIVIDE:     return KEY_DIVIDE;
            case GLFW.GLFW_KEY_KP_MULTIPLY:   return KEY_MULTIPLY;
            case GLFW.GLFW_KEY_KP_SUBTRACT:   return KEY_SUBTRACT;
            case GLFW.GLFW_KEY_KP_ADD:        return KEY_ADD;
            case GLFW.GLFW_KEY_KP_ENTER:      return KEY_NUMPADENTER;
            case GLFW.GLFW_KEY_LEFT_SHIFT:    return KEY_LSHIFT;
            case GLFW.GLFW_KEY_LEFT_CONTROL:  return KEY_LCONTROL;
            case GLFW.GLFW_KEY_LEFT_ALT:      return KEY_LMENU;
            case GLFW.GLFW_KEY_LEFT_SUPER:    return KEY_LMETA;
            case GLFW.GLFW_KEY_RIGHT_SHIFT:   return KEY_RSHIFT;
            case GLFW.GLFW_KEY_RIGHT_CONTROL: return KEY_RCONTROL;
            case GLFW.GLFW_KEY_RIGHT_ALT:     return KEY_RMENU;
            case GLFW.GLFW_KEY_RIGHT_SUPER:   return KEY_RMETA;
            default:                          return KEY_NONE;
        }
    }

    // ---------- Initialization ----------

    public static void init(long window) {
        if (initialized) return;
        initialized = true;

        GLFW.glfwSetKeyCallback(window, new GLFWKeyCallback() {
            @Override
            public void invoke(long w, int glfwKey, int scancode, int action, int mods) {
                int lwjgl2Key = glfwToLwjgl2(glfwKey);
                if (lwjgl2Key == KEY_NONE) return;
                boolean pressed = action != GLFW.GLFW_RELEASE;
                if (action == GLFW.GLFW_REPEAT && !repeatEnabled) return;
                if (lwjgl2Key >= 0 && lwjgl2Key < LWJGL2_KEY_COUNT) {
                    keyStates[lwjgl2Key] = pressed;
                }
                synchronized (eventQueue) {
                    eventQueue.add(new KeyEvent(lwjgl2Key, pressed, (char) 0));
                }
            }
        });

        GLFW.glfwSetCharCallback(window, new GLFWCharCallback() {
            @Override
            public void invoke(long w, int codepoint) {
                synchronized (eventQueue) {
                    // Attach char to the last key event if it was a press
                    KeyEvent last = eventQueue.peekLast();
                    if (last != null && last.pressed && last.character == 0) {
                        last.character = (char) codepoint;
                    } else if (repeatEnabled) {
                        // Standalone char events only matter for text input (IME, dead
                        // keys), and all text-input screens enable repeat events. With
                        // repeats disabled, GLFW still fires char callbacks for key
                        // repeats we dropped above; enqueueing those creates key==0
                        // events whose character gets misread as an LWJGL2 keycode by
                        // dispatchKeypresses (e.g. 'W' == 87 == KEY_F11 -> fullscreen).
                        eventQueue.add(new KeyEvent(0, true, (char) codepoint));
                    }
                }
            }
        });
    }

    // ---------- State queries (all use LWJGL 2 key codes) ----------

    // Test/puppet hook: forced key states, independent of GLFW. Lets the puppet
    // server (debug mode only) drive keybinds that poll isKeyDown directly.
    private static final boolean[] keyOverride = new boolean[LWJGL2_KEY_COUNT];

    public static void setKeyOverride(int lwjgl2Key, boolean state) {
        if (lwjgl2Key >= 0 && lwjgl2Key < keyOverride.length) keyOverride[lwjgl2Key] = state;
    }

    public static boolean isKeyDown(int key) {
        if (key < 0 || key >= LWJGL2_KEY_COUNT) return false;
        return keyStates[key] || keyOverride[key];
    }

    public static boolean next() {
        synchronized (eventQueue) {
            currentEvent = eventQueue.poll();
        }
        return currentEvent != null;
    }

    public static int getEventKey() {
        return currentEvent != null ? currentEvent.key : 0;
    }

    public static boolean getEventKeyState() {
        return currentEvent != null && currentEvent.pressed;
    }

    public static char getEventCharacter() {
        return currentEvent != null ? currentEvent.character : 0;
    }

    public static void enableRepeatEvents(boolean enable) {
        repeatEnabled = enable;
    }

    public static boolean areRepeatEventsEnabled() {
        return repeatEnabled;
    }

    public static boolean isCreated() {
        return initialized;
    }

    public static int debugQueueSize() {
        synchronized (eventQueue) {
            return eventQueue.size();
        }
    }

    public static boolean isRepeatEvent() {
        return false;
    }

    /**
     * Returns a human-readable name for an LWJGL 2 key code.
     */
    public static String getKeyName(int lwjgl2Key) {
        switch (lwjgl2Key) {
            case KEY_NONE:       return "NONE";
            case KEY_ESCAPE:     return "ESCAPE";
            case KEY_1:          return "1";
            case KEY_2:          return "2";
            case KEY_3:          return "3";
            case KEY_4:          return "4";
            case KEY_5:          return "5";
            case KEY_6:          return "6";
            case KEY_7:          return "7";
            case KEY_8:          return "8";
            case KEY_9:          return "9";
            case KEY_0:          return "0";
            case KEY_MINUS:      return "MINUS";
            case KEY_EQUALS:     return "EQUALS";
            case KEY_BACK:       return "BACK";
            case KEY_TAB:        return "TAB";
            case KEY_Q:          return "Q";
            case KEY_W:          return "W";
            case KEY_E:          return "E";
            case KEY_R:          return "R";
            case KEY_T:          return "T";
            case KEY_Y:          return "Y";
            case KEY_U:          return "U";
            case KEY_I:          return "I";
            case KEY_O:          return "O";
            case KEY_P:          return "P";
            case KEY_LBRACKET:   return "LBRACKET";
            case KEY_RBRACKET:   return "RBRACKET";
            case KEY_RETURN:     return "RETURN";
            case KEY_LCONTROL:   return "LCONTROL";
            case KEY_A:          return "A";
            case KEY_S:          return "S";
            case KEY_D:          return "D";
            case KEY_F:          return "F";
            case KEY_G:          return "G";
            case KEY_H:          return "H";
            case KEY_J:          return "J";
            case KEY_K:          return "K";
            case KEY_L:          return "L";
            case KEY_SEMICOLON:  return "SEMICOLON";
            case KEY_APOSTROPHE: return "APOSTROPHE";
            case KEY_GRAVE:      return "GRAVE";
            case KEY_LSHIFT:     return "LSHIFT";
            case KEY_BACKSLASH:  return "BACKSLASH";
            case KEY_Z:          return "Z";
            case KEY_X:          return "X";
            case KEY_C:          return "C";
            case KEY_V:          return "V";
            case KEY_B:          return "B";
            case KEY_N:          return "N";
            case KEY_M:          return "M";
            case KEY_COMMA:      return "COMMA";
            case KEY_PERIOD:     return "PERIOD";
            case KEY_SLASH:      return "SLASH";
            case KEY_RSHIFT:     return "RSHIFT";
            case KEY_MULTIPLY:   return "MULTIPLY";
            case KEY_LMENU:      return "LMENU";
            case KEY_SPACE:      return "SPACE";
            case KEY_CAPITAL:    return "CAPITAL";
            case KEY_F1:         return "F1";
            case KEY_F2:         return "F2";
            case KEY_F3:         return "F3";
            case KEY_F4:         return "F4";
            case KEY_F5:         return "F5";
            case KEY_F6:         return "F6";
            case KEY_F7:         return "F7";
            case KEY_F8:         return "F8";
            case KEY_F9:         return "F9";
            case KEY_F10:        return "F10";
            case KEY_NUMLOCK:    return "NUMLOCK";
            case KEY_SCROLL:     return "SCROLL";
            case KEY_NUMPAD7:    return "NUMPAD7";
            case KEY_NUMPAD8:    return "NUMPAD8";
            case KEY_NUMPAD9:    return "NUMPAD9";
            case KEY_SUBTRACT:   return "SUBTRACT";
            case KEY_NUMPAD4:    return "NUMPAD4";
            case KEY_NUMPAD5:    return "NUMPAD5";
            case KEY_NUMPAD6:    return "NUMPAD6";
            case KEY_ADD:        return "ADD";
            case KEY_NUMPAD1:    return "NUMPAD1";
            case KEY_NUMPAD2:    return "NUMPAD2";
            case KEY_NUMPAD3:    return "NUMPAD3";
            case KEY_NUMPAD0:    return "NUMPAD0";
            case KEY_DECIMAL:    return "DECIMAL";
            case KEY_F11:        return "F11";
            case KEY_F12:        return "F12";
            case KEY_F13:        return "F13";
            case KEY_F14:        return "F14";
            case KEY_F15:        return "F15";
            case KEY_NUMPADENTER: return "NUMPADENTER";
            case KEY_RCONTROL:   return "RCONTROL";
            case KEY_DIVIDE:     return "DIVIDE";
            case KEY_RMENU:      return "RMENU";
            case KEY_PAUSE:      return "PAUSE";
            case KEY_HOME:       return "HOME";
            case KEY_UP:         return "UP";
            case KEY_PRIOR:      return "PRIOR";
            case KEY_LEFT:       return "LEFT";
            case KEY_RIGHT:      return "RIGHT";
            case KEY_END:        return "END";
            case KEY_DOWN:       return "DOWN";
            case KEY_NEXT:       return "NEXT";
            case KEY_INSERT:     return "INSERT";
            case KEY_DELETE:     return "DELETE";
            case KEY_LMETA:      return "LMETA";
            case KEY_RMETA:      return "RMETA";
            default:             return "KEY_" + lwjgl2Key;
        }
    }

    // ---------- Internal event class ----------

    private static class KeyEvent {
        final int key; // LWJGL 2 key code
        final boolean pressed;
        char character;

        KeyEvent(int key, boolean pressed, char character) {
            this.key = key;
            this.pressed = pressed;
            this.character = character;
        }
    }
}
