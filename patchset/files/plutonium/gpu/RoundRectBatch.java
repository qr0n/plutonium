package plutonium.gpu;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import org.lwjgl.BufferUtils;
import plutonium.gpu.math.Matrix4f;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.FloatBuffer;

/**
 * Anti-aliased rounded rectangles / rings / soft shadows, drawn through a single SDF
 * shader pass per flush. Each instance is a quad (6 verts) carrying its own size, corner
 * radius, edge softness and colour; the fragment shader evaluates a rounded-box signed
 * distance for smooth, properly rounded edges — the thing the old 7×{@code drawRect}
 * corner-cut in DrawUtils could never do.
 *
 * Mirrors {@link RectBatch}: vertex positions are baked to eye space at add time and the
 * batch draws under the GUI projection only, so it slots into {@link Batches#flushAll()}
 * between RectBatch and FontBatch (panels under text). On screens (no batching) DrawUtils
 * flushes immediately so glScissor clipping and draw order are preserved.
 *
 * Self-disables on the first GL error / exception so a broken driver path falls back to the
 * legacy corner-cut rounding in DrawUtils. The known per-frame GL errors on this branch are
 * drained before the pass so the post-pass check only sees our own errors (see BlurFx).
 */
public final class RoundRectBatch {

    public static boolean ENABLED = true;
    public static boolean flushing = false;

    private static final int FLOATS_PER_VERT = 18; // pos3 + local2 + half2 + params3 + fill4 + border4
    private static final int VERTS_PER_RECT  = 6;
    private static final int MAX_RECTS       = 1024;

    private static FloatBuffer buffer; // CPU staging
    private static int count = 0;      // instances queued

    private static boolean inited = false;
    private static boolean failed = false;
    private static ShaderProgram program;

    private static final FloatBuffer MVP = BufferUtils.createFloatBuffer(16);
    private static final GlStateManager.BlendSnapshot SAVED_BLEND = new GlStateManager.BlendSnapshot();

    private RoundRectBatch() {}

    /** True while the SDF path is usable; DrawUtils falls back to legacy rounding otherwise. */
    public static boolean isAvailable() { return ENABLED && !failed; }

    /** Filled rounded rectangle. */
    public static void addRect(float x, float y, float w, float h, float radius, float softness, int fillArgb) {
        addInstance(x, y, w, h, radius, softness, 0f, fillArgb, 0);
    }

    /** 1px-or-wider rounded outline. */
    public static void addBorder(float x, float y, float w, float h, float radius, float softness,
                                 float borderW, int borderArgb) {
        addInstance(x, y, w, h, radius, softness, Math.max(0.6f, borderW), 0, borderArgb);
    }

    private static void addInstance(float x, float y, float w, float h, float radius, float softness,
                                    float borderW, int fillArgb, int borderArgb) {
        if (!isAvailable() || w <= 0 || h <= 0) return;
        if (buffer == null) buffer = BufferUtils.createFloatBuffer(MAX_RECTS * VERTS_PER_RECT * FLOATS_PER_VERT);
        if (count >= MAX_RECTS) flush();

        float hw = w * 0.5f, hh = h * 0.5f;
        radius = Math.max(0f, Math.min(radius, Math.min(hw, hh)));
        float aa = Math.max(softness, 0.6f);
        float margin = aa + 1f; // room past the rect bounds for the AA / shadow falloff
        float cx = x + hw, cy = y + hh;
        float lx = hw + margin, ly = hh + margin;

        Matrix4f mv = MatrixStack.getModelView();

        float fr = ((fillArgb >> 16) & 255) / 255f, fg = ((fillArgb >> 8) & 255) / 255f;
        float fb = (fillArgb & 255) / 255f,         fa = ((fillArgb >>> 24) & 255) / 255f;
        float br = ((borderArgb >> 16) & 255) / 255f, bg = ((borderArgb >> 8) & 255) / 255f;
        float bb = (borderArgb & 255) / 255f,         ba = ((borderArgb >>> 24) & 255) / 255f;

        // two triangles (BL, BR, TR) + (BL, TR, TL)
        emit(mv, cx, cy, -lx, -ly, hw, hh, radius, aa, borderW, fr, fg, fb, fa, br, bg, bb, ba);
        emit(mv, cx, cy,  lx, -ly, hw, hh, radius, aa, borderW, fr, fg, fb, fa, br, bg, bb, ba);
        emit(mv, cx, cy,  lx,  ly, hw, hh, radius, aa, borderW, fr, fg, fb, fa, br, bg, bb, ba);
        emit(mv, cx, cy, -lx, -ly, hw, hh, radius, aa, borderW, fr, fg, fb, fa, br, bg, bb, ba);
        emit(mv, cx, cy,  lx,  ly, hw, hh, radius, aa, borderW, fr, fg, fb, fa, br, bg, bb, ba);
        emit(mv, cx, cy, -lx,  ly, hw, hh, radius, aa, borderW, fr, fg, fb, fa, br, bg, bb, ba);
        count++;
    }

