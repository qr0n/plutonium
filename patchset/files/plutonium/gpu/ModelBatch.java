package plutonium.gpu;

import net.minecraft.client.model.ModelBox;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import plutonium.gpu.math.Matrix4f;

/**
 * Batches all the cubes of an entity model into a single draw call.
 *
 * <p>Each {@code ModelRenderer} part normally issues its own draw with its own
 * modelview (head/body/arms/legs each rotated). Instead of one draw per part we
 * accumulate every part's geometry into one buffer, baking each part's current
 * modelview into the vertex positions (eye space). The whole batch is then drawn
 * once with an identity modelview (so {@code u_MVP == projection}).
 *
 * <p>Geometry is emitted pre-triangulated (mode 4 — see
 * {@link net.minecraft.client.renderer.WorldRenderer#putModelTriangles}), so the uploader's
 * quadsToTriangles copy pass never runs for model geometry.
 *
 * <p>The batch is implicit/auto-flushing: it stays open while consecutive parts
 * share the same texture / color / lighting / blend state, and is flushed the
 * moment any of those change or any foreign geometry is drawn. That naturally
 * collapses a 6-part biped (plus each armor layer) into one draw each.
 */
public final class ModelBatch {

    /** Master switch — set false to fall back to one-draw-per-part rendering. */
    public static boolean ENABLED = true;

    private static final WorldRenderer BUFFER = new WorldRenderer(0x200000);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();

    /** Guard so the flush's own draw doesn't recursively re-trigger a flush. */
    public static boolean flushing = false;

    private ModelBatch() {}

    /** Emit every quad of a box into the batch, transformed to eye space by {@code mv}. */
    public static void emitBox(ModelBox box, float scale) {
        if (!BUFFER.isDrawing()) {
            // Mode 4, not 7: emitTransformed writes each face already triangulated, so the
            // uploader's quadsToTriangles pass is skipped (see WorldRenderer.putModelTriangles).
            BUFFER.begin(4, DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL);
        }
        Matrix4f mv = MatrixStack.getModelView();
        box.emitTransformed(BUFFER, scale, mv);
    }

    /** Draw whatever is accumulated, using an identity modelview. Cheap no-op when empty. */
    public static void flush() {
        if (flushing || !BUFFER.isDrawing()) return;
        flushing = true;
        long _t0 = System.nanoTime();
        try {
            // finishDrawing() clears the buffer's isDrawing flag (uploader.draw only calls
            // reset(), which does not) — mirrors Tessellator.draw().
            BUFFER.finishDrawing();
            if (BUFFER.getVertexCount() > 0) {
                DrawStats.modelFlushes++;
                // u_MVP is built as projection * modelview; positions are already in eye
                // space, so render them under an identity modelview. Save/restore the active
                // matrix mode — flush can fire mid-call from any GL state change, and leaving
                // the wrong stack active corrupts the caller's push/pop balance.
                int prevMode = MatrixStack.getActiveMode();
                GlStateManager.matrixMode(5888);
                GlStateManager.pushMatrix();
                GlStateManager.loadIdentity();
                UPLOADER.func_181679_a(BUFFER);   // resets BUFFER
                GlStateManager.popMatrix();
                GlStateManager.matrixMode(prevMode);
            } else {
                BUFFER.reset();
            }
        } finally {
            flushing = false;
            DrawStats.nsModelFlush += System.nanoTime() - _t0;
        }
    }
}
