package plutonium.gpu;

import java.nio.FloatBuffer;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.system.MemoryUtil;
import plutonium.gpu.math.Matrix4f;

/**
 * Defers entity nametag rendering so every visible name batches into a handful of draws
 * instead of ~3 per entity.
 *
 * <p>Vanilla {@code Render.renderLivingLabel} draws each label inline, interleaved with the
 * entity models, and toggles lighting/blend/colour + issues an immediate Tessellator
 * background quad per label. Each of those is a {@link Batches} flush hook, so a busy scene
 * (40+ players) emits ~40 background draws plus ~80 text draws that can never cross-batch.
 *
 * <p>Instead each label {@link #submit}s its billboard modelview (captured from
 * {@link MatrixStack}) plus its text. {@link #flush} replays them all at the world→GUI
 * boundary, with the shared GL state set exactly once: backgrounds + the see-through "faint"
 * text pass (depth off) accumulate into {@link RectBatch}/{@link FontBatch} and draw together,
 * then the solid depth-tested pass draws together. The captured modelview is baked to eye
 * space by the batchers (drawn under identity modelview, like {@link ModelBatch}), so the
 * labels keep their exact world positions and depth behaviour.
 */
public final class NametagBatch {

    public static boolean ENABLED = true;

    private static final int MAX = 512;
    private static final Matrix4f[] MV = new Matrix4f[MAX];
    private static final String[] TEXT = new String[MAX];
    private static final int[] HALF_WIDTH = new int[MAX];
    private static final int[] Y_OFFSET = new int[MAX];
    private static int count = 0;
    private static final FloatBuffer BUF = MemoryUtil.memAllocFloat(16);
    // World projection captured at submit time. We must render the deferred labels with the
    // SAME projection the entities used (getFOVModifier(true) — includes the sprint FOV bump),
    // not whatever is active at flush: renderHand leaves the base-FOV hand projection bound,
    // which would make sprinting labels appear to pop forward relative to the world.
    private static final Matrix4f PROJ = new Matrix4f();

    static {
        for (int k = 0; k < MAX; k++) MV[k] = new Matrix4f();
    }

    private NametagBatch() {}

    /**
     * Capture one label. Call after the caller has applied its billboard transform to the
     * matrix stack (translate/rotate/scale), so {@link MatrixStack#getModelView()} holds the
     * full eye-space transform. Returns false if batching is off or full — caller should then
     * render inline as a fallback.
     */
    public static boolean submit(String text, int halfWidth, int yOffset) {
        if (!ENABLED || count >= MAX) return false;
        if (count == 0) PROJ.load(MatrixStack.getProjection()); // world projection, same for all labels this frame
        MV[count].load(MatrixStack.getModelView());
        TEXT[count] = text;
        HALF_WIDTH[count] = halfWidth;
        Y_OFFSET[count] = yOffset;
        count++;
        return true;
    }

    public static boolean hasPending() {
        return count > 0;
    }

    /** Render and clear all deferred labels. Must run with the world perspective projection
     *  still active (i.e. before the GUI overlay switches to ortho). */
    public static void flush(FontRenderer font) {
        if (count == 0 || font == null) return;

        int prevMode = MatrixStack.getActiveMode();
        // Restore the world projection the labels were captured under (overriding the hand
        // projection left active by renderWorld). Pushed/popped so we don't disturb the GUI.
        GlStateManager.matrixMode(5889);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        BUF.clear();
        PROJ.store(BUF); // store() flips the buffer itself
        GlStateManager.multMatrix(BUF);

        GlStateManager.matrixMode(5888);
        GlStateManager.pushMatrix();

        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableCull();

        // Pass A — translucent background quad + faint see-through text, depth test off.
        // HALF_WIDTH is the submitter's getStringWidth(s)/2 — reuse it instead of
        // re-walking every string twice per frame here.
        GlStateManager.disableDepth();
        for (int k = 0; k < count; k++) {
            loadModelView(k);
            int j = HALF_WIDTH[k];
            int i = Y_OFFSET[k];
            RectBatch.rect(-j - 1, -1 + i, j + 1, 8 + i, 0x40000000);
            font.drawString(TEXT[k], -j, i, 553648127);
        }
        RectBatch.flush();
        FontBatch.flush();

        // Pass B — solid text, depth-tested so names occlude correctly against the world.
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        for (int k = 0; k < count; k++) {
            loadModelView(k);
            font.drawString(TEXT[k], -HALF_WIDTH[k], Y_OFFSET[k], -1);
        }
        FontBatch.flush();

        GlStateManager.enableCull();
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);

        GlStateManager.popMatrix();          // restore modelview
        GlStateManager.matrixMode(5889);
        GlStateManager.popMatrix();          // restore projection (hand projection)
        GlStateManager.matrixMode(prevMode);
        count = 0;
    }

    private static void loadModelView(int k) {
        GlStateManager.loadIdentity();
        BUF.clear();
        MV[k].store(BUF); // Matrix4f.store() flips the buffer itself — ready to read
        GlStateManager.multMatrix(BUF);
    }
}
