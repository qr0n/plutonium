package plutonium.gpu;

import net.minecraft.client.renderer.vertex.VertexFormat;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Tripwire for fixed-function ("legacy") GL calls under the core profile.
 *
 * <p>Calls like {@code glVertexPointer} / {@code glEnableClientState} don't exist in a GL 4.1
 * <i>core</i> context. When one is reached on this branch the driver aborts the JVM with a
 * native <i>"FATAL ERROR in native method: ... a function that is not available in the current
 * context was called"</i> — and because it's a native abort (SIGABRT), <b>no Java try/catch can
 * intercept it</b>. The process just dies with exit 134.
 *
 * <p>So the only way to "find crashes like this" is to guard the dangerous entry points and
 * convert them into a logged, recoverable event <i>before</i> the native call fires. Whenever the
 * legacy draw path is reached after the core renderer is up, {@link #reportLegacy} logs a
 * de-duplicated Java stack trace (pinpointing the exact call site) to {@code gl_legacy.log} and
 * stderr, and the caller redirects the draw through the working core path instead of aborting.
 *
 * <p>An empty {@code gl_legacy.log} means nothing slipped past the core dispatch. Any entry is a
 * call site to fix.
 */
public final class GLGuard {
    private GLGuard() {}

    private static final Set<String> seen = Collections.synchronizedSet(new HashSet<String>());
    private static final File LOG = new File("gl_legacy.log");

    /**
     * Record that a fixed-function draw was reached in the core profile. De-duplicated per call
     * site (first occurrence only) so a per-frame offender doesn't spam the log or stderr.
     */
    public static void reportLegacy(VertexFormat fmt, int drawMode, int vertexCount,
                                    boolean multiTexture, boolean shaders) {
        StackTraceElement[] st = new Throwable().getStackTrace();
        // st[0] = here, st[1] = drawLegacy; the real offender is st[2] and up.
        String key = st.length > 2 ? st[2].toString() : "?";
        if (!seen.add(key)) return;

        StringBuilder sb = new StringBuilder();
        sb.append("[GL-LEGACY] fixed-function draw reached in core profile — redirected to core path\n");
        sb.append("  format=").append(fmt)
          .append(" mode=").append(drawMode)
          .append(" verts=").append(vertexCount)
          .append(" multiTexture=").append(multiTexture)
          .append(" shaders=").append(shaders).append('\n');
        for (int i = 1; i < st.length && i < 24; i++) {
            sb.append("    at ").append(st[i]).append('\n');
        }
        String msg = sb.toString();
        System.err.print(msg);

        try (PrintWriter w = new PrintWriter(new FileWriter(LOG, true))) {
            w.println("=== " + new java.util.Date() + " ===");
            w.print(msg);
        } catch (Exception ignored) {
            // logging must never be the thing that takes the client down
        }
    }
}
