package plutonium.gpu;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public class WindowManager {

    private static long window = MemoryUtil.NULL;
    private static long hiddenWindow = MemoryUtil.NULL;
    private static int fbWidth, fbHeight;
    private static int windowWidth, windowHeight;
    private static boolean closeRequested = false;
    private static boolean resized = false;
    private static boolean focused = true;
    private static String title = "Minecraft 1.8.9";
    private static boolean fullscreen = false;
    private static int windowedX, windowedY, windowedW, windowedH;

    private static boolean glfwInitialized = false;

    public static void init() {
        if (glfwInitialized) return;
        glfwInitialized = true;
        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) {
            throw new RuntimeException("Failed to initialize GLFW");
        }
    }

    public static void create(int width, int height, int depthBits, int samples) {
        // Vulkan: GLFW must not create a GL context; MoltenVK is loaded by LWJGL, so point
        // GLFW's Vulkan support at it before any vulkan-dependent GLFW call.
        GLFWVulkan.glfwInitVulkanLoader(
                org.lwjgl.vulkan.VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new RuntimeException("GLFW reports no Vulkan support (MoltenVK not reachable)");
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        // Disable Retina/HiDPI backing on macOS so the framebuffer matches the window
        // size in screen points (1352x848) instead of 2x (2704x1696). MC 1.8.9 does not
        // handle a HiDPI backing store correctly and the 4x fragment cost is pure waste;
        // the LWJGL2 arm64 build disabled this in the native for the same reason.
        GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_FALSE);

        window = GLFW.glfwCreateWindow(width, height, title, MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) {
            throw new RuntimeException("Failed to create GLFW window");
        }

        GLFW.glfwSetWindowCloseCallback(window, w -> closeRequested = true);
        GLFW.glfwSetFramebufferSizeCallback(window, (w, fw, fh) -> {
            fbWidth = fw;
            fbHeight = fh;
            resized = true;
        });
        GLFW.glfwSetWindowSizeCallback(window, (w, ww, wh) -> {
            windowWidth = ww;
            windowHeight = wh;
            resized = true;
        });
        GLFW.glfwSetWindowFocusCallback(window, (w, f) -> focused = f);

        // Show before sizing anything: macOS only constrains an oversized window to the
        // screen when it is shown, so a hidden 1352x848 window becomes 1352x717 after the
        // swapchain was already built for 848 — the splash then left the rest undrawn.
        GLFW.glfwShowWindow(window);
        GLFW.glfwPollEvents();
        resized = false;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fw = stack.mallocInt(1);
            IntBuffer fh = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, fw, fh);
            fbWidth = fw.get(0);
            fbHeight = fh.get(0);

            IntBuffer ww = stack.mallocInt(1);
            IntBuffer wh = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(window, ww, wh);
            windowWidth = ww.get(0);
            windowHeight = wh.get(0);
        }

        if (net.minecraft.client.Minecraft.GPU_DEBUG) System.err.println("[GPU-DBG] window=" + windowWidth + "x" + windowHeight + " framebuffer=" + fbWidth + "x" + fbHeight + " scale=" + getScaleFactor());
        System.err.flush();

        // Bring up the whole Vulkan stack against this window.
        plutonium.gpu.vk.VkContext.init(window);
        plutonium.gpu.vk.VkMemory.init();
        plutonium.gpu.vk.VkSwapchain.create(Math.max(1, fbWidth), Math.max(1, fbHeight), vsyncRequested);
        plutonium.gpu.vk.VkFrameRing.init();
        plutonium.gpu.vk.VkPipelines.init();
        plutonium.gpu.vk.VkTextures.init();
        plutonium.gpu.vk.VkRenderTargets.init();
        plutonium.gpu.vk.VkDrawDispatch.init();
        CoreShaders.init();

        KeyboardShim.init(window);
        MouseShim.init(window);
    }

    public static void create(int depthBits) {
        GLFWVidMode vidMode = GLFW.glfwGetVideoMode(GLFW.glfwGetPrimaryMonitor());
        int w = vidMode != null ? vidMode.width() * 2 / 3 : 854;
        int h = vidMode != null ? vidMode.height() * 2 / 3 : 480;
        create(w, h, depthBits, 0);
    }

    public static void create() {
        create(24);
    }

    public static long lastSwapNs = 0, lastPollNs = 0;
    public static void update() {
        long a = System.nanoTime();
        // Blit screen target → swapchain, submit the frame, present.
        plutonium.gpu.vk.VkRenderTargets.present();
        long b = System.nanoTime();
        GLFW.glfwPollEvents();
        long c = System.nanoTime();
        lastSwapNs = b - a;
        lastPollNs = c - b;
        syncLiveSizes();
        if (wasResizedPeek()) {
            plutonium.gpu.vk.VkRenderTargets.markSwapchainDirty();
        }
    }

    // wasResized() consumes the flag (Minecraft.runGameLoop uses it to resize framebufferMc);
    // update() needs a non-consuming peek to trigger swapchain recreation.
    private static boolean wasResizedPeek() { return resized; }

    /**
     * Pump OS/window events without swapping. Used on the main thread when the present thread
     * owns the swap (PresentThread.ENABLED). glfwPollEvents MUST stay on the main thread on macOS.
     */
    public static void pollOnly() {
        if (window == MemoryUtil.NULL) return;
        long b = System.nanoTime();
        GLFW.glfwPollEvents();
        long c = System.nanoTime();
        lastPollNs = c - b;
        lastSwapNs = 0;
        syncLiveSizes();
    }


    /**
     * Re-query the true window + framebuffer size from GLFW and keep our cached values
     * authoritative. The framebuffer-size callback alone is unreliable on macOS: when the
     * window moves between Retina and non-Retina displays (or the backing scale changes)
     * the callback can fail to fire or report the stale backing scale, leaving fbWidth/
     * fbHeight pointing at the old 2x size. The present-blit then targets the wrong
     * viewport and the frame shows magnified/cropped. Polling here costs one cheap GLFW
     * call per frame and guarantees getWidth()/getHeight() match the real default FBO.
     */
    private static void syncLiveSizes() {
        if (window == MemoryUtil.NULL) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer fw = stack.mallocInt(1), fh = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, fw, fh);
            IntBuffer ww = stack.mallocInt(1), wh = stack.mallocInt(1);
            GLFW.glfwGetWindowSize(window, ww, wh);
            int nfw = fw.get(0), nfh = fh.get(0), nww = ww.get(0), nwh = wh.get(0);
            if (nfw > 0 && nfh > 0 && (nfw != fbWidth || nfh != fbHeight
                    || nww != windowWidth || nwh != windowHeight)) {
                fbWidth = nfw; fbHeight = nfh;
                windowWidth = nww; windowHeight = nwh;
                resized = true;
            }
        }
    }

    /**
     * Pump the native event loop without swapping buffers.
     * On macOS, failing to call glfwPollEvents() frequently causes the Cocoa
     * event queue to back up, which can make the next glfwPollEvents() block
     * indefinitely.  Call this during long init phases.
     */
    public static void pumpEvents() {
        if (window != MemoryUtil.NULL) {
            GLFW.glfwPollEvents();
        }
    }

    public static void destroy() {
        if (window != MemoryUtil.NULL) {
            GLFW.glfwDestroyWindow(window);
            window = MemoryUtil.NULL;
        }
        GLFW.glfwTerminate();
    }

    public static long getHiddenWindow() {
        return hiddenWindow;
    }

    public static boolean isCloseRequested() {
        return closeRequested || GLFW.glfwWindowShouldClose(window);
    }

    public static boolean wasResized() {
        boolean r = resized;
        resized = false;
        return r;
    }

    public static int getWidth() {
        return fbWidth;
    }

    public static int getHeight() {
        return fbHeight;
    }

    public static int getWindowWidth() {
        return windowWidth;
    }

    public static int getWindowHeight() {
        return windowHeight;
    }

    public static float getScaleFactor() {
        if (windowWidth == 0) return 1.0f;
        return (float) fbWidth / (float) windowWidth;
    }

    public static void setTitle(String t) {
        title = t;
        if (window != MemoryUtil.NULL) {
            GLFW.glfwSetWindowTitle(window, t);
        }
    }

    public static void setResizable(boolean resizable) {
        // GLFW doesn't support changing resizable after creation, but the hint is set at create time
    }

    private static boolean vsyncRequested = true;

    public static void setVSyncEnabled(boolean vsync) {
        if (vsync != vsyncRequested) {
            vsyncRequested = vsync;
            if (plutonium.gpu.vk.VkSwapchain.handle() != 0 && plutonium.gpu.vk.VkSwapchain.isVsync() != vsync) {
                plutonium.gpu.vk.VkRenderTargets.markSwapchainDirty();
            }
        }
    }

    public static boolean isVSyncRequested() { return vsyncRequested; }

    private static long lastSyncTime = 0;

    public static void sync(int fps) {
        if (fps <= 0) return;
        long targetNanos = 1000000000L / fps;
        long now = System.nanoTime();
        if (lastSyncTime == 0) {
            lastSyncTime = now;
            return;
        }
        long elapsed = now - lastSyncTime;
        long sleepNanos = targetNanos - elapsed;
        if (sleepNanos > 1000000L) {
            try {
                Thread.sleep(sleepNanos / 1000000L, (int)(sleepNanos % 1000000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastSyncTime = System.nanoTime();
    }

    public static boolean isActive() {
        return focused;
    }

    /** Windows reports a 0x0 surface while iconified, so no swapchain can exist then. */
    public static boolean isMinimized() {
        return window != MemoryUtil.NULL
                && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_TRUE;
    }

    public static boolean isCreated() {
        return window != MemoryUtil.NULL;
    }

    public static void setFullscreen(boolean fs) {
        long monitor = GLFW.glfwGetPrimaryMonitor();
        GLFWVidMode vidMode = GLFW.glfwGetVideoMode(monitor);
        if (vidMode == null) return;

        if (fs && !fullscreen) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer xBuf = stack.mallocInt(1);
                IntBuffer yBuf = stack.mallocInt(1);
                GLFW.glfwGetWindowPos(window, xBuf, yBuf);
                windowedX = xBuf.get(0);
                windowedY = yBuf.get(0);
            }
            windowedW = windowWidth;
            windowedH = windowHeight;
            GLFW.glfwSetWindowMonitor(window, monitor, 0, 0,
                    vidMode.width(), vidMode.height(), vidMode.refreshRate());
        } else if (!fs && fullscreen) {
            GLFW.glfwSetWindowMonitor(window, MemoryUtil.NULL,
                    windowedX, windowedY, windowedW, windowedH, GLFW.GLFW_DONT_CARE);
        }
        fullscreen = fs;
    }

    public static void setDisplayMode(int width, int height) {
        if (fullscreen) {
            long monitor = GLFW.glfwGetPrimaryMonitor();
            GLFWVidMode vidMode = GLFW.glfwGetVideoMode(monitor);
            int refreshRate = vidMode != null ? vidMode.refreshRate() : GLFW.GLFW_DONT_CARE;
            GLFW.glfwSetWindowMonitor(window, monitor, 0, 0, width, height, refreshRate);
        } else {
            GLFW.glfwSetWindowSize(window, width, height);
        }
    }

    /** Safely fetch the primary monitor's video mode; ensures GLFW is initialized and
     *  tolerates being called before window creation (returns null if no monitor). */
    private static GLFWVidMode primaryVidMode() {
        init();
        long monitor = GLFW.glfwGetPrimaryMonitor();
        if (monitor == MemoryUtil.NULL) return null;
        return GLFW.glfwGetVideoMode(monitor);
    }

    public static int getDesktopWidth() {
        GLFWVidMode vidMode = primaryVidMode();
        return vidMode != null ? vidMode.width() : 1920;
    }

    public static int getDesktopHeight() {
        GLFWVidMode vidMode = primaryVidMode();
        return vidMode != null ? vidMode.height() : 1080;
    }

    public static int getDesktopRefreshRate() {
        GLFWVidMode vidMode = primaryVidMode();
        return vidMode != null ? vidMode.refreshRate() : 60;
    }

    public static int getDesktopBitsPerPixel() {
        GLFWVidMode vidMode = primaryVidMode();
        if (vidMode == null) return 32;
        return vidMode.redBits() + vidMode.greenBits() + vidMode.blueBits();
    }

    /**
     * Square RGBA icons. GLFW reads the pixels through a raw pointer, so heap buffers (what
     * Minecraft.readImageToBuffer returns) are copied into native memory first — passing
     * one directly crashed glfw.dll on Windows. macOS never reaches this (no window icons).
     */
    public static void setIcon(ByteBuffer[] icons) {
        if (icons == null || icons.length == 0) return;
        ByteBuffer[] native_ = new ByteBuffer[icons.length];
        try (GLFWImage.Buffer images = GLFWImage.malloc(icons.length)) {
            for (int i = 0; i < icons.length; i++) {
                ByteBuffer src = icons[i];
                int size = (int) Math.round(Math.sqrt(src.remaining() / 4.0));
                native_[i] = MemoryUtil.memAlloc(size * size * 4);
                native_[i].put(src.duplicate()).flip();
                images.get(i).set(size, size, native_[i]);
            }
            GLFW.glfwSetWindowIcon(window, images);
        } finally {
            for (ByteBuffer b : native_) if (b != null) MemoryUtil.memFree(b);
        }
    }

    public static void setCursorPosition(int x, int y) {
        GLFW.glfwSetCursorPos(window, x, y);
    }

    public static long getHandle() {
        return window;
    }
}
