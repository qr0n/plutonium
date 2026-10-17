package plutonium.module.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import plutonium.gpu.vk.VkRenderTargets;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

/**
 * Accumulation motion blur: each frame the previous frame's result is blended over the
 * freshly rendered scene at {@code strength} alpha, then the blended scene is copied back
 * into the history target to feed the next frame.
 */
public class MotionBlur extends Module {

    public static MotionBlur instance;

    private final SliderSetting strength = reg(new SliderSetting("Strength", "Blur intensity", 0.65f, 0.0f, 0.95f, 0.05f));
    private VkRenderTargets.Target history;
    private boolean historyValid = false;
    private long lastFrameNs = 0;

    public MotionBlur() {
        super("Motion Blur", Category.VISUAL);
        instance = this;
    }

    @Override
    public void onDisable() {
        historyValid = false;
        lastFrameNs = 0;
    }

    /**
     * Per-frame history weight for this frame. The slider is calibrated as the blend
     * alpha at a 60 FPS reference; raising it to the power (frameTime x 60) keeps the
     * trail's decay constant in wall time instead of in frames — a fixed alpha reads
     * as no blur at thousands of FPS and as seconds-long smearing at 30.
     * Capped at 0.9: an 8-bit history can't resolve contributions below 1/255, so
     * higher weights freeze old frames into permanent speckle ("dirty window").
     */
    private float frameAlpha() {
        long now = System.nanoTime();
        float dt = lastFrameNs == 0 ? 1.0f / 60.0f : (now - lastFrameNs) * 1.0e-9f;
        lastFrameNs = now;
        float base = strength.getValue();
        if (base <= 0.0f) return 0.0f;
        return Math.min(0.9f, (float) Math.pow(base, Math.min(dt, 0.25f) * 60.0));
    }

    public static void postRenderWorld(int width, int height) {
        if (instance == null || !instance.isEnabled() || width <= 0 || height <= 0) return;

        Framebuffer fb = Minecraft.getMinecraft().getFramebuffer();
        VkRenderTargets.Target scene = fb != null ? fb.getTarget() : null;
        if (scene == null) return;

        instance.ensureHistory(width, height);

        // No previous frame to blend yet (first frame, resize, or re-enable): just seed it.
        if (!instance.historyValid) {
            VkRenderTargets.blit(scene, instance.history, width, height, false);
            instance.historyValid = true;
            return;
        }

        GlStateManager.matrixMode(5889);   // GL_PROJECTION
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0, width, height, 0, -1, 1);
        GlStateManager.matrixMode(5888);   // GL_MODELVIEW
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();

        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(instance.history.colorTex.id);
        GlStateManager.color(1.0f, 1.0f, 1.0f, instance.frameAlpha());

        // Vulkan targets are top-left origin, so V maps straight through (no GL FBO flip).
        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        wr.begin(7, DefaultVertexFormats.POSITION_TEX);
        wr.pos(0, 0, 0).tex(0, 0).endVertex();
        wr.pos(0, height, 0).tex(0, 1).endVertex();
        wr.pos(width, height, 0).tex(1, 1).endVertex();
        wr.pos(width, 0, 0).tex(1, 0).endVertex();
        tess.draw();

        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);

        GlStateManager.popMatrix();
        GlStateManager.matrixMode(5889);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(5888);

        // Feed the blended result forward.
        VkRenderTargets.blit(scene, instance.history, width, height, false);
        GlStateManager.bindTexture(0);
    }

    private void ensureHistory(int width, int height) {
        if (history == null) {
            history = VkRenderTargets.createTarget(width, height, false);
            historyValid = false;
        } else if (history.width != width || history.height != height) {
            VkRenderTargets.resizeTarget(history, width, height);
            historyValid = false;
        }
    }
}
