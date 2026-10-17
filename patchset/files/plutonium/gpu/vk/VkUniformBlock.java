package plutonium.gpu.vk;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import org.lwjgl.system.MemoryUtil;

/**
 * CPU shadow of the shared 240-byte push-constant block (see shaders/vk/pc_block.glsl).
 * All ShaderProgram setters write here; VkDrawDispatch pushes the whole block via
 * vkCmdPushConstants when dirty at draw time. Uniforms are global under Vulkan (one
 * block for every program), which matches how GlStateManager.syncUniforms re-syncs
 * everything per draw anyway.
 *
 * setMVP applies the GL→VK clip-space fix: z' = (z + w) / 2 folded into the matrix
 * (depth −1..1 → 0..1). Y stays GL-convention — handled by the negative-height
 * viewport in VkDrawDispatch, which preserves winding/cull semantics.
 */
public final class VkUniformBlock {

    public static final int SIZE = 240;

    private static final int OFF_MVP = 0;
    private static final int OFF_COLOR_MOD = 64;
    private static final int OFF_OVERLAY = 80;
    private static final int OFF_FOG_COLOR = 96;
    private static final int OFF_LIGHT0 = 112;
    private static final int OFF_LIGHT1 = 128;
    private static final int OFF_LIGHT_DIFFUSE = 144;
    private static final int OFF_LIGHT_AMBIENT = 160;
    private static final int OFF_LIGHTMAP_COORD = 176;
    private static final int OFF_TEXEL_SIZE = 184;
    private static final int OFF_DIRECTION = 192;
    private static final int OFF_ALPHA_DISCARD = 200;
    private static final int OFF_FOG_ENABLED = 204;
    private static final int OFF_FOG_MODE = 208;
    private static final int OFF_FOG_START = 212;
    private static final int OFF_FOG_END = 216;
    private static final int OFF_FOG_DENSITY = 220;
    private static final int OFF_LIGHTING_ENABLED = 224;

    private static final ByteBuffer buf = MemoryUtil.memAlloc(SIZE);
    private static boolean dirty = true;

    // Dirty byte range [dirtyLo, dirtyHi). vkCmdPushConstants only re-uploads what changed:
    // the chunk loop touches nothing but the MVP between draws, so those draws push 64 bytes
    // instead of the whole 240-byte block — Metal has to copy the range at encode time, and
    // encoding is the frame's dominant cost (see project memory vk-frame-cost-profile).
    private static int dirtyLo = 0;
    private static int dirtyHi = SIZE;

    private static final float[] lastMvp = new float[16];
    private static boolean mvpSet = false;

    private VkUniformBlock() {}

    public static ByteBuffer buffer() { return buf; }
    public static boolean isDirty() { return dirty; }
    public static int dirtyOffset() { return dirtyLo; }
    public static int dirtySize() { return dirtyHi - dirtyLo; }

    public static void markPushed() {
        dirty = false;
        dirtyLo = SIZE;
        dirtyHi = 0;
    }

    /** New command buffer → previous pushes are gone; the whole block must go up again. */
    public static void invalidate() {
        dirty = true;
        dirtyLo = 0;
        dirtyHi = SIZE;
    }

    /** Vulkan requires pushConstants offset/size to be 4-byte aligned; every field here is. */
    private static void markDirty(int off, int len) {
        if (off < dirtyLo) dirtyLo = off;
        if (off + len > dirtyHi) dirtyHi = off + len;
        dirty = true;
    }

    /** GL-convention MVP (column-major, 16 floats); stored raw — depth remap is post-transform in shaders. */
    public static void setMVP(FloatBuffer glMvp) {
        boolean same = mvpSet;
        for (int i = 0; same && i < 16; i++) {
            if (glMvp.get(i) != lastMvp[i]) same = false;
        }
        if (same) return;
        for (int i = 0; i < 16; i++) lastMvp[i] = glMvp.get(i);
        mvpSet = true;
        plutonium.gpu.DrawStats.mvpUploads++;
        for (int i = 0; i < 16; i++) {
            buf.putFloat(OFF_MVP + i * 4, glMvp.get(i));
        }
        markDirty(OFF_MVP, 64);
    }

