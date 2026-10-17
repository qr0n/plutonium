package plutonium.gpu.vk;

import java.nio.LongBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAttachmentDescription;
import org.lwjgl.vulkan.VkAttachmentReference;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.lwjgl.vulkan.VkRenderPassCreateInfo;
import org.lwjgl.vulkan.VkSubpassDependency;
import org.lwjgl.vulkan.VkSubpassDescription;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Classic render passes for drivers without VK_KHR_dynamic_rendering (NVIDIA Kepler's
 * Vulkan 1.2 driver). VkRenderTargets keeps its dynamic-rendering model — attachments
 * already sit in ATTACHMENT_OPTIMAL when a pass begins, and resume uses loadOp=LOAD — so
 * each pass here keeps initialLayout == finalLayout and only varies the load ops.
 *
 * One pass per (hasDepth, clearColor, clearDepth): at most 6. Load ops don't affect
 * render-pass compatibility, so pipelines are built against the all-LOAD variant and run
 * inside any of them; framebuffers likewise.
 */
public final class VkRenderPasses {

    private static final int COLOR_FORMAT = VK_FORMAT_B8G8R8A8_UNORM;
    private static final long[] passes = new long[8];

    private VkRenderPasses() {}

    /** The pass a draw pipeline is created against (compatible with every load-op variant). */
    public static long compatible(boolean hasDepth) {
        return get(hasDepth, false, false);
    }

    public static long get(boolean hasDepth, boolean clearColor, boolean clearDepth) {
        int key = (hasDepth ? 4 : 0) | (clearColor ? 2 : 0) | (hasDepth && clearDepth ? 1 : 0);
        if (passes[key] == 0) passes[key] = create(hasDepth, clearColor, hasDepth && clearDepth);
        return passes[key];
    }

    private static long create(boolean hasDepth, boolean clearColor, boolean clearDepth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAttachmentDescription.Buffer atts = VkAttachmentDescription.calloc(hasDepth ? 2 : 1, stack);
            atts.get(0)
                    .format(COLOR_FORMAT)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(clearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            if (hasDepth) {
                atts.get(1)
                        .format(VkRenderTargets.DEPTH_FORMAT)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(clearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }

            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorRef);
            if (hasDepth) {
                VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack)
                        .attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                subpass.get(0).pDepthStencilAttachment(depthRef);
            }

            // Order this pass against earlier passes/uploads on the same images and against
            // the sampling/blits that follow it — the explicit barriers in VkRenderTargets
            // only cover layout changes, which these passes never make.
            int attachStages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                    | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            int attachWrites = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            int attachAccess = attachWrites
                    | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT;
            VkSubpassDependency.Buffer deps = VkSubpassDependency.calloc(2, stack);
            deps.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                    .srcStageMask(attachStages | VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .srcAccessMask(attachWrites | VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstStageMask(attachStages | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT)
                    .dstAccessMask(attachAccess | VK_ACCESS_SHADER_READ_BIT);
            deps.get(1)
                    .srcSubpass(0).dstSubpass(VK_SUBPASS_EXTERNAL)
                    .srcStageMask(attachStages)
                    .srcAccessMask(attachWrites)
                    .dstStageMask(attachStages | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT)
                    .dstAccessMask(attachAccess | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT);

            VkRenderPassCreateInfo ci = VkRenderPassCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(atts)
                    .pSubpasses(subpass)
                    .pDependencies(deps);
            LongBuffer p = stack.mallocLong(1);
            VkContext.check(vkCreateRenderPass(VkContext.device(), ci, null, p), "vkCreateRenderPass");
            return p.get(0);
        }
    }

    /** Framebuffer over a target's current views; caller owns it and destroys it with the views. */
    public static long createFramebuffer(VkRenderTargets.Target t) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer views = t.hasDepth ? stack.longs(t.colorView, t.depthView) : stack.longs(t.colorView);
            VkFramebufferCreateInfo ci = VkFramebufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(compatible(t.hasDepth))
                    .pAttachments(views)
                    .width(t.width)
                    .height(t.height)
                    .layers(1);
            LongBuffer p = stack.mallocLong(1);
            VkContext.check(vkCreateFramebuffer(VkContext.device(), ci, null, p), "vkCreateFramebuffer");
            return p.get(0);
        }
    }
}
