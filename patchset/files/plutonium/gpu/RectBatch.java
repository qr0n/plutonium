package plutonium.gpu;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import plutonium.gpu.math.Matrix4f;

/**
 * Batches solid-colour rectangles ({@code Gui.drawRect}) into one alpha-blended draw.
 * GUIs draw many of these in a row (scoreboard, chat and tab-list line backgrounds);
 * vanilla toggles blend + binds no texture and issues one draw each. Here they accumulate
 * as POSITION_COLOR (per-vertex colour, modelview baked into positions) and draw together.
 * Crucially this stops a rect from flushing the {@link FontBatch} between text lines, so GUI
 * text cross-batches too. Flushed by the shared hooks on any texture/foreign-geometry change.
 */
public final class RectBatch {

    public static boolean ENABLED = true;

    private static final WorldRenderer BUFFER = new WorldRenderer(0x40000);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    private static final GlStateManager.BlendSnapshot SAVED_BLEND = new GlStateManager.BlendSnapshot();
    public static boolean flushing = false;

    private RectBatch() {}

    /** Add an axis-aligned rect (screen space) with an ARGB colour. */
    public static void rect(float left, float top, float right, float bottom, int argb) {
        if (!BUFFER.isDrawing()) {
            // Mode 4: rects are emitted already triangulated, so the uploader's
            // quadsToTriangles pass is skipped (see WorldRenderer.putColorTriangles).
            BUFFER.begin(4, DefaultVertexFormats.POSITION_COLOR);
        }
        byte ba = (byte) (argb >> 24 & 255);
        byte br = (byte) (argb >> 16 & 255);
        byte bg = (byte) (argb >> 8 & 255);
        byte bb = (byte) (argb & 255);
        Matrix4f mv = MatrixStack.getModelView();
        // quad corners BL, BR, TR, TL (matching Gui.drawRect); putColorTriangles fans them
        BUFFER.putColorTriangles(
            mv.m00 * left  + mv.m10 * bottom + mv.m30, mv.m01 * left  + mv.m11 * bottom + mv.m31, mv.m02 * left  + mv.m12 * bottom + mv.m32,
            mv.m00 * right + mv.m10 * bottom + mv.m30, mv.m01 * right + mv.m11 * bottom + mv.m31, mv.m02 * right + mv.m12 * bottom + mv.m32,
            mv.m00 * right + mv.m10 * top    + mv.m30, mv.m01 * right + mv.m11 * top    + mv.m31, mv.m02 * right + mv.m12 * top    + mv.m32,
            mv.m00 * left  + mv.m10 * top    + mv.m30, mv.m01 * left  + mv.m11 * top    + mv.m31, mv.m02 * left  + mv.m12 * top    + mv.m32,
            br, bg, bb, ba);
    }

    public static void flush() {
        if (flushing || !BUFFER.isDrawing()) return;
        flushing = true;
        try {
            BUFFER.finishDrawing();
            if (BUFFER.getVertexCount() > 0) {
                // drawRect renders with standard alpha blend and no texture; preserve the
                // caller's blend state across the deferred draw.
                GlStateManager.getBlendState(SAVED_BLEND);
                GlStateManager.enableBlend();
                GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                GlStateManager.disableTexture2D();
                int prevMode = MatrixStack.getActiveMode();
                GlStateManager.matrixMode(5888);
                GlStateManager.pushMatrix();
                GlStateManager.loadIdentity();
                UPLOADER.func_181679_a(BUFFER);
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(prevMode);
                GlStateManager.enableTexture2D();
                GlStateManager.setBlendState(SAVED_BLEND);
            } else {
                BUFFER.reset();
            }
        } finally {
            flushing = false;
        }
    }
}
