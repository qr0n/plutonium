package plutonium.gpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.WorldVertexBufferUploader;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

/**
 * Batches baked-model item rendering in the world pass — dropped item entities, third-person
 * held items, item frames — into one deferred draw. Vanilla issues one immediate ITEM-format
 * draw per {@code RenderItem.renderModel} call (ITEM has normals, so it routes to the ENTITY
 * program and counts in the `entity` draw bucket).
 *
 * <p>Capture: {@code renderModel} appends its baked quads into {@link #BUFFER} via the normal
 * {@code renderQuads} path (tints and all — putQuadNormal has already baked eye-space normals
 * using the live modelview), then {@code WorldRenderer.transformPositionsRange} bakes the
 * current modelview into the just-added positions (eye space). Flush draws everything under
 * an identity modelview before renderHand, world projection still bound; all baked item quads
 * sample the shared blocks/items atlas. Known compromise: the entity program's u_LightmapCoord
 * is a uniform, so all batched items get the flush-time lightmap level instead of per-item
 * light — uniform-lit arenas look identical; a cave/torch gradient would not.
 *
 * <p>NOT captured (stay inline): enchant-glint passes (texture-matrix scroll + additive
 * blend), built-in renderers (chests/skulls/banners), GUI items (different phase), and
 * first-person hand items (different projection — renderHand runs after the flush and
 * outside {@link Batches#entityPassActive}).
 */
public final class ItemBatch {

    public static boolean ENABLED = true;

    private static final WorldRenderer BUFFER = new WorldRenderer(0x200000);
    private static final WorldVertexBufferUploader UPLOADER = new WorldVertexBufferUploader();
    public static boolean flushing = false;

    private ItemBatch() {}

    /** True when renderModel should capture into the batch instead of drawing inline. */
    public static boolean capturing() {
        return ENABLED && Batches.entityPassActive;
    }

    /** The shared capture buffer, begun lazily with the ITEM format. */
    public static WorldRenderer buffer() {
        if (!BUFFER.isDrawing()) {
            BUFFER.begin(7, DefaultVertexFormats.ITEM);
        }
        return BUFFER;
    }

    /** Draw everything accumulated this frame. Must run with the world projection active
     *  (called before renderHand). Cheap no-op when empty. */
    public static void flush() {
        if (flushing || !BUFFER.isDrawing()) return;
        flushing = true;
        try {
            BUFFER.finishDrawing();
            if (BUFFER.getVertexCount() > 0) {
                // May trigger Batches.flushAll — fine, this batch is not registered there.
                Minecraft.getMinecraft().getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
                GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                GlStateManager.disableBlend();
                GlStateManager.enableAlpha();
                GlStateManager.alphaFunc(516, 0.1F);
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                // The ITEM format routes to the ENTITY program (it has normals), which
                // lights with the eye-space light dirs + this flag — inline item draws ran
                // with standard item lighting on, so match it. Normals were already baked
                // to eye space at capture (putQuadNormal).
                GlStateManager.enableLighting();
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
