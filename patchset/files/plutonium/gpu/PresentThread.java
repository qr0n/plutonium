package plutonium.gpu;

import java.util.concurrent.CountDownLatch;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/**
 * Present thread: owns the VISIBLE window's GL context (Cw) and does the
 * blocking
 * glfwSwapBuffers off the main thread, so CPU submit (main) and GPU present
 * overlap.
 *
 * The main thread runs the whole game under the HIDDEN window's context (Ch),
 * rendering
 * into the offscreen framebufferMc FBO (double-buffered). Only the framebuffer
 * COLOR TEXTURE
 * (shared across the context share group) and a fence sync object cross between
 * threads —
 * never world state — so it's race-free.
 *
 * See ~/.claude/plans/sequential-jumping-dragonfly.md for the full design.
 *
 * Per-frame protocol (the 2-buffer safety argument):
 * - Main flips framebufferMc to the buffer NOT in flight, renders frame N into
 * it, glFenceSync + glFlush, then submit(textureN, ...).
 * - submit() BLOCKS while a frame is still queued (frameAvailable). The present
 * thread holds the slot (frameAvailable stays true) for the ENTIRE blit+swap
 * and
 * only clears it AFTER the swap completes. So the buffer it is reading cannot
 * be
 * reused by main (2 frames later, via the parity flip) until the read is fully
 * done. Clearing the slot before the blit would race: main could overwrite the
 * buffer mid-blit → torn texels on the last-drawn content.
 * - Present: glClientWaitSync(fence) → glBlitFramebuffer(shared tex → default
 * fb) → swap → release slot.
 */
public class PresentThread {

    /**
     * Master switch — instant A/B revert. false = original synchronous render+swap
     * path.
     */
    public static boolean ENABLED = true;

    private static Thread thread;
    private static volatile boolean running = false;
    private static long presentWindow = 0L;
    private static CountDownLatch ready;

    // --- mailbox (1-slot) + pause/quiesce, all guarded by mailboxLock ---
    private static final Object mailboxLock = new Object();
    private static boolean frameAvailable = false;
    private static int mTex, mSrcW, mSrcH, mDstW, mDstH;
    private static long mFence;
    // resize/teardown coordination: park the present thread between blits
    private static boolean pauseRequested = false;
    private static boolean paused = false;

    // present-side READ framebuffer wrapping the shared color texture (created in
    // Cw)
    private static int readFbo = 0;
    public static boolean DEBUG = false;
    private static long blitCount = 0;

    // perf: last blit time (ns), read by main for the GPU-DBG line
    public static volatile long lastBlitNs = 0;

