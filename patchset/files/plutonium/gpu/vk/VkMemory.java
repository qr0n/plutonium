package plutonium.gpu.vk;

import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Thin allocator. Apple Silicon is UMA — every heap is DEVICE_LOCAL|HOST_VISIBLE —
 * so host-visible buffers are also fast device memory and are kept persistently
 * mapped (no staging needed for vertex/uniform data; images stay OPTIMAL-tiled and
 * go through a staging buffer copy).
 */
public final class VkMemory {

    /**
     * A buffer + its memory; hostPtr != 0 when host-visible (persistently mapped). Pooled
     * vertex buffers are slices of a shared slab: {@code offset} is where the slice starts
     * in {@code buffer} (hostPtr already includes it) and {@code memory} is 0, so destroy()
     * must never see them.
     */
    public static final class Buf {
        public final long buffer, memory, size, hostPtr, offset;
        Buf(long buffer, long memory, long size, long hostPtr) {
            this(buffer, memory, size, hostPtr, 0L);
        }
        Buf(long buffer, long memory, long size, long hostPtr, long offset) {
            this.buffer = buffer; this.memory = memory; this.size = size; this.hostPtr = hostPtr;
            this.offset = offset;
        }
    }

    /** An image + its memory. */
    public static final class Img {
        public final long image, memory;
        Img(long image, long memory) { this.image = image; this.memory = memory; }
    }

    private static VkPhysicalDeviceMemoryProperties memProps;

    /** Live vkAllocateMemory count; Windows drivers cap it at maxMemoryAllocationCount (4096). */
    public static int liveAllocations;
    /** vkAllocateMemory calls ever made; FrameHitch diffs it to blame stutters on allocation. */
    public static long totalAllocations;

    private VkMemory() {}

