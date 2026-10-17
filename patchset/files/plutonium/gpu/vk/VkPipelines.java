package plutonium.gpu.vk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.vertex.VertexFormat;
import net.minecraft.client.renderer.vertex.VertexFormatElement;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;
import org.lwjgl.vulkan.EXTExtendedDynamicState;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Keyed pipeline cache. One shared VkPipelineLayout (2 push-descriptor combined image
 * samplers + the 240-byte push-constant block) serves every pipeline, so push
 * constants and descriptors survive pipeline binds.
 *
 * Static per pipeline: program, vertex format, blend enable/factors, color mask,
 * topology *class* (EDS1 dynamic topology must stay within a class), target depth
 * presence. Dynamic: viewport, scissor, depth bias values, and via EDS1 cull mode,
 * front face, depth test/write/compare, exact topology. depthBiasEnable is baked
 * true everywhere — bias (0,0) is a no-op, and enabling it dynamically needs EDS2.
 */
public final class VkPipelines {

    public static final int PROG_POSITION_COLOR = 0;
    public static final int PROG_POSITION_TEX = 1;
    public static final int PROG_POSITION_TEX_COLOR = 2;
    public static final int PROG_BLOCK = 3;
    public static final int PROG_ENTITY = 4;
    public static final int PROG_ROUNDRECT = 5;
    public static final int PROG_BLUR = 6;
    public static final int PROGRAM_COUNT = 7;

    private static final String[] PROGRAM_NAMES = {
            "position_color", "position_tex", "position_tex_color", "block", "entity", "roundrect", "blur"};
    /** Attribute locations each program's vertex shader consumes (bitmask). */
    private static final int[] PROGRAM_INPUTS = {0b00011, 0b00101, 0b00111, 0b01111, 0b10111, 0b111111, 0};

    public static final int FORMAT_ROUNDRECT = 127;
    /** Attribute-less geometry (blur's fullscreen triangle): no vertex bindings at all. */
    public static final int FORMAT_NONE = 126;

    public static final int TOPO_CLASS_TRIANGLE = 0;
    public static final int TOPO_CLASS_LINE = 1;
    public static final int TOPO_CLASS_POINT = 2;

    private static final long[] vertModules = new long[PROGRAM_COUNT];
    private static final long[] fragModules = new long[PROGRAM_COUNT];
    private static long descriptorSetLayout;
    private static long pipelineLayout;

    private static final Map<Long, Long> cache = new HashMap<Long, Long>();
    private static final Map<VertexFormat, Integer> formatIds = new HashMap<VertexFormat, Integer>();
    private static final VertexFormat[] formatById = new VertexFormat[64];

    private VkPipelines() {}

