package plutonium.gpu.vk;

import java.nio.ByteBuffer;

import net.minecraft.client.renderer.vertex.VertexFormat;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTExtendedDynamicState;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkViewport;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.vulkan.VK10.*;

/**
 * The immediate-geometry funnel: WorldVertexBufferUploader.drawModern lands here.
 * Copies vertices into the per-frame ring, resolves a pipeline from
 * (program, format, VkRenderState, target), applies dynamic state deltas, pushes
 * the uniform block / texture descriptors when dirty, and issues vkCmdDraw.
 *
 * GL quirks handled here: triangle fans and line loops don't exist on Metal, so
 * they're expanded during the ring copy; the viewport is emitted with negative
 * height (GL Y-up clip space, winding preserved); scissor rects convert from GL's
 * bottom-left origin.
 */
public final class VkDrawDispatch {

    private static int program = VkPipelines.PROG_POSITION_COLOR;
    private static int activeUnit = 0;
    private static final int[] texUnit = new int[2];

    private static VkMemory.Buf constants;

    // Per-command-buffer caches (reset when VkFrameRing.generation() changes).
    private static long cbGen = -1;
    private static long boundPipeline;
    private static boolean boundConstants;
    private static long boundVertexBuffer, boundVertexOffset;
    private static int pushedTex0 = -1, pushedTex1 = -1;
    private static long pushedView0, pushedView1;
    private static int lastVpX, lastVpY, lastVpW = -1, lastVpH;
    private static int lastScX, lastScY, lastScW = -1, lastScH;
    private static int lastCull = -1, lastFront = -1, lastTopo = -1, lastDepthCompare = -1;
    private static int lastDepthTest = -1, lastDepthWrite = -1;
    private static float lastBiasFactor = Float.NaN, lastBiasUnits = Float.NaN;

    private static final VkFrameRing.Alloc alloc = new VkFrameRing.Alloc();

    private VkDrawDispatch() {}

