package plutonium.gpu.vk;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Frames in flight (see FRAME_COUNT). Each frame slot owns a command pool + primary CB,
 * acquire/render-done semaphores, an in-flight fence, a growable host-visible
 * vertex ring (replaces GL's STREAM_DRAW orphaning — suballocate per draw, bind at
 * offset) and a staging ring for texture uploads. Resources retired mid-frame
 * (old textures, resized buffers) go on the slot's deferred-destroy list and are
 * freed only after that slot's fence proves the GPU is done with them.
 *
 * The CB opens lazily on first use (clear/draw/upload) and is submitted by
 * VkRenderTargets.present(); {@link #generation()} bumps on every open so
 * dispatch can invalidate its per-CB binding caches.
 */
public final class VkFrameRing {

    // Frames in flight. At 2 the CPU could only run one frame ahead of the GPU, so begin()'s
    // vkWaitForFences blocked ~100us every frame; the stall landed on the frame's first draw,
    // which made the sky bucket look expensive when it was really a GPU wait. 3 costs one more
    // slot's rings (~24MB) and cut the wait to ~18us: rd=12 went 2395 -> 2904 fps. A 4th slot
    // buys <5% for another 24MB.
    public static final int FRAME_COUNT = 3;
    private static final long VERTEX_BLOCK_SIZE = 8L * 1024 * 1024;
    private static final long STAGING_BLOCK_SIZE = 16L * 1024 * 1024;

    /** A suballocation in a host-visible ring block. */
    public static final class Alloc {
        public long buffer;     // VkBuffer
        public long offset;
        public long hostPtr;    // write vertex/pixel data here
    }

    private static final class Ring {
        final List<VkMemory.Buf> blocks = new ArrayList<VkMemory.Buf>();
        final long blockSize;
        final int usage;
        int blockIndex;
        long offset;
        Ring(long blockSize, int usage) { this.blockSize = blockSize; this.usage = usage; }

        void reset() { blockIndex = 0; offset = 0; }

        void alloc(long size, Alloc out) {
            long aligned = (offset + 63) & ~63L;
            if (blocks.isEmpty() || aligned + size > blocks.get(blockIndex).size) {
                if (!blocks.isEmpty()) blockIndex++;
                if (blockIndex >= blocks.size()) {
                    blocks.add(VkMemory.createBuffer(Math.max(blockSize, size), usage, true));
                } else if (blocks.get(blockIndex).size < size) {
                    // Reused block from an earlier frame is too small for this alloc.
                    final VkMemory.Buf old = blocks.set(blockIndex, VkMemory.createBuffer(size, usage, true));
                    deferDestroy(new Runnable() { public void run() { VkMemory.destroy(old); } });
                }
                aligned = 0;
            }
            VkMemory.Buf block = blocks.get(blockIndex);
            out.buffer = block.buffer;
            out.offset = aligned;
            out.hostPtr = block.hostPtr + aligned;
            offset = aligned + size;
        }

        void destroy() {
            for (VkMemory.Buf b : blocks) VkMemory.destroy(b);
            blocks.clear();
        }
    }

    private static final long[] pools = new long[FRAME_COUNT];
    private static final VkCommandBuffer[] commandBuffers = new VkCommandBuffer[FRAME_COUNT];
    private static final long[] imageAvailable = new long[FRAME_COUNT];
    private static final long[] renderDone = new long[FRAME_COUNT];
    private static final long[] fences = new long[FRAME_COUNT];
    @SuppressWarnings("unchecked")
    private static final List<Runnable>[] deferred = new List[FRAME_COUNT];
    private static final Ring[] vertexRings = new Ring[FRAME_COUNT];
    private static final Ring[] stagingRings = new Ring[FRAME_COUNT];

    private static int frame = 0;
    private static boolean open = false;
    private static long gen = 0;

    private VkFrameRing() {}

    public static void init() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolCi = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .queueFamilyIndex(VkContext.queueFamily());
            VkSemaphoreCreateInfo semCi = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceCi = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);

            java.nio.LongBuffer pHandle = stack.mallocLong(1);
            for (int i = 0; i < FRAME_COUNT; i++) {
                VkContext.check(vkCreateCommandPool(VkContext.device(), poolCi, null, pHandle), "vkCreateCommandPool");
                pools[i] = pHandle.get(0);

                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType$Default()
                        .commandPool(pools[i])
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);
                PointerBuffer pCb = stack.mallocPointer(1);
                VkContext.check(vkAllocateCommandBuffers(VkContext.device(), allocInfo, pCb), "vkAllocateCommandBuffers");
                commandBuffers[i] = new VkCommandBuffer(pCb.get(0), VkContext.device());

                VkContext.check(vkCreateSemaphore(VkContext.device(), semCi, null, pHandle), "vkCreateSemaphore");
                imageAvailable[i] = pHandle.get(0);
                VkContext.check(vkCreateSemaphore(VkContext.device(), semCi, null, pHandle), "vkCreateSemaphore");
                renderDone[i] = pHandle.get(0);
                VkContext.check(vkCreateFence(VkContext.device(), fenceCi, null, pHandle), "vkCreateFence");
                fences[i] = pHandle.get(0);

                deferred[i] = new ArrayList<Runnable>();
                vertexRings[i] = new Ring(VERTEX_BLOCK_SIZE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                stagingRings[i] = new Ring(STAGING_BLOCK_SIZE, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            }
        }
    }

    /** Open (if needed) and return the current frame's CB. */
    public static VkCommandBuffer begin() {
        if (open) return commandBuffers[frame];
        frame = (frame + 1) % FRAME_COUNT;
        vkWaitForFences(VkContext.device(), fences[frame], true, Long.MAX_VALUE);
        vkResetFences(VkContext.device(), fences[frame]);
        runDeferred(frame);
        vkResetCommandPool(VkContext.device(), pools[frame], 0);
        vertexRings[frame].reset();
        stagingRings[frame].reset();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            VkContext.check(vkBeginCommandBuffer(commandBuffers[frame], beginInfo), "vkBeginCommandBuffer");
        }
        open = true;
        gen++;
        VkUniformBlock.invalidate();
        return commandBuffers[frame];
    }

    public static boolean isOpen() { return open; }
    public static long generation() { return gen; }
    public static VkCommandBuffer commandBuffer() { return commandBuffers[frame]; }
    public static long imageAvailableSemaphore() { return imageAvailable[frame]; }
    public static long renderDoneSemaphore() { return renderDone[frame]; }

    public static void allocVertex(long size, Alloc out) { begin(); vertexRings[frame].alloc(size, out); }
    public static void allocStaging(long size, Alloc out) { begin(); stagingRings[frame].alloc(size, out); }

    /** Queue a destroy that runs once this frame's fence signals. Safe outside a frame too. */
    public static void deferDestroy(Runnable r) {
        deferred[frame].add(r);
    }

    /** End the open CB and submit. waitSemaphore may be 0 (no wait). Returns false if nothing was open. */
    public static boolean endAndSubmit(long waitSemaphore, int waitStage, long signalSemaphore) {
        if (!open) return false;
        VkContext.check(vkEndCommandBuffer(commandBuffers[frame]), "vkEndCommandBuffer");
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                    .sType$Default()
                    .pCommandBuffers(stack.pointers(commandBuffers[frame]));
            if (waitSemaphore != 0) {
                submit.waitSemaphoreCount(1)
                        .pWaitSemaphores(stack.longs(waitSemaphore))
                        .pWaitDstStageMask(stack.ints(waitStage));
            }
            if (signalSemaphore != 0) {
                submit.pSignalSemaphores(stack.longs(signalSemaphore));
            }
            VkContext.check(vkQueueSubmit(VkContext.queue(), submit, fences[frame]), "vkQueueSubmit");
        }
        open = false;
        return true;
    }

    /** Submit the open CB and block until the GPU finishes it (uploads before first present, readbacks). */
    public static void flushAndWait() {
        if (!open) return;
        endAndSubmit(0, 0, 0);
        vkWaitForFences(VkContext.device(), fences[frame], true, Long.MAX_VALUE);
        // Slot stays usable: begin() will advance to the next slot as usual.
    }

    private static void runDeferred(int slot) {
        List<Runnable> list = deferred[slot];
        for (int i = 0; i < list.size(); i++) list.get(i).run();
        list.clear();
    }

    public static void destroy() {
        vkDeviceWaitIdle(VkContext.device());
        for (int i = 0; i < FRAME_COUNT; i++) {
            runDeferred(i);
            vertexRings[i].destroy();
            stagingRings[i].destroy();
            if (fences[i] != VK_NULL_HANDLE) vkDestroyFence(VkContext.device(), fences[i], null);
            if (imageAvailable[i] != VK_NULL_HANDLE) vkDestroySemaphore(VkContext.device(), imageAvailable[i], null);
            if (renderDone[i] != VK_NULL_HANDLE) vkDestroySemaphore(VkContext.device(), renderDone[i], null);
            if (pools[i] != VK_NULL_HANDLE) vkDestroyCommandPool(VkContext.device(), pools[i], null);
            fences[i] = imageAvailable[i] = renderDone[i] = pools[i] = VK_NULL_HANDLE;
            commandBuffers[i] = null;
        }
    }
}