    public static void init() {
        for (int i = 0; i < PROGRAM_COUNT; i++) {
            vertModules[i] = loadShaderModule("/assets/minecraft/shaders/vk/" + PROGRAM_NAMES[i] + ".vert.spv");
            fragModules[i] = loadShaderModule("/assets/minecraft/shaders/vk/" + PROGRAM_NAMES[i] + ".frag.spv");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(2, stack);
            for (int i = 0; i < 2; i++) {
                bindings.get(i)
                        .binding(i)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            VkDescriptorSetLayoutCreateInfo dslCi = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings);
            LongBuffer pHandle = stack.mallocLong(1);
            VkContext.check(vkCreateDescriptorSetLayout(VkContext.device(), dslCi, null, pHandle),
                    "vkCreateDescriptorSetLayout");
            descriptorSetLayout = pHandle.get(0);

            VkPushConstantRange.Buffer pcRange = VkPushConstantRange.calloc(1, stack);
            pcRange.get(0)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT)
                    .offset(0)
                    .size(VkUniformBlock.SIZE);
            VkPipelineLayoutCreateInfo plCi = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(descriptorSetLayout))
                    .pPushConstantRanges(pcRange);
            VkContext.check(vkCreatePipelineLayout(VkContext.device(), plCi, null, pHandle),
                    "vkCreatePipelineLayout");
            pipelineLayout = pHandle.get(0);
        }
    }

    public static long layout() { return pipelineLayout; }

    public static int formatId(VertexFormat format) {
        Integer id = formatIds.get(format);
        if (id != null) return id;
        int next = formatIds.size();
        if (next >= 64) throw new IllegalStateException("Too many distinct vertex formats");
        formatIds.put(format, next);
        formatById[next] = format;
        return next;
    }

    public static long getPipeline(int program, int formatId, int topoClass, boolean targetHasDepth) {
        boolean blend = VkRenderState.blendEnabled || VkRenderState.logicOpEnabled;
        int srcRGB, dstRGB, srcA, dstA;
        if (VkRenderState.logicOpEnabled) {
            // GL_INVERT logicOp emulation: out = 1 - dst (Metal has no logic ops).
            // 15 here was SRC1_COLOR — dual-source blending, which Metal rejects at
            // pipeline compile unless the shader writes color(0) index(1). It crashed
            // the first time the chat caret drew mid-text.
            srcRGB = srcA = 5;   // VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR
            dstRGB = dstA = 0;   // VK_BLEND_FACTOR_ZERO
        } else {
            srcRGB = vkBlendFactor(VkRenderState.blendSrcRGB);
            dstRGB = vkBlendFactor(VkRenderState.blendDstRGB);
            srcA = vkBlendFactor(VkRenderState.blendSrcA);
            dstA = vkBlendFactor(VkRenderState.blendDstA);
        }
        int mask = (VkRenderState.maskR ? 1 : 0) | (VkRenderState.maskG ? 2 : 0)
                | (VkRenderState.maskB ? 4 : 0) | (VkRenderState.maskA ? 8 : 0);

        long key = program
                | ((long) formatId << 3)
                | ((blend ? 1L : 0L) << 10)
                | ((long) srcRGB << 11) | ((long) dstRGB << 16)
                | ((long) srcA << 21) | ((long) dstA << 26)
                | ((long) mask << 31)
                | ((long) topoClass << 35)
                | ((targetHasDepth ? 1L : 0L) << 37);

        Long cached = cache.get(key);
        if (cached != null) return cached;
        long pipeline;
        try {
            pipeline = build(program, formatId, topoClass, targetHasDepth, blend, srcRGB, dstRGB, srcA, dstA, mask);
        } catch (RuntimeException e) {
            // A rejected variant must never take down the session mid-frame (a bad blend
            // combo once crashed 3.5h in, on the first chat-caret draw). Fall back to the
            // same pipeline with standard alpha blending: wrong for one draw, but visible
            // and survivable. Cached so the error logs once per variant.
            System.err.println("[VK] pipeline variant rejected, using alpha-blend fallback: " + e.getMessage());
            pipeline = build(program, formatId, topoClass, targetHasDepth, true, 6, 7, 1, 0, mask);
        }
        cache.put(key, pipeline);
        return pipeline;
    }

    private static long build(int program, int formatId, int topoClass, boolean targetHasDepth,
                              boolean blend, int srcRGB, int dstRGB, int srcA, int dstA, int mask) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertModules[program]).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragModules[program]).pName(stack.UTF8("main"));

            VkPipelineVertexInputStateCreateInfo vertexInput = buildVertexInput(stack, program, formatId);

            int topology = topoClass == TOPO_CLASS_LINE ? VK_PRIMITIVE_TOPOLOGY_LINE_LIST
                    : topoClass == TOPO_CLASS_POINT ? VK_PRIMITIVE_TOPOLOGY_POINT_LIST
                    : VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                    VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(topology);

            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                    .sType$Default().viewportCount(1).scissorCount(1);

            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .polygonMode(VK_POLYGON_MODE_FILL)
                    .cullMode(VK_CULL_MODE_NONE)                 // dynamic (EDS1)
                    .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)  // dynamic (EDS1)
                    .depthBiasEnable(true)                       // values dynamic; (0,0) = off
                    .lineWidth(1.0f);

            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                    .sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

            VkPipelineDepthStencilStateCreateInfo depthStencil = null;
            if (targetHasDepth) {
                depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                        .sType$Default();  // test/write/compare all dynamic (EDS1)
            }

            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(1, stack);
            blendAttachment.get(0)
                    .blendEnable(blend)
                    .srcColorBlendFactor(srcRGB).dstColorBlendFactor(dstRGB).colorBlendOp(VK_BLEND_OP_ADD)
                    .srcAlphaBlendFactor(srcA).dstAlphaBlendFactor(dstA).alphaBlendOp(VK_BLEND_OP_ADD)
                    .colorWriteMask(mask);
            VkPipelineColorBlendStateCreateInfo blendState = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                    .sType$Default().pAttachments(blendAttachment);

            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType$Default()
                    .pDynamicStates(stack.ints(
                            VK_DYNAMIC_STATE_VIEWPORT,
                            VK_DYNAMIC_STATE_SCISSOR,
                            VK_DYNAMIC_STATE_DEPTH_BIAS,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_CULL_MODE_EXT,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_FRONT_FACE_EXT,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY_EXT,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE_EXT,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE_EXT,
                            EXTExtendedDynamicState.VK_DYNAMIC_STATE_DEPTH_COMPARE_OP_EXT));

            // Dynamic rendering describes attachment formats inline; the classic path builds
            // against a compatible render pass instead (VkRenderPasses).
            boolean dynamicRendering = VkContext.dynamicRendering();
            VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .colorAttachmentCount(1)
                    .pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM));
            if (targetHasDepth) rendering.depthAttachmentFormat(VkRenderTargets.DEPTH_FORMAT);

            VkGraphicsPipelineCreateInfo.Buffer ci = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            ci.get(0).sType$Default()
                    .pNext(dynamicRendering ? rendering.address() : 0L)
                    .pStages(stages)
                    .pVertexInputState(vertexInput)
                    .pInputAssemblyState(inputAssembly)
                    .pViewportState(viewport)
                    .pRasterizationState(raster)
                    .pMultisampleState(multisample)
                    .pDepthStencilState(depthStencil)
                    .pColorBlendState(blendState)
                    .pDynamicState(dynamic)
                    .layout(pipelineLayout)
                    .renderPass(dynamicRendering ? VK_NULL_HANDLE : VkRenderPasses.compatible(targetHasDepth));

            LongBuffer pPipeline = stack.mallocLong(1);
            VkContext.check(vkCreateGraphicsPipelines(VkContext.device(), VK_NULL_HANDLE, ci, null, pPipeline),
                    "vkCreateGraphicsPipelines(" + PROGRAM_NAMES[program] + ")");
            return pPipeline.get(0);
        }
    }

    /**
     * Binding 0: the interleaved vertex stream. Binding 1: a tiny constants buffer
     * (white color at offset 0, zeros after) with stride 0 — Vulkan reads the same
     * bytes for every vertex, filling attribute locations the shader consumes but
     * the vertex format doesn't provide (e.g. Color for POSITION-only sky geometry).
     */
    private static VkPipelineVertexInputStateCreateInfo buildVertexInput(MemoryStack stack, int program, int formatId) {
        int consumed = PROGRAM_INPUTS[program];
        if (consumed == 0) {
            return VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
        }

        VkVertexInputAttributeDescription.Buffer attrs = VkVertexInputAttributeDescription.calloc(8, stack);
        int attrCount = 0;
        int providedMask = 0;
        int stride;
        boolean needConstants = false;

        if (formatId == FORMAT_ROUNDRECT) {
            // All-float layout from RoundRectBatch: pos3, local2, half2, params3, fill4, border4.
            stride = 18 * 4;
            int[] counts = {3, 2, 2, 3, 4, 4};
            int off = 0;
            for (int loc = 0; loc < 6; loc++) {
                attrs.get(attrCount++).location(loc).binding(0).format(floatFormat(counts[loc])).offset(off);
                off += counts[loc] * 4;
                providedMask |= 1 << loc;
            }
        } else {
            VertexFormat format = formatById[formatId];
            stride = format.getNextOffset();
            List<VertexFormatElement> elements = format.getElements();
            for (int j = 0; j < elements.size(); j++) {
                VertexFormatElement e = elements.get(j);
                VertexFormatElement.EnumUsage usage = e.getUsage();
                if (usage == VertexFormatElement.EnumUsage.PADDING) continue;
                int location = attribLocation(usage, e.getIndex());
                if (location < 0 || (consumed & (1 << location)) == 0) continue;
                attrs.get(attrCount++)
                        .location(location).binding(0)
                        .format(vkAttribFormat(e.getType().getGlConstant(), e.getElementCount(), usage))
                        .offset(format.func_181720_d(j));
                providedMask |= 1 << location;
            }
        }

        // Constants-binding fallback for consumed-but-missing locations.
        for (int loc = 0; loc < 6; loc++) {
            if ((consumed & (1 << loc)) == 0 || (providedMask & (1 << loc)) != 0) continue;
            needConstants = true;
            int fmt;
            int off;
            if (loc == 1) { fmt = VK_FORMAT_R8G8B8A8_UNORM; off = 0; }        // white color
            else if (loc == 3) { fmt = VK_FORMAT_R16G16_SINT; off = 4; }      // zero lightmap
            else if (loc == 4) { fmt = VK_FORMAT_R8G8B8_SNORM; off = 4; }     // zero normal
            else { fmt = VK_FORMAT_R32G32_SFLOAT; off = 4; }                  // zero UV
            attrs.get(attrCount++).location(loc).binding(1).format(fmt).offset(off);
        }

        VkVertexInputBindingDescription.Buffer bindings =
                VkVertexInputBindingDescription.calloc(needConstants ? 2 : 1, stack);
        bindings.get(0).binding(0).stride(stride).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        if (needConstants) {
            bindings.get(1).binding(1).stride(0).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        }
        attrs.limit(attrCount);

        return VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default()
                .pVertexBindingDescriptions(bindings)
                .pVertexAttributeDescriptions(attrs);
    }

    public static int attribLocation(VertexFormatElement.EnumUsage usage, int index) {
        switch (usage) {
            case POSITION: return 0;
            case COLOR: return 1;
            case UV: return index == 0 ? 2 : (index == 1 ? 3 : -1);
            case NORMAL: return 4;
            default: return -1;
        }
    }

    private static int floatFormat(int count) {
        switch (count) {
            case 1: return VK_FORMAT_R32_SFLOAT;
            case 2: return VK_FORMAT_R32G32_SFLOAT;
            case 3: return VK_FORMAT_R32G32B32_SFLOAT;
            default: return VK_FORMAT_R32G32B32A32_SFLOAT;
        }
    }

    private static int vkAttribFormat(int glType, int count, VertexFormatElement.EnumUsage usage) {
        switch (glType) {
            case 5126: return floatFormat(count);                                    // GL_FLOAT
            case 5121: return count == 4 ? VK_FORMAT_R8G8B8A8_UNORM : VK_FORMAT_R8G8B8_UNORM;  // GL_UNSIGNED_BYTE (color)
            case 5120: return count == 3 ? VK_FORMAT_R8G8B8_SNORM : VK_FORMAT_R8G8B8A8_SNORM;  // GL_BYTE (normal)
            case 5122: return VK_FORMAT_R16G16_SINT;                                 // GL_SHORT (ivec2 lightmap)
            default:
                throw new IllegalStateException("Unmapped vertex attrib type " + glType + " x" + count + " " + usage);
        }
    }

    /** GL blend factor enum → VkBlendFactor. */
    public static int vkBlendFactor(int gl) {
        switch (gl) {
            case 0: return VK_BLEND_FACTOR_ZERO;
            case 1: return VK_BLEND_FACTOR_ONE;
            case 768: return VK_BLEND_FACTOR_SRC_COLOR;
            case 769: return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case 770: return VK_BLEND_FACTOR_SRC_ALPHA;
            case 771: return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case 772: return VK_BLEND_FACTOR_DST_ALPHA;
            case 773: return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case 774: return VK_BLEND_FACTOR_DST_COLOR;
            case 775: return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case 776: return VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case 32769: return VK_BLEND_FACTOR_CONSTANT_COLOR;
            case 32770: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case 32771: return VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case 32772: return VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            default: return VK_BLEND_FACTOR_ONE;
        }
    }

    /** Load a committed .spv from the classpath (src/minecraft is on CP_RUN). */
    public static long loadShaderModule(String resourcePath) {
        byte[] bytes = readResource(resourcePath);
        ByteBuffer code = MemoryUtil.memAlloc(bytes.length);
        code.put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkShaderModuleCreateInfo ci = VkShaderModuleCreateInfo.calloc(stack)
                    .sType$Default()
                    .pCode(code);
            LongBuffer pModule = stack.mallocLong(1);
            VkContext.check(vkCreateShaderModule(VkContext.device(), ci, null, pModule),
                    "vkCreateShaderModule " + resourcePath);
            return pModule.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static byte[] readResource(String resourcePath) {
        InputStream in = VkPipelines.class.getResourceAsStream(resourcePath);
        if (in == null) {
            throw new IllegalStateException("SPIR-V resource not found on classpath: " + resourcePath
                    + " (run tools/compile-shaders.sh?)");
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading " + resourcePath, e);
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
