package plutonium.gpu;

import java.nio.FloatBuffer;

import plutonium.gpu.vk.VkDrawDispatch;
import plutonium.gpu.vk.VkUniformBlock;

/**
 * Thin façade kept API-compatible with the old GL program wrapper so the callers
 * (GlStateManager.syncUniforms, the batches, WorldVertexBufferUploader) are
 * untouched. Under Vulkan there is one shared push-constant block for every
 * program, so all setters forward to {@link VkUniformBlock} (which value-compares
 * and marks the block dirty); {@link #use()} just selects which pipeline family
 * the next draw resolves against.
 */
public class ShaderProgram {

    private final int vkProgram;

    // MatrixStack.getMvpVersion() at the last setMVP. Global now: there is one
    // push-constant block shared by every program, so per-program tracking would
    // wrongly skip re-syncs after out-of-band MVP writes (RoundRectBatch).
    public static long globalSyncedMvpVersion = -1;

    /** Kept for source compat; GlStateManager now uses the static. */
    public long syncedMvpVersion = -1;

    /** Force the next syncUniforms to re-upload the MVP (out-of-band MVP writes). */
    public static void invalidateMvpSync() { globalSyncedMvpVersion = -1; }

    public ShaderProgram(int vkProgram) {
        this.vkProgram = vkProgram;
    }

    public void use() {
        VkDrawDispatch.setProgram(vkProgram);
    }

    public static void unbind() {
        // No-op: Vulkan has no "no program" state; the next draw binds what it needs.
    }

    /** Legacy hook for external GL program changes (OptiFine) — nothing to invalidate now. */
    public static void invalidateBinding() {
    }

    public int getProgramId() { return vkProgram; }

    public void setMVP(FloatBuffer buf) { VkUniformBlock.setMVP(buf); }
    public void setColorModifier(float r, float g, float b, float a) { VkUniformBlock.setColorModifier(r, g, b, a); }
    public void setOverlayColor(float r, float g, float b, float a) { VkUniformBlock.setOverlayColor(r, g, b, a); }
    public void setLightmapCoord(float x, float y) { VkUniformBlock.setLightmapCoord(x, y); }
    public void setAlphaDiscard(float threshold) { VkUniformBlock.setAlphaDiscard(threshold); }
    public void setSampler0(int unit) { /* fixed bindings under Vulkan */ }
    public void setSampler1(int unit) { /* fixed bindings under Vulkan */ }
    public void setFogEnabled(boolean enabled) { VkUniformBlock.setFogEnabled(enabled); }
    public void setFogMode(int mode) { VkUniformBlock.setFogMode(mode); }
    public void setFogStart(float start) { VkUniformBlock.setFogStart(start); }
    public void setFogEnd(float end) { VkUniformBlock.setFogEnd(end); }
    public void setFogDensity(float density) { VkUniformBlock.setFogDensity(density); }
    public void setFogColor(float r, float g, float b, float a) { VkUniformBlock.setFogColor(r, g, b, a); }
    public void setLightingEnabled(boolean enabled) { VkUniformBlock.setLightingEnabled(enabled); }
    public void setLight0Dir(float x, float y, float z) { VkUniformBlock.setLight0Dir(x, y, z); }
    public void setLight1Dir(float x, float y, float z) { VkUniformBlock.setLight1Dir(x, y, z); }
    public void setLightDiffuse(float r, float g, float b) { VkUniformBlock.setLightDiffuse(r, g, b); }
    public void setLightAmbient(float r, float g, float b) { VkUniformBlock.setLightAmbient(r, g, b); }

    /** The GL texture matrix was only ever identity on the paths that survive (see entity.vsh
     *  history) — the Vulkan shaders don't declare it. */
    public void setTextureMatrix(FloatBuffer buf) { }

    public void delete() { }
}
