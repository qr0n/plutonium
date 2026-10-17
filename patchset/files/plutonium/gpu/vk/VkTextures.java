package plutonium.gpu.vk;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GL-style texture ids over VkImages. Ids are handed out by {@link #gen()} and flow
 * through the untouched vanilla code (TextureManager, DynamicTexture, atlas…); all
 * pixel data arrives as BGRA ints via GlStateManager.glTexImage2D/glTexSubImage2D —
 * byte-identical to VK_FORMAT_B8G8R8A8_UNORM on little-endian, so uploads are a
 * staging-ring copy with no swizzle.
 *
 * Samplers are resolved lazily from the GL texparams a texture carries, via a global
 * cache. Render-target color images register here too (VkRenderTargets sets
 * {@code target}) so framebuffer textures bind/sample like any other id.
 */
public final class VkTextures {

    public static final class Tex {
        public final int id;
        public int width, height, mips = 1;
        public long image, memory, view;
        public int layout = VK_IMAGE_LAYOUT_UNDEFINED;
        /** Non-null when this id is a render target's color attachment (layout managed there). */
        public VkRenderTargets.Target target;

        // GL texture parameters (defaults per GL spec).
        public int minFilter = 9986;   // GL_NEAREST_MIPMAP_LINEAR
        public int magFilter = 9729;   // GL_LINEAR
        public int wrapS = 10497, wrapT = 10497;  // GL_REPEAT
        public int declaredMaxLevel = 0;
        public float lodBias = 0;

        Tex(int id) { this.id = id; }
    }

    private static final Map<Integer, Tex> textures = new HashMap<Integer, Tex>();
    private static final Map<Long, Long> samplers = new HashMap<Long, Long>();
    private static int nextId = 1;
    private static Tex white;

    private static final VkFrameRing.Alloc scratch = new VkFrameRing.Alloc();

    private VkTextures() {}

    public static void init() {
        white = get(gen());
        IntBuffer px = MemoryUtil.memAllocInt(1);
        px.put(0, 0xFFFFFFFF);
        texImage2D(white.id, 0, 1, 1, null);
        texSubImage2D(white.id, 0, 0, 0, 1, 1, px);
        MemoryUtil.memFree(px);
    }

    public static int gen() {
        int id = nextId++;
        textures.put(id, new Tex(id));
        return id;
    }

    public static Tex get(int id) {
        return textures.get(id);
    }

    /** Register an externally-created image (render target color) under a fresh id. */
    public static Tex registerTarget(long image, long view, int w, int h, VkRenderTargets.Target target) {
        Tex tex = get(gen());
        tex.image = image;
        tex.view = view;
        tex.width = w;
        tex.height = h;
        tex.target = target;
        tex.minFilter = 9728;  // framebuffers default to NEAREST
        tex.magFilter = 9728;
        tex.wrapS = tex.wrapT = 33071;
        return tex;
    }

    public static void delete(int id) {
        final Tex tex = textures.remove(id);
        if (tex == null || tex.target != null) return; // target images are owned by VkRenderTargets
        deferDestroyImage(tex);
    }

    private static void deferDestroyImage(Tex tex) {
        final long image = tex.image, memory = tex.memory, view = tex.view;
        if (image == 0) return;
        VkFrameRing.deferDestroy(new Runnable() {
            public void run() {
                vkDestroyImageView(VkContext.device(), view, null);
                vkDestroyImage(VkContext.device(), image, null);
                vkFreeMemory(VkContext.device(), memory, null);
                VkMemory.liveAllocations--;
            }
        });
        tex.image = tex.memory = tex.view = 0;
        tex.layout = VK_IMAGE_LAYOUT_UNDEFINED;
    }

    public static void setParam(int id, int pname, float value) {
        Tex tex = get(id);
        if (tex == null) return;
        int iv = (int) value;
        switch (pname) {
            case 10241: tex.minFilter = iv; break;          // GL_TEXTURE_MIN_FILTER
            case 10240: tex.magFilter = iv; break;          // GL_TEXTURE_MAG_FILTER
            case 10242: tex.wrapS = iv; break;              // GL_TEXTURE_WRAP_S
            case 10243: tex.wrapT = iv; break;              // GL_TEXTURE_WRAP_T
            case 33085: tex.declaredMaxLevel = iv; break;   // GL_TEXTURE_MAX_LEVEL
            case 34049: tex.lodBias = value; break;         // GL_TEXTURE_LOD_BIAS
            default: break;                                  // MIN_LOD/MAX_LOD/aniso: ignored (M2)
        }
    }

    /**
     * GL glTexImage2D semantics for level 0: (re)define storage. Levels > 0 are
     * ignored for allocation (level 0 already sized the full chain from
     * GL_TEXTURE_MAX_LEVEL); non-null data is uploaded.
     */
    public static void texImage2D(int id, int level, int width, int height, IntBuffer data) {
        Tex tex = get(id);
        if (tex == null) return;
        if (level == 0) {
            int mips = tex.declaredMaxLevel + 1;
            if (tex.image == 0 || tex.width != width || tex.height != height || tex.mips != mips) {
                deferDestroyImage(tex);
                tex.width = width;
                tex.height = height;
                tex.mips = mips;
                VkMemory.Img img = VkMemory.createImage2D(width, height, mips, VK_FORMAT_B8G8R8A8_UNORM,
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
                tex.image = img.image;
                tex.memory = img.memory;
                tex.view = createView(img.image, VK_FORMAT_B8G8R8A8_UNORM, mips, VK_IMAGE_ASPECT_COLOR_BIT);
                tex.layout = VK_IMAGE_LAYOUT_UNDEFINED;
            }
        }
        if (data != null && tex.image != 0) {
            texSubImage2D(id, level, 0, 0, Math.max(1, tex.width >> level), Math.max(1, tex.height >> level), data);
        }
    }

    /** Upload a BGRA int region into a mip level via the staging ring. */
    public static void texSubImage2D(int id, int level, int x, int y, int width, int height, IntBuffer data) {
        Tex tex = get(id);
        if (tex == null || tex.image == 0 || width <= 0 || height <= 0) return;

        long bytes = (long) width * height * 4;
        VkFrameRing.allocStaging(bytes, scratch);
        MemoryUtil.memCopy(MemoryUtil.memAddress(data), scratch.hostPtr, bytes);

        VkRenderTargets.suspendRendering();
        VkCommandBuffer cb = VkFrameRing.begin();

        if (tex.layout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            transition(cb, tex, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset(scratch.offset)
                    .bufferRowLength(0)
                    .bufferImageHeight(0);
            region.get(0).imageSubresource()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(level)
                    .baseArrayLayer(0).layerCount(1);
            region.get(0).imageOffset().set(x, y, 0);
            region.get(0).imageExtent().set(width, height, 1);
            vkCmdCopyBufferToImage(cb, scratch.buffer, tex.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }
        transition(cb, tex, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    /** Make the texture sampleable (barrier if needed). Must be called with rendering suspended
     *  or known-compatible; VkDrawDispatch calls this before pushing descriptors. */
    public static void ensureShaderRead(Tex tex) {
        if (tex == null || tex.image == 0) return;
        if (tex.target != null) {
            VkRenderTargets.ensureShaderRead(tex.target);
            return;
        }
        if (tex.layout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) return;
        VkRenderTargets.suspendRendering();
        transition(VkFrameRing.begin(), tex, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
    }

    private static void transition(VkCommandBuffer cb, Tex tex, int newLayout) {
        imageBarrier(cb, tex.image, tex.layout, newLayout, tex.mips, VK_IMAGE_ASPECT_COLOR_BIT);
        tex.layout = newLayout;
    }

    /** Generic full-image layout barrier with conservative stage/access masks per layout. */
    public static void imageBarrier(VkCommandBuffer cb, long image, int oldLayout, int newLayout,
                                    int mips, int aspect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0).sType$Default()
                    .srcAccessMask(accessFor(oldLayout))
                    .dstAccessMask(accessFor(newLayout))
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            barrier.get(0).subresourceRange()
                    .aspectMask(aspect)
                    .baseMipLevel(0).levelCount(mips)
                    .baseArrayLayer(0).layerCount(1);
            vkCmdPipelineBarrier(cb, stageFor(oldLayout), stageFor(newLayout), 0, null, null, barrier);
        }
    }

    private static int accessFor(int layout) {
        switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL: return VK_ACCESS_TRANSFER_WRITE_BIT;
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL: return VK_ACCESS_TRANSFER_READ_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL: return VK_ACCESS_SHADER_READ_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL:
                return VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
                return VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            default: return 0;
        }
    }

    private static int stageFor(int layout) {
        switch (layout) {
            case VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL:
            case VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL: return VK_PIPELINE_STAGE_TRANSFER_BIT;
            case VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL: return VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            case VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL: return VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
            case VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL:
                return VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            case org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR: return VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
            default: return VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;  // UNDEFINED
        }
    }

    static long createView(long image, int format, int mips, int aspect) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                    .sType$Default()
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            ci.subresourceRange()
                    .aspectMask(aspect)
                    .baseMipLevel(0).levelCount(mips)
                    .baseArrayLayer(0).layerCount(1);
            LongBuffer pView = stack.mallocLong(1);
            VkContext.check(vkCreateImageView(VkContext.device(), ci, null, pView), "vkCreateImageView");
            return pView.get(0);
        }
    }

    /** View to bind for a texture unit; falls back to the built-in white texture. */
    public static Tex resolve(int id) {
        Tex tex = get(id);
        return (tex == null || tex.image == 0) ? white : tex;
    }

    public static long sampler(Tex tex) {
        boolean mipmapped = tex.minFilter >= 9984 && tex.mips > 1;
        long key = ((long) (tex.minFilter - 9728)) | ((long) (tex.magFilter - 9728) << 9)
                | ((long) (tex.wrapS == 10497 ? 1 : 0) << 18) | ((long) (tex.wrapT == 10497 ? 1 : 0) << 19)
                | ((long) (mipmapped ? tex.mips : 0) << 20) | ((long) Float.floatToIntBits(tex.lodBias) << 28);
        Long cached = samplers.get(key);
        if (cached != null) return cached;

        int magLinear = (tex.magFilter == 9729) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        boolean minLinear = tex.minFilter == 9729 || tex.minFilter == 9985 || tex.minFilter == 9987;
        boolean mipLinear = tex.minFilter == 9986 || tex.minFilter == 9987;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo ci = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(magLinear)
                    .minFilter(minLinear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .mipmapMode(mipLinear ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(tex.wrapS == 10497 ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(tex.wrapT == 10497 ? VK_SAMPLER_ADDRESS_MODE_REPEAT : VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT)
                    .mipLodBias(tex.lodBias)
                    .minLod(0)
                    .maxLod(mipmapped ? tex.mips : 0.25f);
            LongBuffer pSampler = stack.mallocLong(1);
            VkContext.check(vkCreateSampler(VkContext.device(), ci, null, pSampler), "vkCreateSampler");
            samplers.put(key, pSampler.get(0));
            return pSampler.get(0);
        }
    }
}
