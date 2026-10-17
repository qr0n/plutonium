package plutonium.gpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import plutonium.gpu.math.Matrix4f;

/**
 * Batches all arrow geometry (flying arrows + arrows stuck in players) into one deferred
 * draw. Vanilla {@code RenderArrow.doRender} issues SIX immediate position_tex draws per
 * arrow per frame — a player peppered with arrows costs dozens of draws (the immW tex
 * 14→122/frame spike found by {@link TexDrawProbe}).
 *
 * <p>Quads are baked to eye space with the modelview active at emit time (same trick as
 * {@link ModelBatch}) and drawn once, under an identity modelview, before renderHand —
 * while the world projection is still bound, so no projection capture is needed. All
 * arrows share one texture and the unlit position_tex program, so ordering against other
 * opaque geometry is depth-handled. Known (accepted) artifact: like all end-of-world-pass
 * deferrals, arrows behind translucent water draw after it and lose the water tint.
 *
 * <p>Only active inside renderEntities ({@link Batches#entityPassActive}).
 */
public final class ArrowBatch {

    public static boolean ENABLED = true;

    private static final ResourceLocation TEXTURE = new ResourceLocation("textures/entity/arrow.png");
    private static final WorldRenderer BUFFER = new WorldRenderer(0x40000);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    public static boolean flushing = false;

    private ArrowBatch() {}

    /** True when arrow quads should be deferred instead of drawn inline. */
    public static boolean active() {
        return ENABLED && Batches.entityPassActive;
    }

    /** Emit one arrow quad, baking {@code mv} into the positions (eye space). */
    public static void quad(Matrix4f mv,
                            float x0, float y0, float z0, float u0, float v0,
                            float x1, float y1, float z1, float u1, float v1,
                            float x2, float y2, float z2, float u2, float v2,
                            float x3, float y3, float z3, float u3, float v3) {
        if (!BUFFER.isDrawing()) {
            // Mode 4: quads are emitted already triangulated, so the uploader's
            // quadsToTriangles pass is skipped (see WorldRenderer.putTexTriangles).
            BUFFER.begin(4, DefaultVertexFormats.POSITION_TEX);
        }
        DrawStats.arrowQuads++;
        BUFFER.putTexTriangles(
            mv.m00 * x0 + mv.m10 * y0 + mv.m20 * z0 + mv.m30, mv.m01 * x0 + mv.m11 * y0 + mv.m21 * z0 + mv.m31, mv.m02 * x0 + mv.m12 * y0 + mv.m22 * z0 + mv.m32, u0, v0,
            mv.m00 * x1 + mv.m10 * y1 + mv.m20 * z1 + mv.m30, mv.m01 * x1 + mv.m11 * y1 + mv.m21 * z1 + mv.m31, mv.m02 * x1 + mv.m12 * y1 + mv.m22 * z1 + mv.m32, u1, v1,
            mv.m00 * x2 + mv.m10 * y2 + mv.m20 * z2 + mv.m30, mv.m01 * x2 + mv.m11 * y2 + mv.m21 * z2 + mv.m31, mv.m02 * x2 + mv.m12 * y2 + mv.m22 * z2 + mv.m32, u2, v2,
            mv.m00 * x3 + mv.m10 * y3 + mv.m20 * z3 + mv.m30, mv.m01 * x3 + mv.m11 * y3 + mv.m21 * z3 + mv.m31, mv.m02 * x3 + mv.m12 * y3 + mv.m22 * z3 + mv.m32, u3, v3);
    }

    /** Draw everything accumulated this frame. Must run with the world projection active
     *  (called before renderHand). Cheap no-op when empty. */
    public static void flush() {
        if (flushing || !BUFFER.isDrawing()) return;
        flushing = true;
        try {
            BUFFER.finishDrawing();
            if (BUFFER.getVertexCount() > 0) {
                // State setters below may trigger Batches.flushAll (draining Model/Font
                // batches first — correct: models under the world, arrows on top). This
                // batch is intentionally NOT registered in flushAll, so no re-entrancy.
                Minecraft.getMinecraft().getTextureManager().bindTexture(TEXTURE);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                GlStateManager.disableBlend();
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(516, 0.1F);
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                // position_tex is unlit — lighting state is irrelevant to this draw, so
                // leave it as the world pass set it rather than toggling (each toggle is
                // a flushAll hook).
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
            flushing = false;
        }
    }
}
