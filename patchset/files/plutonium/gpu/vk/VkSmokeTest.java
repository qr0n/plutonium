package plutonium.gpu.vk;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;

import static org.lwjgl.vulkan.VK10.*;

/**
 * M0 smoke test: standalone entry point that brings up MoltenVK inside a GLFW_NO_API
 * window, clears the swapchain to magenta and draws one shader-generated triangle
 * under a negative-height (GL-convention) viewport. Run via `./launch.sh --vk-smoke`.
 * Optional arg: max frame count before auto-exit (for scripted verification).
 */
public final class VkSmokeTest {

    private static boolean framebufferResized = false;

    public static void main(String[] args) {
        long maxFrames = args.length > 0 ? Long.parseLong(args[0]) : Long.MAX_VALUE;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!GLFW.glfwInit()) throw new IllegalStateException("glfwInit failed");

        // Point GLFW at LWJGL's Vulkan (MoltenVK from the natives dir) — there is no
        // system Vulkan loader on macOS for GLFW to discover on its own.
        GLFWVulkan.glfwInitVulkanLoader(VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"));
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("glfwVulkanSupported() == false — MoltenVK not reachable");
        }

        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API);
        GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, GLFW.GLFW_TRUE);
        GLFW.glfwWindowHint(GLFW.GLFW_COCOA_RETINA_FRAMEBUFFER, GLFW.GLFW_FALSE);
        long window = GLFW.glfwCreateWindow(960, 600, "Plutonium — Vulkan smoke test", MemoryUtil.NULL, MemoryUtil.NULL);
        if (window == MemoryUtil.NULL) throw new IllegalStateException("glfwCreateWindow failed");
        GLFW.glfwSetFramebufferSizeCallback(window, (w, fw, fh) -> framebufferResized = true);

        VkContext.init(window);
        int[] fb = framebufferSize(window);
        VkSwapchain.create(fb[0], fb[1], true);
        VkFrameRing.init();

        long frames = 0;
        while (!GLFW.glfwWindowShouldClose(window) && frames < maxFrames) {
            GLFW.glfwPollEvents();
            fb = framebufferSize(window);
            if (fb[0] == 0 || fb[1] == 0) continue; // minimized

            if (framebufferResized) {
                framebufferResized = false;
                VkSwapchain.recreate(fb[0], fb[1], VkSwapchain.isVsync());
                continue;
            }
            if (drawFrame()) {
                frames++;
            } else {
                VkSwapchain.recreate(fb[0], fb[1], VkSwapchain.isVsync());
            }
        }

        vkDeviceWaitIdle(VkContext.device());
        VkFrameRing.destroy();
        VkSwapchain.destroy();
        VkContext.shutdown();
        GLFW.glfwDestroyWindow(window);
        GLFW.glfwTerminate();
        System.out.println("[VK] smoke test done, frames presented: " + frames);
    }

    /** @return true if the frame was presented; false if the swapchain must be recreated. */
    private static boolean drawFrame() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cb = VkFrameRing.begin();

            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquire = KHRSwapchain.vkAcquireNextImageKHR(VkContext.device(), VkSwapchain.handle(),
                    Long.MAX_VALUE, VkFrameRing.imageAvailableSemaphore(), VK_NULL_HANDLE, pImageIndex);
            if (acquire == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                VkFrameRing.endAndSubmit(0, 0, 0);   // close the CB; fence keeps the slot correct
                return false;
            }
            VkContext.check(acquire, "vkAcquireNextImageKHR");
            int imageIndex = pImageIndex.get(0);
            long image = VkSwapchain.image(imageIndex);
            int w = VkSwapchain.width(), h = VkSwapchain.height();

            imageBarrier(cb, image, stack,
                    0, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);

            VkRenderingAttachmentInfoKHR.Buffer color = VkRenderingAttachmentInfoKHR.calloc(1, stack);
            color.get(0).sType$Default()
                    .imageView(VkSwapchain.view(imageIndex))
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.get(0).clearValue().color()
                    .float32(0, 1.0f).float32(1, 0.0f).float32(2, 1.0f).float32(3, 1.0f);

            VkRenderingInfoKHR renderingInfo = VkRenderingInfoKHR.calloc(stack)
                    .sType$Default()
                    .layerCount(1)
                    .pColorAttachments(color);
            renderingInfo.renderArea().extent().set(w, h);

            KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, renderingInfo);
            KHRDynamicRendering.vkCmdEndRenderingKHR(cb);

            imageBarrier(cb, image, stack,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, 0,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

            VkFrameRing.endAndSubmit(VkFrameRing.imageAvailableSemaphore(),
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VkFrameRing.renderDoneSemaphore());

            VkPresentInfoKHR present = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(VkFrameRing.renderDoneSemaphore()))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(VkSwapchain.handle()))
                    .pImageIndices(stack.ints(imageIndex));
            int result = KHRSwapchain.vkQueuePresentKHR(VkContext.queue(), present);
            if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR || result == KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                return false;
            }
            VkContext.check(result, "vkQueuePresentKHR");
            return true;
        }
    }

    private static void imageBarrier(VkCommandBuffer cb, long image, MemoryStack stack,
                                     int srcAccess, int dstAccess, int oldLayout, int newLayout,
                                     int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default()
                .srcAccessMask(srcAccess)
                .dstAccessMask(dstAccess)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);
        barrier.get(0).subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cb, srcStage, dstStage, 0, null, null, barrier);
    }

    private static int[] framebufferSize(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            GLFW.glfwGetFramebufferSize(window, w, h);
            return new int[]{w.get(0), h.get(0)};
        }
    }
}
