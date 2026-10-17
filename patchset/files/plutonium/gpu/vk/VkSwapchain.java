package plutonium.gpu.vk;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VkExtent2D;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;
import org.lwjgl.vulkan.VkSwapchainCreateInfoKHR;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Swapchain + per-image views. B8G8R8A8_UNORM (matches the engine's BGRA texture
 * uploads), always FIFO — with vsync off, VkRenderTargets' present pacer plus FIFO
 * gives tear-free MAILBOX semantics (IMMEDIATE tore visibly on macOS).
 * Recreated on resize/out-of-date/vsync change via {@link #recreate}.
 */
public final class VkSwapchain {

    private static long swapchain = VK_NULL_HANDLE;
    private static int format;
    private static int colorSpace;
    private static int width, height;
    private static boolean vsync = true;
    private static long[] images = new long[0];
    private static long[] views = new long[0];

    private VkSwapchain() {}

    public static void create(int fbWidth, int fbHeight, boolean vsyncEnabled) {
        vsync = vsyncEnabled;
        build(fbWidth, fbHeight, VK_NULL_HANDLE);
    }

    public static void recreate(int fbWidth, int fbHeight, boolean vsyncEnabled) {
        vkDeviceWaitIdle(VkContext.device());
        vsync = vsyncEnabled;
        long old = swapchain;
        destroyViews();
        build(fbWidth, fbHeight, old);
        if (old != VK_NULL_HANDLE) {
            KHRSwapchain.vkDestroySwapchainKHR(VkContext.device(), old, null);
        }
    }

    private static void build(int fbWidth, int fbHeight, long oldSwapchain) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.malloc(stack);
            VkContext.check(KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                    VkContext.physicalDevice(), VkContext.surface(), caps), "surface capabilities");

            VkExtent2D extent = caps.currentExtent();
            if (extent.width() != 0xFFFFFFFF) {
                width = extent.width();
                height = extent.height();
            } else {
                width = Math.max(caps.minImageExtent().width(), Math.min(caps.maxImageExtent().width(), fbWidth));
                height = Math.max(caps.minImageExtent().height(), Math.min(caps.maxImageExtent().height(), fbHeight));
            }

            int imageCount = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && imageCount > caps.maxImageCount()) {
                imageCount = caps.maxImageCount();
            }

            pickFormat(stack);
            int presentMode = pickPresentMode(stack);

            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(VkContext.surface())
                    .minImageCount(imageCount)
                    .imageFormat(format)
                    .imageColorSpace(colorSpace)
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .preTransform(caps.currentTransform())
                    .compositeAlpha(KHRSurface.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(presentMode)
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);
            ci.imageExtent().set(width, height);

            LongBuffer pSwapchain = stack.mallocLong(1);
            VkContext.check(KHRSwapchain.vkCreateSwapchainKHR(VkContext.device(), ci, null, pSwapchain),
                    "vkCreateSwapchainKHR");
            swapchain = pSwapchain.get(0);

            IntBuffer count = stack.mallocInt(1);
            VkContext.check(KHRSwapchain.vkGetSwapchainImagesKHR(VkContext.device(), swapchain, count, null),
                    "vkGetSwapchainImagesKHR");
            LongBuffer pImages = stack.mallocLong(count.get(0));
            VkContext.check(KHRSwapchain.vkGetSwapchainImagesKHR(VkContext.device(), swapchain, count, pImages),
                    "vkGetSwapchainImagesKHR");

            images = new long[count.get(0)];
            views = new long[count.get(0)];
            for (int i = 0; i < images.length; i++) {
                images[i] = pImages.get(i);
                views[i] = createView(images[i], stack);
            }
        }
        System.out.println("[VK] swapchain " + width + "x" + height + " images=" + images.length
                + " format=" + format + " vsync=" + vsync);
    }

    private static void pickFormat(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        VkContext.check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                VkContext.physicalDevice(), VkContext.surface(), count, null), "surface formats");
        VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.malloc(count.get(0), stack);
        VkContext.check(KHRSurface.vkGetPhysicalDeviceSurfaceFormatsKHR(
                VkContext.physicalDevice(), VkContext.surface(), count, formats), "surface formats");

        format = formats.get(0).format();
        colorSpace = formats.get(0).colorSpace();
        for (int i = 0; i < formats.capacity(); i++) {
            if (formats.get(i).format() == VK_FORMAT_B8G8R8A8_UNORM
                    && formats.get(i).colorSpace() == KHRSurface.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = VK_FORMAT_B8G8R8A8_UNORM;
                colorSpace = formats.get(i).colorSpace();
                break;
            }
        }
    }

    private static int pickPresentMode(MemoryStack stack) {
        // FIFO even with vsync off. IMMEDIATE (displaySyncEnabled=false) scans out
        // mid-refresh and visibly tears; since VkRenderTargets paces presents to one
        // per refresh interval when vsync is off, FIFO costs only vblank alignment on
        // the presenting frame while the render loop stays uncapped — i.e. MAILBOX
        // semantics, which MoltenVK doesn't offer natively.
        // A/B'd 2026-07-17: IMMEDIATE also *feels* worse (sluggish/juddery) than
        // FIFO + pacer, on top of the tearing — this choice is confirmed, not a guess.
        return KHRSurface.VK_PRESENT_MODE_FIFO_KHR;
    }

    private static long createView(long image, MemoryStack stack) {
        VkImageViewCreateInfo ci = VkImageViewCreateInfo.calloc(stack)
                .sType$Default()
                .image(image)
                .viewType(VK_IMAGE_VIEW_TYPE_2D)
                .format(format);
        ci.subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0).levelCount(1)
                .baseArrayLayer(0).layerCount(1);
        LongBuffer pView = stack.mallocLong(1);
        VkContext.check(vkCreateImageView(VkContext.device(), ci, null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    private static void destroyViews() {
        for (long view : views) {
            vkDestroyImageView(VkContext.device(), view, null);
        }
        views = new long[0];
        images = new long[0];
    }

    public static void destroy() {
        destroyViews();
        if (swapchain != VK_NULL_HANDLE) {
            KHRSwapchain.vkDestroySwapchainKHR(VkContext.device(), swapchain, null);
            swapchain = VK_NULL_HANDLE;
        }
    }

    public static long handle() { return swapchain; }
    public static int format() { return format; }
    public static int width() { return width; }
    public static int height() { return height; }
    public static boolean isVsync() { return vsync; }
    public static int imageCount() { return images.length; }
    public static long image(int index) { return images[index]; }
    public static long view(int index) { return views[index]; }
}