    /**
     * Write an MVP without value-compare (RoundRectBatch pushes a projection-only
     * matrix mid-frame). Poisons the compare cache so the next setMVP always rewrites.
     * The GL->VK depth remap happens post-transform in the vertex shaders.
     */
    public static void setMVPDirect(FloatBuffer glMvp) {
        mvpSet = false;
        for (int i = 0; i < 16; i++) {
            buf.putFloat(OFF_MVP + i * 4, glMvp.get(i));
        }
        markDirty(OFF_MVP, 64);
    }

    private static void putVec4(int off, float x, float y, float z, float w) {
        if (buf.getFloat(off) == x && buf.getFloat(off + 4) == y
                && buf.getFloat(off + 8) == z && buf.getFloat(off + 12) == w) return;
        buf.putFloat(off, x).putFloat(off + 4, y).putFloat(off + 8, z).putFloat(off + 12, w);
        markDirty(off, 16);
    }

    private static void putVec2(int off, float x, float y) {
        if (buf.getFloat(off) == x && buf.getFloat(off + 4) == y) return;
        buf.putFloat(off, x).putFloat(off + 4, y);
        markDirty(off, 8);
    }

    private static void putFloat(int off, float v) {
        if (buf.getFloat(off) == v) return;
        buf.putFloat(off, v);
        markDirty(off, 4);
    }

    private static void putInt(int off, int v) {
        if (buf.getInt(off) == v) return;
        buf.putInt(off, v);
        markDirty(off, 4);
    }

    public static void setColorModifier(float r, float g, float b, float a) { putVec4(OFF_COLOR_MOD, r, g, b, a); }
    public static void setOverlayColor(float r, float g, float b, float a) { putVec4(OFF_OVERLAY, r, g, b, a); }
    public static void setFogColor(float r, float g, float b, float a) { putVec4(OFF_FOG_COLOR, r, g, b, a); }
    public static void setLight0Dir(float x, float y, float z) { putVec4(OFF_LIGHT0, x, y, z, 0); }
    public static void setLight1Dir(float x, float y, float z) { putVec4(OFF_LIGHT1, x, y, z, 0); }
    public static void setLightDiffuse(float r, float g, float b) { putVec4(OFF_LIGHT_DIFFUSE, r, g, b, 0); }
    public static void setLightAmbient(float r, float g, float b) { putVec4(OFF_LIGHT_AMBIENT, r, g, b, 0); }
    public static void setLightmapCoord(float x, float y) { putVec2(OFF_LIGHTMAP_COORD, x, y); }
    public static void setTexelSize(float x, float y) { putVec2(OFF_TEXEL_SIZE, x, y); }
    public static void setDirection(float x, float y) { putVec2(OFF_DIRECTION, x, y); }
    public static void setAlphaDiscard(float v) { putFloat(OFF_ALPHA_DISCARD, v); }
    public static void setFogEnabled(boolean v) { putInt(OFF_FOG_ENABLED, v ? 1 : 0); }
    public static void setFogMode(int v) { putInt(OFF_FOG_MODE, v); }
    public static void setFogStart(float v) { putFloat(OFF_FOG_START, v); }
    public static void setFogEnd(float v) { putFloat(OFF_FOG_END, v); }
    public static void setFogDensity(float v) { putFloat(OFF_FOG_DENSITY, v); }
    public static void setLightingEnabled(boolean v) { putInt(OFF_LIGHTING_ENABLED, v ? 1 : 0); }

    static {
        MemoryUtil.memSet(buf, 0);
        // Identity MVP + white color so early draws are sane before first sync.
        buf.putFloat(0, 1).putFloat(20, 1).putFloat(40, 1).putFloat(60, 1);
        buf.putFloat(OFF_COLOR_MOD, 1).putFloat(OFF_COLOR_MOD + 4, 1)
           .putFloat(OFF_COLOR_MOD + 8, 1).putFloat(OFF_COLOR_MOD + 12, 1);
    }
}