    /**
     * Hand the visible window's context to a new present thread. MUST be called
     * when no
     * thread holds Cw current (in the ENABLED path, main never makes the visible
     * window
     * current). Blocks until the present thread has the context current and is
     * looping.
     */
    public static void start(long window) {
        if (!ENABLED || running)
            return;
        presentWindow = window;
        running = true;
        ready = new CountDownLatch(1);
        thread = new Thread(PresentThread::run, "Plutonium-Present");
        thread.setDaemon(true);
        thread.start();
        try {
            ready.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Hand frame N to the present thread. Called by the main thread after it has
     * rendered
     * into the FBO, fenced, and flushed. Blocks while a previous frame is still
     * queued so at
     * most one frame is ever in flight (bounds latency to +1 frame, prevents
     * drift).
     *
     * @param texture   shared color texture name (valid in Cw via the share group)
     * @param srcW/srcH the FBO size (blit source rect)
     * @param dstW/dstH the default-framebuffer size (blit dest rect; handles Retina
     *                  upscale)
     * @param fence     glFenceSync handle created in Ch after the frame's GL
     *                  commands
     */
    public static void submit(int texture, int srcW, int srcH, int dstW, int dstH, long fence) {
        while (running) {
            synchronized (mailboxLock) {
                if (!frameAvailable) {
                    mTex = texture;
                    mSrcW = srcW;
                    mSrcH = srcH;
                    mDstW = dstW;
                    mDstH = dstH;
                    mFence = fence;
                    frameAvailable = true;
                    mailboxLock.notifyAll();
                    return;
                }
            }
            // Present thread still owns the previous frame's buffer (mid blit/swap). Pump
            // OS
            // events while we wait so its in-flight glfwSwapBuffers can complete: macOS
            // services
            // the GL flush via the main-thread event loop, so blocking here WITHOUT pumping
            // deadlocks (main waits for the slot; the swap waits for main to pump).
            WindowManager.pumpEvents();
            try {
                Thread.sleep(0, 200_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Park the present thread between blits and block until it is idle. After this
     * returns,
     * the present thread is guaranteed not to be mid-blit and will not start
     * another blit
     * until endCriticalSection(). Use around framebuffer recreation (resize) so the
     * shared
     * texture isn't deleted while the present thread is reading it.
     */
    public static void beginCriticalSection() {
        if (!running)
            return;
        synchronized (mailboxLock) {
            pauseRequested = true;
            mailboxLock.notifyAll();
        }
        // Wait for the present thread to park between blits — but keep pumping OS
        // events so any
        // in-flight glfwSwapBuffers can complete (same macOS deadlock as submit()).
        while (running) {
            synchronized (mailboxLock) {
                if (paused)
                    return;
            }
            WindowManager.pumpEvents();
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public static void endCriticalSection() {
        synchronized (mailboxLock) {
            pauseRequested = false;
            mailboxLock.notifyAll();
        }
    }

    private static void run() {
        GLFW.glfwMakeContextCurrent(presentWindow);
        GL.createCapabilities();
        GLFW.glfwSwapInterval(0);
        readFbo = GL30.glGenFramebuffers();
        System.err.println("[PRESENT] visible context (Cw) is current on present thread; entering loop");
        System.err.flush();
        ready.countDown();

        while (running) {
            int tex, srcW, srcH, dstW, dstH;
            long fence;
            synchronized (mailboxLock) {
                // honor a pause request between frames (resize/teardown)
                while (pauseRequested && running) {
                    paused = true;
                    mailboxLock.notifyAll();
                    try {
                        mailboxLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                paused = false;
                // Wait for a frame, but also bail out if a pause is requested mid-wait so we
                // loop back to the park above (else beginCriticalSection would spin forever
                // waiting for `paused`).
                while (!frameAvailable && running && !pauseRequested) {
                    try {
                        mailboxLock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (!running)
                    break;
                if (pauseRequested)
                    continue;
                tex = mTex;
                srcW = mSrcW;
                srcH = mSrcH;
                dstW = mDstW;
                dstH = mDstH;
                fence = mFence;
                // NOTE: frameAvailable is intentionally NOT cleared here. We hold the slot for
                // the whole blit+swap so the main thread cannot reuse this buffer (2 frames
                // later, via the parity flip) while we are still reading it. Releasing before
                // the blit raced: main would render frame N+2 into buffer A while we blit
                // buffer A from frame N → torn texels (garbage on the last-drawn text). The
                // fence only orders frame N's own rendering, not a future overwrite, so the
                // mailbox must provide the mutual exclusion. Released after the swap below.
            }

            long b0 = System.nanoTime();
            if (fence != 0L) {
                // Wait for the main thread's GPU commands for this frame to complete.
                // Main already glFlush'd, so a plain wait (flags=0) observes completion.
                GL32.glClientWaitSync(fence, 0, 1_000_000_000L /* 1s */);
                GL32.glDeleteSync(fence);
            }

            // Blit the shared color texture straight to the visible default framebuffer.
            // Both framebuffers use GL's bottom-left origin, so an un-flipped blit is
            // upright.
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex,
                    0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, 0);
            GL30.glBlitFramebuffer(0, 0, srcW, srcH, 0, 0, dstW, dstH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

            if (DEBUG && (blitCount % 120 == 0)) {
                int rstat = GL30.glCheckFramebufferStatus(GL30.GL_READ_FRAMEBUFFER);
                // Read back a center pixel from the READ FBO (the shared texture as seen from
                // Cw).
                java.nio.ByteBuffer px = org.lwjgl.BufferUtils.createByteBuffer(4);
                GL11.glReadPixels(srcW / 2, srcH / 2, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
                int err = GL11.glGetError();
                System.err.println("[PRESENT-DBG] tex=" + tex + " src=" + srcW + "x" + srcH + " dst=" + dstW + "x"
                        + dstH
                        + " readFboComplete=" + (rstat == GL30.GL_FRAMEBUFFER_COMPLETE) + "(" + rstat + ") glErr=" + err
                        + " centerPx=[" + (px.get(0) & 0xFF) + "," + (px.get(1) & 0xFF) + "," + (px.get(2) & 0xFF) + ","
                        + (px.get(3) & 0xFF) + "]");
                System.err.flush();
            }
            blitCount++;

            GLFW.glfwSwapBuffers(presentWindow);
            lastBlitNs = System.nanoTime() - b0;

            // Blit + swap done: the buffer we read is now free. Release the slot so the
            // main
            // thread may submit (and eventually reuse) it.
            synchronized (mailboxLock) {
                frameAvailable = false;
                mailboxLock.notifyAll();
            }
        }

        if (readFbo != 0) {
            GL30.glDeleteFramebuffers(readFbo);
            readFbo = 0;
        }
        GLFW.glfwMakeContextCurrent(0L);
    }

    /** Stop the present thread and wait for it to release the context. */
    public static void stop() {
        if (!running)
            return;
        running = false;
        synchronized (mailboxLock) {
            mailboxLock.notifyAll();
        }
        if (thread != null) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static boolean isRunning() {
        return running;
    }
}
