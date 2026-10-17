package plutonium.gpu;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import plutonium.gpu.math.Matrix4f;

/**
 * Batches font glyphs across strings into a single draw. Glyphs use POSITION_TEX_COLOR
 * (per-vertex colour) and have the current modelview baked into their positions, so text
 * at different colours AND different 3D/GUI positions (every player nametag, every
 * scoreboard/chat line) all accumulate into one buffer and draw together. Flushed on font
 * page (texture) change or whenever any other geometry / state change occurs.
 */
public final class FontBatch {

    public static boolean ENABLED = true;

    private static final WorldRenderer BUFFER = new WorldRenderer(0x200000);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    public static boolean flushing = false;

    /** Identity for the currently-batched font page; null when no batch is open. */
    private static Object page = null;

    private FontBatch() {}

    /** Begin/continue a batch for the given font page; flushes first if the page changed. */
    public static void begin(Object texturePage) {
        if (BUFFER.isDrawing() && texturePage != page) {
            flush();
        }
        if (!BUFFER.isDrawing()) {
            // Mode 4: glyphs are emitted already triangulated, so the uploader's
            // quadsToTriangles pass is skipped (see WorldRenderer.putGlyphTriangles).
            BUFFER.begin(4, DefaultVertexFormats.POSITION_TEX_COLOR);
            page = texturePage;
        }
    }

    /** Emit one glyph quad (4 corners), baking the current modelview into positions. */
    public static void glyph(float x0, float y0, float u0, float v0,
                             float x1, float y1, float u1, float v1,
                             float x2, float y2, float u2, float v2,
                             float x3, float y3, float u3, float v3,
                             float r, float g, float b, float a) {
        if (GlyphCache.isRecording()) {
            GlyphCache.record(page, x0, y0, u0, v0, x1, y1, u1, v1,
                              x2, y2, u2, v2, x3, y3, u3, v3, r, g, b);
        }
        Matrix4f mv = MatrixStack.getModelView();
        // One absolute 144-byte write per quad.
        byte br = (byte) (int) (r * 255.0F), bg = (byte) (int) (g * 255.0F);
        byte bb = (byte) (int) (b * 255.0F), ba = (byte) (int) (a * 255.0F);
        BUFFER.putGlyphTriangles(
            mv.m00 * x0 + mv.m10 * y0 + mv.m30, mv.m01 * x0 + mv.m11 * y0 + mv.m31,
            mv.m02 * x0 + mv.m12 * y0 + mv.m32, u0, v0,
            mv.m00 * x1 + mv.m10 * y1 + mv.m30, mv.m01 * x1 + mv.m11 * y1 + mv.m31,
            mv.m02 * x1 + mv.m12 * y1 + mv.m32, u1, v1,
            mv.m00 * x2 + mv.m10 * y2 + mv.m30, mv.m01 * x2 + mv.m11 * y2 + mv.m31,
            mv.m02 * x2 + mv.m12 * y2 + mv.m32, u2, v2,
            mv.m00 * x3 + mv.m10 * y3 + mv.m30, mv.m01 * x3 + mv.m11 * y3 + mv.m31,
            mv.m02 * x3 + mv.m12 * y3 + mv.m32, u3, v3,
            br, bg, bb, ba);
    }

    /**
     * Bulk replay lane for {@link GlyphCache}: emit a whole recorded run of quads at
     * (ox, oy), hoisting the modelview fetch and base-colour conversion out
     * of the per-quad path. Data layout is {@link GlyphCache}'s STRIDE-float record:
     * 16 pos/uv floats, rgb, base-colour flag.
     */
    public static void glyphRun(float[] d, int len, float ox, float oy,
                                float r, float g, float b, float a) {
        Matrix4f mv = MatrixStack.getModelView();
        byte baseR = (byte) (int) (r * 255.0F), baseG = (byte) (int) (g * 255.0F);
        byte baseB = (byte) (int) (b * 255.0F), ba = (byte) (int) (a * 255.0F);
        for (int i = 0; i < len; i += GlyphCache.STRIDE) {
            byte br, bg, bb;
            if (d[i + 19] != 0.0f) {
                br = baseR; bg = baseG; bb = baseB;
            } else {
                br = (byte) (int) (d[i + 16] * 255.0F);
                bg = (byte) (int) (d[i + 17] * 255.0F);
                bb = (byte) (int) (d[i + 18] * 255.0F);
            }
            float x0 = ox + d[i],      y0 = oy + d[i + 1];
            float x1 = ox + d[i + 4],  y1 = oy + d[i + 5];
            float x2 = ox + d[i + 8],  y2 = oy + d[i + 9];
            float x3 = ox + d[i + 12], y3 = oy + d[i + 13];
            BUFFER.putGlyphTriangles(
                mv.m00 * x0 + mv.m10 * y0 + mv.m30, mv.m01 * x0 + mv.m11 * y0 + mv.m31,
                mv.m02 * x0 + mv.m12 * y0 + mv.m32, d[i + 2], d[i + 3],
                mv.m00 * x1 + mv.m10 * y1 + mv.m30, mv.m01 * x1 + mv.m11 * y1 + mv.m31,
                mv.m02 * x1 + mv.m12 * y1 + mv.m32, d[i + 6], d[i + 7],
                mv.m00 * x2 + mv.m10 * y2 + mv.m30, mv.m01 * x2 + mv.m11 * y2 + mv.m31,
                mv.m02 * x2 + mv.m12 * y2 + mv.m32, d[i + 10], d[i + 11],
                mv.m00 * x3 + mv.m10 * y3 + mv.m30, mv.m01 * x3 + mv.m11 * y3 + mv.m31,
                mv.m02 * x3 + mv.m12 * y3 + mv.m32, d[i + 14], d[i + 15],
                br, bg, bb, ba);
        }
    }

    public static boolean isOpen() { return BUFFER.isDrawing(); }

    public static void flush() {
        if (flushing || !BUFFER.isDrawing()) return;
        flushing = true;
        try {
            BUFFER.finishDrawing();
            if (BUFFER.getVertexCount() > 0) {
                DrawStats.fontQuads += BUFFER.getVertexCount() / 6;   // mode 4: 6 verts per quad
                int prevMode = MatrixStack.getActiveMode();
                GlStateManager.matrixMode(5888);
                GlStateManager.pushMatrix();
                GlStateManager.loadIdentity();
                UPLOADER.func_181679_a(BUFFER);
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(prevMode);
            } else {
                BUFFER.reset();
            }
        } finally {
            page = null;
            flushing = false;
        }
    }
}
