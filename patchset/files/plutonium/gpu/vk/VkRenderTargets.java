package plutonium.gpu.vk;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkClearAttachment;
import org.lwjgl.vulkan.VkClearRect;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Render-target management replacing GL FBOs. Every {@code Framebuffer} wraps a
 * Target (color + optional D32 depth); an internal "screen" Target stands in for
 * GL's default framebuffer (binding FBO 0). Draws happen inside a dynamic-rendering (or, on
 * drivers without it, classic VkRenderPasses)
 * pass on the current target, begun lazily by ensureRendering() and suspended for
 * uploads/blits/target switches (resume uses loadOp=LOAD, so suspend is free).
 *
 * present() blits the screen target to an acquired swapchain image, submits the
 * frame CB and presents — replacing both glfwSwapBuffers and the old PresentThread.
 */
public final class VkRenderTargets {

    public static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

    public static final class Target {
        public final int id;
        public int width, height;
        public final boolean hasDepth;
        public long colorImage, colorMemory, colorView;
        public long depthImage, depthMemory, depthView;
        long framebuffer;   // classic render-pass path only (no VK_KHR_dynamic_rendering)
        public int colorLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        public boolean depthInitialized;
        public VkTextures.Tex colorTex;
        boolean pendingClearColor = true, pendingClearDepth = true;
        float clearR, clearG, clearB, clearA;
        float clearDepthValue = 1.0f;

        Target(int id, boolean hasDepth) { this.id = id; this.hasDepth = hasDepth; }
    }

    private static final Map<Integer, Target> targets = new HashMap<Integer, Target>();
    private static int nextId = 1;

    private static Target screen;
    private static Target current;
    private static boolean renderingActive = false;
    private static Target renderingTarget;
    private static boolean swapchainDirty = false;

    private VkRenderTargets() {}

    public static void init() {
        screen = createTarget(VkSwapchain.width(), VkSwapchain.height(), true);
        current = screen;
    }

    public static Target createTarget(int width, int height, boolean depth) {
        Target t = new Target(nextId++, depth);
        allocate(t, width, height);
        targets.put(t.id, t);
        return t;
    }