    private static void emit(Matrix4f mv, float cx, float cy, float lx, float ly,
                             float hw, float hh, float radius, float aa, float borderW,
                             float fr, float fg, float fb, float fa,
                             float br, float bg, float bb, float ba) {
        float px = cx + lx, py = cy + ly;
        float ex = mv.m00 * px + mv.m10 * py + mv.m30;
        float ey = mv.m01 * px + mv.m11 * py + mv.m31;
        float ez = mv.m02 * px + mv.m12 * py + mv.m32;
        buffer.put(ex).put(ey).put(ez);              // aPos
        buffer.put(lx).put(ly);                      // aLocal
        buffer.put(hw).put(hh);                      // aHalf
        buffer.put(radius).put(aa).put(borderW);     // aParams
        buffer.put(fr).put(fg).put(fb).put(fa);      // aFill
        buffer.put(br).put(bg).put(bb).put(ba);      // aBorder
    }

    public static void flush() {
        if (flushing || count == 0) return;
        flushing = true;
        try {
            if (!inited) init();
            if (failed) { buffer.clear(); count = 0; return; }

            buffer.flip();
            int verts = count * VERTS_PER_RECT;

            GlStateManager.getBlendState(SAVED_BLEND);
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            // GUI rendering runs with GL_CULL_FACE enabled; our two-triangle quads are wound
            // the opposite way from the mode-7 quads WorldVertexBufferUploader emits, so they
            // would be back-face culled (drawing nothing). Disable cull for the 2D pass.
            GlStateManager.disableCull();

            // Modelview is baked into aPos; push a projection-only MVP out of band and
            // poison the sync cache so the next regular draw restores the real MVP.
            MVP.clear();
            MatrixStack.storeProjection(MVP);
            MVP.position(0);
            plutonium.gpu.vk.VkUniformBlock.setMVPDirect(MVP);
            ShaderProgram.invalidateMvpSync();

            plutonium.gpu.vk.VkDrawDispatch.drawRoundRect(buffer, verts);

            ShaderProgram.invalidateMvpSync();
            GlStateManager.enableCull(); // restore GUI default
            GlStateManager.setBlendState(SAVED_BLEND);
        } catch (Throwable t) {
            failed = true;
            System.err.println("[Plutonium] RoundRectBatch disabled: " + t);
        } finally {
            if (buffer != null) buffer.clear();
            count = 0;
            flushing = false;
        }
    }

    private static void init() {
        inited = true;
        // The roundrect pipeline + vertex layout live in VkPipelines (PROG_ROUNDRECT /
        // FORMAT_ROUNDRECT); nothing to create here.
        program = CoreShaders.getRoundRect();
    }

    private static String read(String path) {
        try {
            InputStream is = RoundRectBatch.class.getResourceAsStream(path);
            if (is == null) throw new RuntimeException("Shader not found: " + path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line).append('\n');
            reader.close();
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read shader " + path, e);
        }
    }
}
