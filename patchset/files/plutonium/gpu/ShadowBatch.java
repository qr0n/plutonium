package plutonium.gpu;

import java.nio.FloatBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.system.MemoryUtil;
import plutonium.gpu.math.Matrix4f;

/**
 * Batches every entity drop-shadow in a frame into a single draw.
 *
 * <p>Vanilla {@code Render.renderShadow} draws each entity's shadow inline, interleaved with the
 * entity models, wrapped in its own {@code enableBlend / bindTexture / depthMask(false) / draw /
 * disableBlend / depthMask(true)}. On a busy scene (30–40 players) that is 30–40 draw calls for
 * quads that all share one texture and one GL state. Worse, the per-entity
 * {@code enableBlend}/{@code disableBlend} are unconditional {@link Batches#flushAll()} hooks, so
 * with rendering ordered model→shadow→model→shadow the {@link ModelBatch} is force-flushed after
 * every entity — preventing it from spanning the entity list.
 *
 * <p>Instead each shadow's quads are accumulated here (in camera-relative world space, exactly as
 * {@code renderShadowBlock} emits them — no per-vertex transform), and {@link #flush} replays them
 * all at the world→GUI boundary with the GL state set exactly once. Every shadow in the frame
 * shares the camera-view modelview (the per-entity matrix push lives in {@code doRender}, which has
 * already returned by the time shadows render), so we capture that modelview + the world projection
 * once and restore them at flush — like {@link NametagBatch}, but simpler because there is a single
 * shared transform rather than a per-item billboard matrix.
 *
 * <p>Unlike {@link ModelBatch}/{@link GuiQuadBatch} this batch is NOT a member of
 * {@link Batches#flushAll()}: all shadows share one texture/state, so unrelated state changes
 * during the entity loop must NOT flush it (that would re-introduce one draw per entity). It is
 * drained only by the explicit {@link #flush} at the boundary, exactly like {@link NametagBatch}.
 */
public final class ShadowBatch {

    public static boolean ENABLED = true;

    /** Master switch for drawing entity drop-shadows at all (batched OR inline). Off: shadows are
     *  a minor visual, and skipping them drops ~1 draw/entity AND removes the per-entity blend
     *  toggles that were splitting ModelBatch — so the entity models batch across the whole list. */
    public static boolean RENDER_SHADOWS = false;

    private static final ResourceLocation SHADOW_TEX = new ResourceLocation("textures/misc/shadow.png");
    private static final WorldRenderer BUFFER = new WorldRenderer(0x200000);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();

    /** Guard so the flush's own enableBlend→flushAll hook can't recursively re-enter. */
    public static boolean flushing = false;

    private static final FloatBuffer BUF = MemoryUtil.memAllocFloat(16);
    // Captured once per frame at the first submit: the world projection (renderHand later leaves
    // the base-FOV hand projection bound, so we must restore this at flush) and the camera-view
    // modelview the shadow vertices were emitted relative to.
    private static final Matrix4f PROJ = new Matrix4f();
    private static final Matrix4f MV = new Matrix4f();
    private static boolean captured = false;

    private ShadowBatch() {}

    /**
     * Open the shared buffer (POSITION_TEX_COLOR, mode 7) if needed and capture the world
     * projection + camera modelview on the frame's first shadow. Returns the buffer for the caller
     * to emit shadow quads into via {@code renderShadowBlock}.
     */
    public static WorldRenderer begin() {
        if (!BUFFER.isDrawing()) {
            BUFFER.begin(7, DefaultVertexFormats.POSITION_TEX_COLOR);
        }
        if (!captured) {
            PROJ.load(MatrixStack.getProjection());
            MV.load(MatrixStack.getModelView());
            captured = true;
        }
        return BUFFER;
    }

    /** Render and clear all deferred shadows. Must run while the world render state is still set up
     *  (before the GUI ortho). Cheap no-op when empty. */
    public static void flush() {
        if (flushing || !BUFFER.isDrawing()) {
            captured = false;
            return;
        }
        flushing = true;
        try {
            BUFFER.finishDrawing();
            if (BUFFER.getVertexCount() > 0) {
                int prevMode = MatrixStack.getActiveMode();

                // Restore the world projection (renderHand left the hand projection bound).
                GlStateManager.matrixMode(5889);
                GlStateManager.pushMatrix();
                GlStateManager.loadIdentity();
                BUF.clear();
                PROJ.store(BUF);            // store() flips the buffer itself — ready to read
                GlStateManager.multMatrix(BUF);

                // Restore the camera-view modelview the shadow coords were emitted under.
                GlStateManager.matrixMode(5888);
                GlStateManager.pushMatrix();
                GlStateManager.loadIdentity();
                BUF.clear();
                MV.store(BUF);
                GlStateManager.multMatrix(BUF);

                // Shadow GL state, set once for the whole batch. enableBlend/bindTexture each fire
                // Batches.flushAll() — at this boundary the other batches (ModelBatch etc.) are
                // drained here, drawing under the projection/modelview we just restored, then the
                // shadows draw on top. The `flushing` guard keeps it out of our own re-entry.
                GlStateManager.enableBlend();
                GlStateManager.blendFunc(770, 771);
                // Depth TEST on so the single batched draw is occluded by blocks and entity
                // bodies (all already in the depth buffer — ModelBatch was drained by the
                // enableBlend hook above). Depth WRITE off: shadows are translucent ground
                // decals and must not occlude later geometry. Vanilla relied on the depth test
                // being on ambiently mid-entity-loop; at this boundary the cache has drifted
                // (the framebuffer rebind / OptiFine path just before this flush toggles depth
                // directly), so a cache-guarded enableDepth() is a silent no-op and the shadows
                // draw with NO depth test — painting over skins and bleeding through blocks.
                // Force it straight to GL (func LEQUAL like the world pass, write off).
                GlStateManager.forceDepthState(true, 515, false);
                Minecraft.getMinecraft().getTextureManager().bindTexture(SHADOW_TEX);

                UPLOADER.func_181679_a(BUFFER);      // resets BUFFER

                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                GlStateManager.disableBlend();
                // Restore depth write; leave test as-is (GUI phase manages its own depth state).
                GlStateManager.forceDepthState(true, 515, true);

                GlStateManager.popMatrix();             // restore modelview
                GlStateManager.matrixMode(5889);
                GlStateManager.popMatrix();             // restore projection
                GlStateManager.matrixMode(prevMode);
            } else {
                BUFFER.reset();
            }
        } finally {
            captured = false;
            flushing = false;
        }
    }
}
