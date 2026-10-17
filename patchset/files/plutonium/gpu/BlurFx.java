package plutonium.gpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import plutonium.gpu.vk.VkDrawDispatch;
import plutonium.gpu.vk.VkPipelines;
import plutonium.gpu.vk.VkRenderTargets;
import plutonium.gpu.vk.VkUniformBlock;

/**
 * Gaussian blur of the current game framebuffer, drawn as a fullscreen background
 * behind GUI screens. Downsamples the scene to quarter resolution, runs two
 * separable 9-tap gaussian iterations (H+V twice) between two small render targets,
 * then composites the result back through the normal positionTex GUI pipeline.
 *
 * Self-disables permanently on the first failure so a broken path just falls back to
 * the flat dark overlay the callers draw instead.
 */
public final class BlurFx {

    private static boolean failed = false;

    private static VkRenderTargets.Target targetA, targetB;
    private static int bw, bh;

    private BlurFx() {}

    /**
     * Blur the scene currently in the MC framebuffer and draw it over the whole GUI.
     * @return false if blur is unavailable (caller should draw its own dark overlay).
     */
    public static boolean drawBackground(int guiW, int guiH) {
        if (failed || !CoreShaders.isInitialized()) return false;

        try {
            Framebuffer fb = Minecraft.getMinecraft().getFramebuffer();
            VkRenderTargets.Target scene = fb != null ? fb.getTarget() : null;
            if (scene == null || scene.width <= 0 || scene.height <= 0) return false;

            ensureTargets(scene.width, scene.height);

            Batches.flushAll();
            GlStateManager.disableBlend();

            // Linear downsample to quarter res — the first softening pass comes free.
            VkRenderTargets.blit(scene, targetA, bw, bh, true);

            VkUniformBlock.setTexelSize(1.0f / bw, 1.0f / bh);
            GlStateManager.viewport(0, 0, bw, bh);
            CoreShaders.getBlur().use();
            for (int i = 0; i < 2; i++) {
                blurPass(targetA, targetB, 1, 0);
                blurPass(targetB, targetA, 0, 1);
            }

            // Back to the scene target for the composite.
            VkRenderTargets.setCurrent(scene);
            GlStateManager.viewport(0, 0, scene.width, scene.height);

            // Composite through the tracked GUI pipeline: positionTex samples whatever is
            // bound, which is our blurred result. Unlike a GL FBO, a Vulkan target is
            // top-left origin, so V maps straight through (no flip).
            GlStateManager.enableTexture2D();
            GlStateManager.bindTexture(targetA.colorTex.id);
            GlStateManager.color(1, 1, 1, 1);
            GlStateManager.disableAlpha();   // scene alpha is garbage; don't let alpha-test eat it
            Tessellator tess = Tessellator.getInstance();
            WorldRenderer wr = tess.getWorldRenderer();
            wr.begin(7, DefaultVertexFormats.POSITION_TEX);
            wr.pos(0,    guiH, 0).tex(0, 1).endVertex();
            wr.pos(guiW, guiH, 0).tex(1, 1).endVertex();
            wr.pos(guiW, 0,    0).tex(1, 0).endVertex();
            wr.pos(0,    0,    0).tex(0, 0).endVertex();
            tess.draw();
            GlStateManager.enableAlpha();
            return true;
        } catch (Throwable t) {
            failed = true;
            System.err.println("[Plutonium] GUI blur disabled: " + t);
            return false;
        }
    }

    private static void blurPass(VkRenderTargets.Target src, VkRenderTargets.Target dst,
                                 float dirX, float dirY) {
        VkRenderTargets.setCurrent(dst);
        GlStateManager.bindTexture(src.colorTex.id);
        VkUniformBlock.setDirection(dirX, dirY);
        VkDrawDispatch.drawFullscreen(VkPipelines.PROG_BLUR);
    }

    private static void ensureTargets(int sceneW, int sceneH) {
        int w = Math.max(1, sceneW / 4), h = Math.max(1, sceneH / 4);
        if (w == bw && h == bh && targetA != null) return;
        bw = w;
        bh = h;

        if (targetA != null) {
            VkRenderTargets.resizeTarget(targetA, w, h);
            VkRenderTargets.resizeTarget(targetB, w, h);
        } else {
            targetA = VkRenderTargets.createTarget(w, h, false);
            targetB = VkRenderTargets.createTarget(w, h, false);
        }
        // The gaussian taps need bilinear filtering and edge clamping.
        for (VkRenderTargets.Target t : new VkRenderTargets.Target[] {targetA, targetB}) {
            plutonium.gpu.vk.VkTextures.setParam(t.colorTex.id, 10241, 9729);   // MIN_FILTER = LINEAR
            plutonium.gpu.vk.VkTextures.setParam(t.colorTex.id, 10240, 9729);   // MAG_FILTER = LINEAR
        }
    }
}