    public static void init() {
        // 32-byte constants stream: white RGBA8 at 0, zeros after (see VkPipelines fallback).
        constants = VkMemory.createBuffer(32, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
        MemoryUtil.memSet(constants.hostPtr, 0, 32);
        MemoryUtil.memPutInt(constants.hostPtr, 0xFFFFFFFF);
    }

    public static void setProgram(int programIndex) { program = programIndex; }
    public static int getProgram() { return program; }

    public static void setActiveUnit(int unit) {
        if (unit >= 0 && unit < 2) activeUnit = unit;
        else activeUnit = -1;   // units we don't model (OptiFine); binds get ignored
    }

    public static void bindTexture(int id) {
        if (activeUnit >= 0) texUnit[activeUnit] = id;
    }

    public static int boundTexture() { return activeUnit >= 0 ? texUnit[activeUnit] : 0; }

    public static void onTextureDeleted(int id) {
        for (int i = 0; i < 2; i++) if (texUnit[i] == id) texUnit[i] = 0;
    }

    /** Immediate-mode draw from a client-memory vertex buffer (the WorldRenderer stream). */
    public static void draw(VertexFormat format, ByteBuffer data, int glMode, int vertexCount) {
        if (vertexCount == 0) return;
        int stride = format.getNextOffset();
        drawInternal(program, VkPipelines.formatId(format), stride, MemoryUtil.memAddress(data), glMode, vertexCount);
    }

    /** RoundRectBatch path: fixed all-float stride-72 layout, triangles. */
    public static void drawRoundRect(java.nio.FloatBuffer data, int vertexCount) {
        if (vertexCount == 0) return;
        drawInternal(VkPipelines.PROG_ROUNDRECT, VkPipelines.FORMAT_ROUNDRECT, 72,
                MemoryUtil.memAddress(data), 4, vertexCount);
    }

    private static void drawInternal(int prog, int formatId, int stride, long srcAddr,
                                     int glMode, int vertexCount) {
        // Expansion for Metal-less primitives + ring upload.
        int topoClass;
        int vkTopology;
        int finalCount;

        switch (glMode) {
            case 4:  vkTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;  topoClass = VkPipelines.TOPO_CLASS_TRIANGLE; finalCount = vertexCount; break;
            case 5:  vkTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP; topoClass = VkPipelines.TOPO_CLASS_TRIANGLE; finalCount = vertexCount; break;
            case 8:  vkTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP; topoClass = VkPipelines.TOPO_CLASS_TRIANGLE; finalCount = vertexCount; break; // QUAD_STRIP ≈ tri strip
            case 6:  vkTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;  topoClass = VkPipelines.TOPO_CLASS_TRIANGLE; finalCount = Math.max(0, (vertexCount - 2) * 3); break;
            case 1:  vkTopology = VK_PRIMITIVE_TOPOLOGY_LINE_LIST;      topoClass = VkPipelines.TOPO_CLASS_LINE;     finalCount = vertexCount; break;
            case 3:  vkTopology = VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;     topoClass = VkPipelines.TOPO_CLASS_LINE;     finalCount = vertexCount; break;
            case 2:  vkTopology = VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;     topoClass = VkPipelines.TOPO_CLASS_LINE;     finalCount = vertexCount + 1; break; // loop → strip + first
            case 0:  vkTopology = VK_PRIMITIVE_TOPOLOGY_POINT_LIST;     topoClass = VkPipelines.TOPO_CLASS_POINT;    finalCount = vertexCount; break;
            default: // 7 (quads) is triangulated upstream; anything else, draw as triangles
                vkTopology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST; topoClass = VkPipelines.TOPO_CLASS_TRIANGLE; finalCount = vertexCount; break;
        }
        if (finalCount == 0) return;

        // Make sampled textures ready BEFORE (re)starting the render pass.
        VkTextures.Tex tex0 = VkTextures.resolve(texUnit[0]);
        VkTextures.Tex tex1 = VkTextures.resolve(texUnit[1]);
        VkTextures.ensureShaderRead(tex0);
        VkTextures.ensureShaderRead(tex1);

        VkFrameRing.allocVertex((long) finalCount * stride, alloc);
        if (glMode == 6) {
            // Triangle fan → list: (0, i, i+1)
            long dst = alloc.hostPtr;
            for (int i = 1; i < vertexCount - 1; i++) {
                MemoryUtil.memCopy(srcAddr, dst, stride);
                MemoryUtil.memCopy(srcAddr + (long) i * stride, dst + stride, stride);
                MemoryUtil.memCopy(srcAddr + (long) (i + 1) * stride, dst + 2L * stride, stride);
                dst += 3L * stride;
            }
        } else if (glMode == 2) {
            MemoryUtil.memCopy(srcAddr, alloc.hostPtr, (long) vertexCount * stride);
            MemoryUtil.memCopy(srcAddr, alloc.hostPtr + (long) vertexCount * stride, stride);
        } else {
            MemoryUtil.memCopy(srcAddr, alloc.hostPtr, (long) finalCount * stride);
        }

        emit(prog, formatId, topoClass, vkTopology, alloc.buffer, alloc.offset, finalCount, tex0, tex1);
    }

    /**
     * Draw straight out of a persistent VkBuffer — chunk layers and the sky/star domes,
     * whose geometry lives on the GPU across frames instead of streaming through the ring.
     * Callers upload pre-triangulated data (VertexBufferUploader and RenderGlobal both run
     * quadsToTriangles first), so there is no fan/loop expansion to do here.
     */
    public static void drawBuffer(VertexFormat format, long vkBuffer, long offset, int glMode, int vertexCount) {
        if (vkBuffer == 0L || vertexCount == 0) return;
        if (glMode != 4) {
            throw new IllegalArgumentException("drawBuffer needs triangles, got GL mode " + glMode);
        }

        VkTextures.Tex tex0 = VkTextures.resolve(texUnit[0]);
        VkTextures.Tex tex1 = VkTextures.resolve(texUnit[1]);
        VkTextures.ensureShaderRead(tex0);
        VkTextures.ensureShaderRead(tex1);

        emit(program, VkPipelines.formatId(format), VkPipelines.TOPO_CLASS_TRIANGLE,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, vkBuffer, offset, vertexCount, tex0, tex1);
    }

    /**
     * Attribute-less fullscreen triangle (BlurFx). The vertex shader derives its position
     * from gl_VertexIndex, so the pipeline declares no vertex bindings and nothing is bound.
     */
    public static void drawFullscreen(int prog) {
        VkTextures.Tex tex0 = VkTextures.resolve(texUnit[0]);
        VkTextures.Tex tex1 = VkTextures.resolve(texUnit[1]);
        VkTextures.ensureShaderRead(tex0);
        VkTextures.ensureShaderRead(tex1);

        emit(prog, VkPipelines.FORMAT_NONE, VkPipelines.TOPO_CLASS_TRIANGLE,
                VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, 0L, 0L, 3, tex0, tex1);
    }

    private static void emit(int prog, int formatId, int topoClass, int vkTopology,
                             long vertexBuffer, long vertexOffset, int count,
                             VkTextures.Tex tex0, VkTextures.Tex tex1) {
        VkRenderTargets.Target target = VkRenderTargets.currentTarget();
        VkCommandBuffer cb = VkRenderTargets.ensureRendering();

        if (cbGen != VkFrameRing.generation()) {
            resetCaches();
            cbGen = VkFrameRing.generation();
        }

        long pipeline = VkPipelines.getPipeline(prog, formatId, topoClass, target.hasDepth);
        if (pipeline != boundPipeline) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            boundPipeline = pipeline;
        }

        applyDynamicState(cb, target, vkTopology);
        pushUniformsIfDirty(cb);
        pushDescriptorsIfChanged(cb, tex0, tex1);

        if (vertexBuffer != 0L) {
            // Binding 1 (the constants stream) never changes, so it only has to be bound once
            // per command buffer; re-binding it per draw doubled the buffer-binding work Metal
            // had to encode. Binding 0 still changes per chunk/batch.
            if (!boundConstants) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    vkCmdBindVertexBuffers(cb, 1, stack.longs(constants.buffer), stack.longs(0L));
                }
                boundConstants = true;
            }
            if (vertexBuffer != boundVertexBuffer || vertexOffset != boundVertexOffset) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    vkCmdBindVertexBuffers(cb, 0, stack.longs(vertexBuffer), stack.longs(vertexOffset));
                }
                boundVertexBuffer = vertexBuffer;
                boundVertexOffset = vertexOffset;
            }
        }
        vkCmdDraw(cb, count, 1, 0, 0);
    }

    private static void resetCaches() {
        boundPipeline = 0;
        boundConstants = false;
        boundVertexBuffer = 0L;
        boundVertexOffset = -1L;
        pushedTex0 = pushedTex1 = -1;
        pushedView0 = pushedView1 = 0;
        lastVpW = -1;
        lastScW = -1;
        lastCull = lastFront = lastTopo = lastDepthCompare = -1;
        lastDepthTest = lastDepthWrite = -1;
        lastBiasFactor = lastBiasUnits = Float.NaN;
    }

    private static void applyDynamicState(VkCommandBuffer cb, VkRenderTargets.Target target, int vkTopology) {
        int th = target.height;

        // Viewport: GL bottom-left origin, we flip via negative height (winding preserved).
        int vx = VkRenderState.viewportX, vy = VkRenderState.viewportY;
        int vw = Math.max(1, VkRenderState.viewportW), vh = Math.max(1, VkRenderState.viewportH);
        if (vx != lastVpX || vy != lastVpY || vw != lastVpW || vh != lastVpH) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkViewport.Buffer vp = VkViewport.calloc(1, stack)
                        .x(vx).y(th - vy).width(vw).height(-vh).minDepth(0.0f).maxDepth(1.0f);
                vkCmdSetViewport(cb, 0, vp);
            }
            lastVpX = vx; lastVpY = vy; lastVpW = vw; lastVpH = vh;
        }

        int sx, sy, sw, sh;
        if (VkRenderState.scissorEnabled) {
            // glScissor coords arrive in logical window pixels — callers compute them as
            // GUI units * ScaledResolution.getScaleFactor(), and ScaledResolution's base
            // is the logical window size. The pass may render into a target of a
            // different size (render-scale), so rescale into target coordinates.
            float rx = (float) target.width / Math.max(1, plutonium.gpu.WindowManager.getWindowWidth());
            float ry = (float) th / Math.max(1, plutonium.gpu.WindowManager.getWindowHeight());
            sx = Math.max(0, Math.round(VkRenderState.scissorX * rx));
            sw = Math.max(0, Math.round(VkRenderState.scissorW * rx));
            sh = Math.max(0, Math.round(VkRenderState.scissorH * ry));
            sy = Math.max(0, th - Math.round(VkRenderState.scissorY * ry) - sh);   // GL bottom-left → VK top-left
        } else {
            sx = 0; sy = 0; sw = target.width; sh = th;
        }
        if (sx != lastScX || sy != lastScY || sw != lastScW || sh != lastScH) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
                sc.get(0).offset().set(sx, sy);
                sc.get(0).extent().set(Math.min(sw, target.width - sx), Math.min(sh, th - sy));
                vkCmdSetScissor(cb, 0, sc);
            }
            lastScX = sx; lastScY = sy; lastScW = sw; lastScH = sh;
        }

        int cull = VkRenderState.cullEnabled ? vkCullMode(VkRenderState.cullMode) : VK_CULL_MODE_NONE;
        if (cull != lastCull) {
            EXTExtendedDynamicState.vkCmdSetCullModeEXT(cb, cull);
            lastCull = cull;
        }
        if (lastFront != VK_FRONT_FACE_COUNTER_CLOCKWISE) {
            EXTExtendedDynamicState.vkCmdSetFrontFaceEXT(cb, VK_FRONT_FACE_COUNTER_CLOCKWISE);
            lastFront = VK_FRONT_FACE_COUNTER_CLOCKWISE;
        }
        if (vkTopology != lastTopo) {
            EXTExtendedDynamicState.vkCmdSetPrimitiveTopologyEXT(cb, vkTopology);
            lastTopo = vkTopology;
        }

        if (target.hasDepth) {
            int test = VkRenderState.depthTest ? 1 : 0;
            int write = VkRenderState.depthWrite ? 1 : 0;
            int compare = VkRenderState.depthTest ? (VkRenderState.depthFunc - 512) : VK_COMPARE_OP_ALWAYS;
            if (test != lastDepthTest) {
                EXTExtendedDynamicState.vkCmdSetDepthTestEnableEXT(cb, test == 1);
                lastDepthTest = test;
            }
            if (write != lastDepthWrite) {
                EXTExtendedDynamicState.vkCmdSetDepthWriteEnableEXT(cb, write == 1);
                lastDepthWrite = write;
            }
            if (compare != lastDepthCompare) {
                EXTExtendedDynamicState.vkCmdSetDepthCompareOpEXT(cb, compare);
                lastDepthCompare = compare;
            }
        }

        float biasFactor = VkRenderState.polygonOffsetEnabled ? VkRenderState.polygonOffsetFactor : 0f;
        float biasUnits = VkRenderState.polygonOffsetEnabled ? VkRenderState.polygonOffsetUnits : 0f;
        if (biasFactor != lastBiasFactor || biasUnits != lastBiasUnits) {
            vkCmdSetDepthBias(cb, biasUnits, 0.0f, biasFactor);
            lastBiasFactor = biasFactor;
            lastBiasUnits = biasUnits;
        }
    }

    private static int vkCullMode(int glMode) {
        switch (glMode) {
            case 1028: return VK_CULL_MODE_FRONT_BIT;
            case 1032: return VK_CULL_MODE_FRONT_AND_BACK;
            default: return VK_CULL_MODE_BACK_BIT;   // 1029
        }
    }

    private static void pushUniformsIfDirty(VkCommandBuffer cb) {
        if (!VkUniformBlock.isDirty()) return;
        int off = VkUniformBlock.dirtyOffset();
        int size = VkUniformBlock.dirtySize();
        org.lwjgl.vulkan.VK10.nvkCmdPushConstants(cb, VkPipelines.layout(),
                VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT,
                off, size, MemoryUtil.memAddress(VkUniformBlock.buffer()) + off);
        VkUniformBlock.markPushed();
    }

    private static void pushDescriptorsIfChanged(VkCommandBuffer cb, VkTextures.Tex tex0, VkTextures.Tex tex1) {
        // Compare by view handle too: a texture id can be reallocated (resource reload).
        if (tex0.id == pushedTex0 && tex1.id == pushedTex1
                && tex0.view == pushedView0 && tex1.view == pushedView1) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorImageInfo.Buffer img0 = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(tex0.view).sampler(VkTextures.sampler(tex0))
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkDescriptorImageInfo.Buffer img1 = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(tex1.view).sampler(VkTextures.sampler(tex1))
                    .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(2, stack);
            writes.get(0).sType$Default()
                    .dstBinding(0).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(img0);
            writes.get(1).sType$Default()
                    .dstBinding(1).descriptorCount(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(img1);
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_GRAPHICS,
                    VkPipelines.layout(), 0, writes);
        }
        pushedTex0 = tex0.id; pushedTex1 = tex1.id;
        pushedView0 = tex0.view; pushedView1 = tex1.view;
    }
}
