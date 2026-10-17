package plutonium.gpu;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import plutonium.gpu.math.Matrix4f;

/**
 * Batches consecutive {@code Gui.drawTexturedModalRect} calls (POSITION_TEX quads) into a
 * single draw. The HUD issues dozens of these in a row sharing one texture and white color
 * (hearts, armour, hunger, hotbar) — normally one draw each. Like {@link ModelBatch}, vertex
 * positions are baked to eye space and the batch is drawn under an identity modelview, then
 * flushed the moment the texture / color / blend state changes or any foreign geometry draws.
 */
public final class GuiQuadBatch {

    public static boolean ENABLED = true;

    private static final WorldRenderer BUFFER = new WorldRenderer(0x40000);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    public static boolean flushing = false;

    private GuiQuadBatch() {}

    /** Add a textured quad (4 corners, in current GUI space) to the batch. */
    public static void quad(double x0, double y0, double u0, double v0,
                            double x1, double y1, double u1, double v1,
                            double x2, double y2, double u2, double v2,
                            double x3, double y3, double u3, double v3, double z) {
        if (!BUFFER.isDrawing()) {
            // Mode 4: quads are emitted already triangulated, so the uploader's
            // quadsToTriangles pass is skipped (see WorldRenderer.putTexTriangles).
            BUFFER.begin(4, DefaultVertexFormats.POSITION_TEX);
        }
        Matrix4f mv = MatrixStack.getModelView();
        float fz = (float) z;
        BUFFER.putTexTriangles(
            ex(mv, x0, y0, fz), ey(mv, x0, y0, fz), ez(mv, x0, y0, fz), (float) u0, (float) v0,
            ex(mv, x1, y1, fz), ey(mv, x1, y1, fz), ez(mv, x1, y1, fz), (float) u1, (float) v1,
            ex(mv, x2, y2, fz), ey(mv, x2, y2, fz), ez(mv, x2, y2, fz), (float) u2, (float) v2,
            ex(mv, x3, y3, fz), ey(mv, x3, y3, fz), ez(mv, x3, y3, fz), (float) u3, (float) v3);
    }

    private static float ex(Matrix4f mv, double x, double y, float z) {
        return mv.m00 * (float) x + mv.m10 * (float) y + mv.m20 * z + mv.m30;
    }
    private static float ey(Matrix4f mv, double x, double y, float z) {
        return mv.m01 * (float) x + mv.m11 * (float) y + mv.m21 * z + mv.m31;
    }
    private static float ez(Matrix4f mv, double x, double y, float z) {
        return mv.m02 * (float) x + mv.m12 * (float) y + mv.m22 * z + mv.m32;
    }

    public static void flush() {
        if (flushing || !BUFFER.isDrawing()) return;
        flushing = true;
        try {
            BUFFER.finishDrawing();
            if (BUFFER.getVertexCount() > 0) {
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