    private static void allocate(Target t, int width, int height) {
        t.width = width;
        t.height = height;
        VkMemory.Img color = VkMemory.createImage2D(width, height, 1, VK_FORMAT_B8G8R8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                        | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
        t.colorImage = color.image;
        t.colorMemory = color.memory;
        t.colorView = VkTextures.createView(color.image, VK_FORMAT_B8G8R8A8_UNORM, 1, VK_IMAGE_ASPECT_COLOR_BIT);
        t.colorLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (t.colorTex == null) {
            t.colorTex = VkTextures.registerTarget(color.image, t.colorView, width, height, t);
        } else {
            t.colorTex.image = color.image;
            t.colorTex.view = t.colorView;
            t.colorTex.width = width;
            t.colorTex.height = height;
        }
        if (t.hasDepth) {
            VkMemory.Img depth = VkMemory.createImage2D(width, height, 1, DEPTH_FORMAT,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
            t.depthImage = depth.image;
            t.depthMemory = depth.memory;
            t.depthView = VkTextures.createView(depth.image, DEPTH_FORMAT, 1, VK_IMAGE_ASPECT_DEPTH_BIT);
            t.depthInitialized = false;
        }
        t.pendingClearColor = true;
        t.pendingClearDepth = true;
    }

    /** Resize in place (Framebuffer.createBindFramebuffer on an existing object). */
    public static void resizeTarget(Target t, int width, int height) {
        if (t.width == width && t.height == height && t.colorImage != 0) return;
        releaseImages(t);
        allocate(t, width, height);
    }

    public static void destroyTarget(Target t) {
        if (t == null) return;
        if (renderingTarget == t && renderingActive) suspendRendering();
        if (current == t) current = screen;
        releaseImages(t);
        targets.remove(t.id);
        if (t.colorTex != null) {
            t.colorTex.image = 0;
            t.colorTex.target = null;
        }
    }

    private static void releaseImages(Target t) {
        final long ci = t.colorImage, cm = t.colorMemory, cv = t.colorView;
        final long di = t.depthImage, dm = t.depthMemory, dv = t.depthView;
        final long fb = t.framebuffer;
        if (ci == 0) return;
        VkFrameRing.deferDestroy(new Runnable() {
            public void run() {
                if (fb != 0) vkDestroyFramebuffer(VkContext.device(), fb, null);
                vkDestroyImageView(VkContext.device(), cv, null);
                vkDestroyImage(VkContext.device(), ci, null);
                vkFreeMemory(VkContext.device(), cm, null);
                VkMemory.liveAllocations--;
                if (di != 0) {
                    vkDestroyImageView(VkContext.device(), dv, null);
                    vkDestroyImage(VkContext.device(), di, null);
                    vkFreeMemory(VkContext.device(), dm, null);
                    VkMemory.liveAllocations--;
                }
            }
        });
        t.colorImage = t.colorMemory = t.colorView = 0;
        t.depthImage = t.depthMemory = t.depthView = 0;
        t.framebuffer = 0;
    }

    public static Target byId(int id) { return id == 0 ? screen : targets.get(id); }
    public static Target screen() { return screen; }
    public static Target currentTarget() { return current; }
    public static boolean isRenderingActive() { return renderingActive; }

    public static void setCurrent(Target t) {
        if (t == null) t = screen;
        if (t == current) return;
        if (renderingActive) suspendRendering();
        current = t;
    }

    /** Record a clear of the current target (GL glClear semantics, honors current clear values). */
    public static void clear(boolean color, boolean depth) {
        Target t = current;
        if (color) {
            t.clearR = VkRenderState.clearR; t.clearG = VkRenderState.clearG;
            t.clearB = VkRenderState.clearB; t.clearA = VkRenderState.clearA;
        }
        if (depth) t.clearDepthValue = (float) VkRenderState.clearDepth;

        if (renderingActive && renderingTarget == t) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                int n = (color ? 1 : 0) + (depth && t.hasDepth ? 1 : 0);
                if (n == 0) return;
                VkClearAttachment.Buffer atts = VkClearAttachment.calloc(n, stack);
                int i = 0;
                if (color) {
                    atts.get(i).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).colorAttachment(0);
                    atts.get(i).clearValue().color()
                            .float32(0, t.clearR).float32(1, t.clearG).float32(2, t.clearB).float32(3, t.clearA);
                    i++;
                }
                if (depth && t.hasDepth) {
                    atts.get(i).aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                    atts.get(i).clearValue().depthStencil().depth(t.clearDepthValue);
                }
                VkClearRect.Buffer rect = VkClearRect.calloc(1, stack);
                rect.get(0).baseArrayLayer(0).layerCount(1);
                rect.get(0).rect().extent().set(t.width, t.height);
                vkCmdClearAttachments(VkFrameRing.commandBuffer(), atts, rect);
            }
        } else {
            if (color) t.pendingClearColor = true;
            if (depth) t.pendingClearDepth = true;
        }
    }

    /** Begin (or continue) dynamic rendering on the current target. Called before every draw. */
    public static VkCommandBuffer ensureRendering() {
        VkCommandBuffer cb = VkFrameRing.begin();
        Target t = current;
        if (renderingActive && renderingTarget == t) return cb;
        if (renderingActive) suspendRendering();

        if (t.colorLayout != VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL) {
            VkTextures.imageBarrier(cb, t.colorImage, t.colorLayout,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT);
            t.colorLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
            if (t.colorTex != null) t.colorTex.layout = t.colorLayout;
        }
        if (t.hasDepth && !t.depthInitialized) {
            VkTextures.imageBarrier(cb, t.depthImage, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, 1, VK_IMAGE_ASPECT_DEPTH_BIT);
            t.depthInitialized = true;
        }

        if (!VkContext.dynamicRendering()) {
            beginRenderPass(cb, t);
        } else try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRenderingAttachmentInfoKHR.Buffer color = VkRenderingAttachmentInfoKHR.calloc(1, stack);
            color.get(0).sType$Default()
                    .imageView(t.colorView)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(t.pendingClearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            color.get(0).clearValue().color()
                    .float32(0, t.clearR).float32(1, t.clearG).float32(2, t.clearB).float32(3, t.clearA);

            VkRenderingInfoKHR info = VkRenderingInfoKHR.calloc(stack)
                    .sType$Default()
                    .layerCount(1)
                    .pColorAttachments(color);
            info.renderArea().extent().set(t.width, t.height);

            VkRenderingAttachmentInfoKHR depth = null;
            if (t.hasDepth) {
                depth = VkRenderingAttachmentInfoKHR.calloc(stack)
                        .sType$Default()
                        .imageView(t.depthView)
                        .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .loadOp(t.pendingClearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                depth.clearValue().depthStencil().depth(t.clearDepthValue);
                info.pDepthAttachment(depth);
            }
            KHRDynamicRendering.vkCmdBeginRenderingKHR(cb, info);
        }
        t.pendingClearColor = false;
        t.pendingClearDepth = false;
        renderingActive = true;
        renderingTarget = t;
        return cb;
    }

    /** Classic-render-pass equivalent of the dynamic-rendering begin in ensureRendering. */
    private static void beginRenderPass(VkCommandBuffer cb, Target t) {
        if (t.framebuffer == 0) t.framebuffer = VkRenderPasses.createFramebuffer(t);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearValue.Buffer clears = VkClearValue.calloc(t.hasDepth ? 2 : 1, stack);
            clears.get(0).color()
                    .float32(0, t.clearR).float32(1, t.clearG).float32(2, t.clearB).float32(3, t.clearA);
            if (t.hasDepth) clears.get(1).depthStencil().depth(t.clearDepthValue);
            VkRenderPassBeginInfo info = VkRenderPassBeginInfo.calloc(stack)
                    .sType$Default()
                    .renderPass(VkRenderPasses.get(t.hasDepth, t.pendingClearColor, t.pendingClearDepth))
                    .framebuffer(t.framebuffer)
                    .pClearValues(clears);
            info.renderArea().extent().set(t.width, t.height);
            vkCmdBeginRenderPass(cb, info, VK_SUBPASS_CONTENTS_INLINE);
        }
    }

    /** End the active rendering pass (uploads, blits, target switches, present). */
    public static void suspendRendering() {
        if (!renderingActive) return;
        if (VkContext.dynamicRendering()) {
            KHRDynamicRendering.vkCmdEndRenderingKHR(VkFrameRing.commandBuffer());
        } else {
            vkCmdEndRenderPass(VkFrameRing.commandBuffer());
        }
        renderingActive = false;
        renderingTarget = null;
    }

    /** Transition a target's color image for sampling (framebuffer-texture binds). */
    public static void ensureShaderRead(Target t) {
        if (t.colorLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) return;
        if (renderingActive && renderingTarget == t) suspendRendering();
        else if (renderingActive) suspendRendering(); // barriers must be outside rendering
        VkCommandBuffer cb = VkFrameRing.begin();
        VkTextures.imageBarrier(cb, t.colorImage, t.colorLayout,
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT);
        t.colorLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        if (t.colorTex != null) t.colorTex.layout = t.colorLayout;
    }

    /** Blit src's color into dst (used by framebufferRenderExt: offscreen → screen). */
    public static void blit(Target src, Target dst, int dstW, int dstH, boolean linear) {
        suspendRendering();
        VkCommandBuffer cb = VkFrameRing.begin();
        if (src.colorLayout != VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
            VkTextures.imageBarrier(cb, src.colorImage, src.colorLayout,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT);
            src.colorLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            if (src.colorTex != null) src.colorTex.layout = src.colorLayout;
        }
        if (dst.colorLayout != VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            VkTextures.imageBarrier(cb, dst.colorImage, dst.colorLayout,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT);
            dst.colorLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            if (dst.colorTex != null) dst.colorTex.layout = dst.colorLayout;
        }
        recordBlit(cb, src.colorImage, src.width, src.height, dst.colorImage,
                Math.min(dstW, dst.width), Math.min(dstH, dst.height), linear);
        dst.pendingClearColor = false;
    }

    private static void recordBlit(VkCommandBuffer cb, long srcImage, int srcW, int srcH,
                                   long dstImage, int dstW, int dstH, boolean linear) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(srcW, srcH, 1);
            region.get(0).dstOffsets(1).set(dstW, dstH, 1);
            vkCmdBlitImage(cb, srcImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    dstImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region,
                    linear ? VK_FILTER_LINEAR : VK_FILTER_NEAREST);
        }
    }

    /**
     * Blit the screen target to the swapchain, submit the frame CB and present.
     * Handles OUT_OF_DATE/SUBOPTIMAL by recreating the swapchain (and screen target).
     *
     * With vsync off, presentation is decoupled from rendering (app-side MAILBOX;
     * MoltenVK only offers FIFO/IMMEDIATE): Metal throttles nextDrawable at
     * (drawableCount-1) x refresh, and MoltenVK surfaces that block inside
     * vkQueueSubmit, so presenting every frame caps the loop at ~240 on a 120 Hz
     * panel. Instead, frames between refresh intervals submit their work fence-only
     * and skip the swapchain entirely; the display gets the freshest completed frame
     * once per refresh, and the render loop runs at true CPU/GPU speed.
     */
    public static void present() {
        plutonium.gpu.FrameHitch.frame();
        if (plutonium.gpu.WindowManager.isMinimized()) {
            // Retire the frame fence-only and leave any pending recreate for the restore;
            // the sleep keeps an invisible window from spinning the render loop flat out.
            if (VkFrameRing.isOpen()) {
                suspendRendering();
                VkFrameRing.endAndSubmit(0, 0, 0);
            }
            try { Thread.sleep(16); } catch (InterruptedException ignored) {}
            return;
        }
        if (swapchainDirty) {
            recreateSwapchain();
        }
        if (!plutonium.gpu.WindowManager.isVSyncRequested() && !presentDue()) {
            if (VkFrameRing.isOpen()) {
                suspendRendering();
                VkFrameRing.endAndSubmit(0, 0, 0);
            }
            return;
        }
        VkCommandBuffer cb = VkFrameRing.begin();
        suspendRendering();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);
            int acquire = KHRSwapchain.vkAcquireNextImageKHR(VkContext.device(), VkSwapchain.handle(),
                    Long.MAX_VALUE, VkFrameRing.imageAvailableSemaphore(), VK_NULL_HANDLE, pImageIndex);
            if (acquire == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                swapchainDirty = true;
                VkFrameRing.flushAndWait();   // keep the recorded work; just skip presenting
                return;
            }
            // SUBOPTIMAL is a success code: the image is still presentable. MoltenVK reports
            // it persistently on Retina displays with COCOA_RETINA_FRAMEBUFFER off, so don't
            // recreate on it — real resizes arrive via WindowManager.wasResized().
            if (acquire != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                VkContext.check(acquire, "vkAcquireNextImageKHR");
            }
            int imageIndex = pImageIndex.get(0);
            long swapImage = VkSwapchain.image(imageIndex);

            if (screen.colorLayout != VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
                VkTextures.imageBarrier(cb, screen.colorImage, screen.colorLayout,
                        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT);
                screen.colorLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
                if (screen.colorTex != null) screen.colorTex.layout = screen.colorLayout;
            }
            VkTextures.imageBarrier(cb, swapImage, VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT);
            recordBlit(cb, screen.colorImage, screen.width, screen.height,
                    swapImage, VkSwapchain.width(), VkSwapchain.height(),
                    screen.width != VkSwapchain.width() || screen.height != VkSwapchain.height());
            VkTextures.imageBarrier(cb, swapImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, 1, VK_IMAGE_ASPECT_COLOR_BIT);

            long submitStartNs = System.nanoTime();
            VkFrameRing.endAndSubmit(VkFrameRing.imageAvailableSemaphore(),
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VkFrameRing.renderDoneSemaphore());

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.calloc(stack)
                    .sType$Default()
                    .pWaitSemaphores(stack.longs(VkFrameRing.renderDoneSemaphore()))
                    .swapchainCount(1)
                    .pSwapchains(stack.longs(VkSwapchain.handle()))
                    .pImageIndices(stack.ints(imageIndex));
            int result = KHRSwapchain.vkQueuePresentKHR(VkContext.queue(), presentInfo);
            reschedulePresent(submitStartNs);
            if (result == KHRSwapchain.VK_ERROR_OUT_OF_DATE_KHR) {
                swapchainDirty = true;
            } else if (result != KHRSwapchain.VK_SUBOPTIMAL_KHR) {
                VkContext.check(result, "vkQueuePresentKHR");
            }
        }
    }

    private static long nextPresentNs = 0;

    /** True once per display refresh interval — the pace at which the panel can
     *  actually take a new image, so acquire/present never hits Metal's throttle. */
    private static boolean presentDue() {
        return System.nanoTime() >= nextPresentNs;
    }

    /** Closed-loop reschedule, called after vkQueuePresentKHR returns. Open-loop
     *  pacing at the nominal integer refresh rate runs a hair fast (119.88 Hz
     *  panels, ProMotion), so Metal's drawable pool slowly fills; once full, the
     *  presenting frame's vkQueueSubmit blocks ~one refresh and stays that way —
     *  fps collapses to ~2x refresh until a swapchain recreate. Merely re-timing
     *  from the post-present timestamp is not enough: it converges to presenting
     *  at exactly panel pace with the pool still full (verified 2026-07-14,
     *  ~220 fps steady). So detect the block by timing submit+present, and skip
     *  the next slot so the pool drains a drawable — one duplicated panel frame
     *  every ~10s of accumulated drift instead of a permanent stall. */
    private static void reschedulePresent(long submitStartNs) {
        long after = System.nanoTime();
        long interval = 1_000_000_000L / Math.max(30, plutonium.gpu.WindowManager.getDesktopRefreshRate());
        if (after - submitStartNs > interval / 2) {
            nextPresentNs = after + 2 * interval;   // blocked: back off one slot to drain
        } else {
            // Drift-free open-loop pacing; the "after" floor resyncs after a stall.
            nextPresentNs = Math.max(nextPresentNs + interval, after);
        }
    }

    /**
     * Synchronous color readback (screenshots): copies the target's color image into
     * {@code out} as BGRA ints in GL orientation (row 0 = bottom), matching what the
     * old glGetTexImage returned so ScreenShotHelper's row-flip stays correct.
     * Submits and fences the in-flight CB — an acceptable hitch for a screenshot.
     */
    public static void readbackColor(Target t, java.nio.IntBuffer out) {
        suspendRendering();
        VkCommandBuffer cb = VkFrameRing.begin();
        if (t.colorLayout != VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL) {
            VkTextures.imageBarrier(cb, t.colorImage, t.colorLayout,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT);
            t.colorLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
            if (t.colorTex != null) t.colorTex.layout = t.colorLayout;
        }
        VkMemory.Buf host = VkMemory.createBuffer((long) t.width * t.height * 4,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferImageCopy.Buffer region =
                    org.lwjgl.vulkan.VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageExtent().set(t.width, t.height, 1);
            vkCmdCopyImageToBuffer(cb, t.colorImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    host.buffer, region);
        }
        VkFrameRing.flushAndWait();
        long rowBytes = (long) t.width * 4;
        long outAddr = org.lwjgl.system.MemoryUtil.memAddress(out);
        for (int row = 0; row < t.height; row++) {
            org.lwjgl.system.MemoryUtil.memCopy(host.hostPtr + row * rowBytes,
                    outAddr + (long) (t.height - 1 - row) * rowBytes, rowBytes);
        }
        out.position(0).limit(t.width * t.height);
        VkMemory.destroy(host);   // GPU already fenced idle for this copy
    }

    /** Mark the swapchain (and screen target) for recreation at the next present. */
    public static void markSwapchainDirty() { swapchainDirty = true; }

    private static void recreateSwapchain() {
        swapchainDirty = false;
        vkDeviceWaitIdle(VkContext.device());
        int w = Math.max(1, plutonium.gpu.WindowManager.getWidth());
        int h = Math.max(1, plutonium.gpu.WindowManager.getHeight());
        VkSwapchain.recreate(w, h, plutonium.gpu.WindowManager.isVSyncRequested());
        // Screen target follows the swapchain size (framebufferMc is resized by Minecraft.resize).
        resizeTarget(screen, VkSwapchain.width(), VkSwapchain.height());
    }
}