    public static void init() {
        memProps = VkPhysicalDeviceMemoryProperties.malloc();
        vkGetPhysicalDeviceMemoryProperties(VkContext.physicalDevice(), memProps);
        // One line per type so a tester's console.log shows whether a BAR window exists.
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            int heap = memProps.memoryTypes(i).heapIndex();
            System.out.println("[VK] memory type " + i + ": flags=0x"
                    + Integer.toHexString(memProps.memoryTypes(i).propertyFlags())
                    + " heap " + heap + " (" + (memProps.memoryHeaps(heap).size() >> 20) + "MB)");
        }
    }

    private static int findMemoryType(int typeBits, int required) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                    && (memProps.memoryTypes(i).propertyFlags() & required) == required) {
                return i;
            }
        }
        throw new IllegalStateException("No memory type for bits=" + typeBits + " props=" + required);
    }

    public static Buf createBuffer(long size, int usage, boolean hostVisible) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            VkContext.check(vkCreateBuffer(VkContext.device(), ci, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(VkContext.device(), buffer, reqs);
            int props = hostVisible
                    ? VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(reqs.size())
                    .memoryTypeIndex(findMemoryType(reqs.memoryTypeBits(), props));
            LongBuffer pMemory = stack.mallocLong(1);
            VkContext.check(vkAllocateMemory(VkContext.device(), alloc, null, pMemory), "vkAllocateMemory(buffer)");
            long memory = pMemory.get(0);
            liveAllocations++;
            totalAllocations++;
            VkContext.check(vkBindBufferMemory(VkContext.device(), buffer, memory, 0), "vkBindBufferMemory");

            long hostPtr = 0;
            if (hostVisible) {
                PointerBuffer pp = stack.mallocPointer(1);
                VkContext.check(vkMapMemory(VkContext.device(), memory, 0, VK_WHOLE_SIZE, 0, pp), "vkMapMemory");
                hostPtr = pp.get(0);
            }
            return new Buf(buffer, memory, size, hostPtr);
        }
    }

    public static Img createImage2D(int width, int height, int mipLevels, int format, int usage) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo ci = VkImageCreateInfo.calloc(stack)
                    .sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .mipLevels(mipLevels)
                    .arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            ci.extent().set(width, height, 1);
            LongBuffer pImage = stack.mallocLong(1);
            VkContext.check(vkCreateImage(VkContext.device(), ci, null, pImage), "vkCreateImage");
            long image = pImage.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(VkContext.device(), image, reqs);
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(reqs.size())
                    .memoryTypeIndex(findMemoryType(reqs.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pMemory = stack.mallocLong(1);
            VkContext.check(vkAllocateMemory(VkContext.device(), alloc, null, pMemory), "vkAllocateMemory(image)");
            long memory = pMemory.get(0);
            liveAllocations++;
            totalAllocations++;
            VkContext.check(vkBindImageMemory(VkContext.device(), image, memory, 0), "vkBindImageMemory");
            return new Img(image, memory);
        }
    }

    /**
     * Recycling pool for the chunk/sky vertex buffers. Chunk rebuilds churn hundreds of
     * buffers a second, so sizes are rounded to a class (quarter-steps between powers of
     * two — at most 25% slack) and buffers are reused. Released buffers only re-enter the
     * free list once the frame that referenced them has retired, which is what keeps the
     * GPU from reading a recycled chunk.
     *
     * New slices are bump-allocated out of 32MB slabs rather than given their own
     * vkAllocateMemory: on Windows each allocation is a kernel call costing up to
     * milliseconds, so walking into fresh terrain used to stutter until the pool warmed up,
     * and the live count crept toward the driver's 4096 cap.
     *
     * Slabs prefer DEVICE_LOCAL|HOST_VISIBLE memory. On Apple UMA that's every type (no
     * change); on a discrete GPU it's the BAR window (256MB without ReBAR), so the GPU
     * reads chunk geometry from VRAM instead of across PCIe every frame. BAR use is capped
     * at half the heap to leave the driver room; past that, slabs fall back to system RAM.
     */
    private static final Map<Long, ArrayDeque<Buf>> vertexPool = new HashMap<Long, ArrayDeque<Buf>>();
    private static final long MIN_VERTEX_CLASS = 4096;
    private static final long SLAB_SIZE = 32L * 1024 * 1024;
    private static final java.util.List<Buf> slabs = new java.util.ArrayList<Buf>();
    private static Buf slab;          // slab currently being carved
    private static long slabTop;      // bump pointer into it
    private static long barUsed;      // bytes of slabs placed in device-local host-visible memory
    private static boolean barExhausted;

    public static Buf acquireVertexBuffer(long size) {
        long cls = sizeClass(size);
        ArrayDeque<Buf> free = vertexPool.get(cls);
        if (free != null) {
            Buf reused = free.poll();
            if (reused != null) return reused;
        }
        if (cls > SLAB_SIZE) {
            return createBuffer(cls, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
        }
        if (slab == null || slabTop + cls > slab.size) {
            // The unused tail of the old slab is lost; with classes this coarse it's small.
            slab = createSlab();
            slabs.add(slab);
            slabTop = 0;
        }
        // Classes above MIN_VERTEX_CLASS are multiples of 1024, so slices stay well aligned.
        Buf slice = new Buf(slab.buffer, 0L, cls, slab.hostPtr + slabTop, slabTop);
        slabTop += cls;
        return slice;
    }

    private static Buf createSlab() {
        int deviceHost = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
        if (!barExhausted) {
            Buf b = tryCreateBuffer(SLAB_SIZE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, deviceHost, true);
            if (b != null) {
                barUsed += SLAB_SIZE;
                return b;
            }
            barExhausted = true;
            System.out.println("[VK] vertex slabs: device-local host-visible memory full after "
                    + (barUsed >> 20) + "MB, using system memory");
        }
        return createBuffer(SLAB_SIZE, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, true);
    }

    /**
     * createBuffer with explicit memory properties that returns null instead of throwing
     * when no such type exists, the heap's budget share is spent, or the driver is out
     * of memory.
     */
    private static Buf tryCreateBuffer(long size, int usage, int props, boolean map) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo ci = VkBufferCreateInfo.calloc(stack)
                    .sType$Default()
                    .size(size)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuffer = stack.mallocLong(1);
            VkContext.check(vkCreateBuffer(VkContext.device(), ci, null, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(0);

            VkMemoryRequirements reqs = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(VkContext.device(), buffer, reqs);
            int type = -1;
            for (int i = 0; i < memProps.memoryTypeCount(); i++) {
                if ((reqs.memoryTypeBits() & (1 << i)) != 0
                        && (memProps.memoryTypes(i).propertyFlags() & props) == props) {
                    type = i;
                    break;
                }
            }
            if (type < 0 || barUsed + reqs.size() > memProps.memoryHeaps(memProps.memoryTypes(type).heapIndex()).size() / 2) {
                vkDestroyBuffer(VkContext.device(), buffer, null);
                return null;
            }
            VkMemoryAllocateInfo alloc = VkMemoryAllocateInfo.calloc(stack)
                    .sType$Default()
                    .allocationSize(reqs.size())
                    .memoryTypeIndex(type);
            LongBuffer pMemory = stack.mallocLong(1);
            if (vkAllocateMemory(VkContext.device(), alloc, null, pMemory) != VK_SUCCESS) {
                vkDestroyBuffer(VkContext.device(), buffer, null);
                return null;
            }
            long memory = pMemory.get(0);
            liveAllocations++;
            totalAllocations++;
            VkContext.check(vkBindBufferMemory(VkContext.device(), buffer, memory, 0), "vkBindBufferMemory");

            long hostPtr = 0;
            if (map) {
                PointerBuffer pp = stack.mallocPointer(1);
                VkContext.check(vkMapMemory(VkContext.device(), memory, 0, VK_WHOLE_SIZE, 0, pp), "vkMapMemory");
                hostPtr = pp.get(0);
            }
            return new Buf(buffer, memory, size, hostPtr);
        }
    }

    public static void releaseVertexBuffer(final Buf buf) {
        if (buf == null) return;
        VkFrameRing.deferDestroy(new Runnable() {
            public void run() {
                ArrayDeque<Buf> free = vertexPool.get(buf.size);
                if (free == null) {
                    free = new ArrayDeque<Buf>();
                    vertexPool.put(buf.size, free);
                }
                free.add(buf);
            }
        });
    }

    private static long sizeClass(long size) {
        if (size <= MIN_VERTEX_CLASS) return MIN_VERTEX_CLASS;
        long step = Long.highestOneBit(size) >> 2;
        return (size + step - 1) / step * step;
    }

    public static void destroy(Buf buf) {
        if (buf == null) return;
        vkDestroyBuffer(VkContext.device(), buf.buffer, null);
        vkFreeMemory(VkContext.device(), buf.memory, null);
        liveAllocations--;
    }

    public static void destroy(Img img) {
        if (img == null) return;
        vkDestroyImage(VkContext.device(), img.image, null);
        vkFreeMemory(VkContext.device(), img.memory, null);
        liveAllocations--;
    }
}
