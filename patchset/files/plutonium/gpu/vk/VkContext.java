package plutonium.gpu.vk;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.EXTExtendedDynamicState;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.KHRPortabilitySubset;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSurface;
import org.lwjgl.vulkan.KHRSwapchain;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDynamicRenderingFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceExtendedDynamicStateFeaturesEXT;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns the Vulkan instance, window surface, physical + logical device and the single
 * graphics/present queue (MoltenVK exposes exactly one queue family).
 *
 * MoltenVK is loaded directly by LWJGL (no Khronos loader on macOS), so portability
 * extensions are enabled conditionally: VK_KHR_portability_enumeration at instance
 * level when advertised, VK_KHR_portability_subset at device level whenever the
 * device enumerates it (mandatory per spec in that case).
 *
 * The libMoltenVK.dylib in the natives dir is standalone MoltenVK 1.4.1, upgraded from
 * the 1.2.5 that LWJGL 3.3.3 bundles (1.2.5 predates VK_EXT_extended_dynamic_state,
 * which the pipeline design requires). LWJGL prints a benign "Incompatible Java and
 * native library versions" warning at startup because the dylib's version stamp differs
 * from the one it shipped — expected, ignore it.
 */
public final class VkContext {

    /** The shared push-constant block (VkUniformBlock / pc_block.glsl). NVIDIA reports 256. */
    public static final int REQUIRED_PUSH_CONSTANT_BYTES = VkUniformBlock.SIZE;

    private static VkInstance instance;
    private static long surface = VK_NULL_HANDLE;
    private static VkPhysicalDevice physicalDevice;
    private static VkDevice device;
    private static VkQueue queue;
    private static int queueFamily = -1;

    private static String deviceName = "unknown";
    private static String apiVersionString = "unknown";
    private static int maxPushConstantsSize;
    private static boolean portabilitySubset;
    /** False on drivers without VK_KHR_dynamic_rendering (Kepler tops out at Vulkan 1.2.175):
     *  VkRenderPasses then supplies classic render passes. -Dplutonium.vk.noDynamicRendering
     *  forces that path for testing on hardware that has the extension. */
    private static boolean dynamicRendering;

    private VkContext() {}

    public static void init(long window) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            createInstance(stack);
            createSurface(window, stack);
            pickPhysicalDevice(stack);
            createDevice(stack);
        }
        System.out.println("[VK] device=" + deviceName + " api=" + apiVersionString
                + " queueFamily=" + queueFamily
                + " portabilitySubset=" + portabilitySubset
                + " dynamicRendering=" + dynamicRendering
                + " maxPushConstants=" + maxPushConstantsSize);
    }

    private static void createInstance(MemoryStack stack) {
        Set<String> available = enumerateInstanceExtensions(stack);

        PointerBuffer glfwExt = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (glfwExt == null) {
            throw new IllegalStateException("GLFW found no Vulkan surface extensions (is MoltenVK loadable?)");
        }

        List<String> extras = new ArrayList<String>();
        int flags = 0;
        if (available.contains(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME)) {
            extras.add(KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME);
            flags |= KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
        }

        PointerBuffer exts = stack.mallocPointer(glfwExt.remaining() + extras.size());
        exts.put(glfwExt);
        for (String e : extras) exts.put(stack.UTF8(e));
        exts.flip();

        VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                .sType$Default()
                .pApplicationName(stack.UTF8("Plutonium"))
                .pEngineName(stack.UTF8("plutonium-gpu"))
                .apiVersion(VK_MAKE_VERSION(1, 1, 0));

        VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType$Default()
                .flags(flags)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(exts);

        PointerBuffer pInstance = stack.mallocPointer(1);
        check(vkCreateInstance(ci, null, pInstance), "vkCreateInstance");
        instance = new VkInstance(pInstance.get(0), ci);
    }

    private static Set<String> enumerateInstanceExtensions(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateInstanceExtensionProperties((String) null, count, null), "enumerate instance extensions");
        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateInstanceExtensionProperties((String) null, count, props), "enumerate instance extensions");
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < props.capacity(); i++) names.add(props.get(i).extensionNameString());
        return names;
    }

    private static void createSurface(long window, MemoryStack stack) {
        LongBuffer pSurface = stack.mallocLong(1);
        check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, pSurface), "glfwCreateWindowSurface");
        surface = pSurface.get(0);
    }

    private static void pickPhysicalDevice(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices");
        if (count.get(0) == 0) throw new IllegalStateException("No Vulkan physical devices");
        PointerBuffer devices = stack.mallocPointer(count.get(0));
        check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");

        // Single-GPU machines (every Apple Silicon Mac): take the first device that
        // has a graphics queue family which can also present to our surface.
        for (int d = 0; d < devices.capacity(); d++) {
            VkPhysicalDevice cand = new VkPhysicalDevice(devices.get(d), instance);
            int family = findGraphicsPresentFamily(cand, stack);
            if (family >= 0) {
                physicalDevice = cand;
                queueFamily = family;
                break;
            }
        }
        if (physicalDevice == null) {
            throw new IllegalStateException("No device with a graphics+present queue family");
        }

        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
        vkGetPhysicalDeviceProperties(physicalDevice, props);
        deviceName = props.deviceNameString();
        int v = props.apiVersion();
        apiVersionString = VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
        maxPushConstantsSize = props.limits().maxPushConstantsSize();
        if (maxPushConstantsSize < REQUIRED_PUSH_CONSTANT_BYTES) {
            throw new IllegalStateException("maxPushConstantsSize=" + maxPushConstantsSize
                    + " < required " + REQUIRED_PUSH_CONSTANT_BYTES
                    + " — the push-constant uniform design needs a UBO-ring fallback on this device");
        }
    }

    private static int findGraphicsPresentFamily(VkPhysicalDevice dev, MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, count, null);
        VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
        vkGetPhysicalDeviceQueueFamilyProperties(dev, count, families);
        IntBuffer supported = stack.mallocInt(1);
        for (int i = 0; i < families.capacity(); i++) {
            if ((families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) continue;
            check(KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR(dev, i, surface, supported),
                    "vkGetPhysicalDeviceSurfaceSupportKHR");
            if (supported.get(0) == VK_TRUE) return i;
        }
        return -1;
    }

    private static void createDevice(MemoryStack stack) {
        Set<String> available = enumerateDeviceExtensions(stack);

        List<String> required = new ArrayList<String>();
        required.add(KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME);
        required.add(EXTExtendedDynamicState.VK_EXT_EXTENDED_DYNAMIC_STATE_EXTENSION_NAME);
        required.add(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME);
        for (String ext : required) {
            if (!available.contains(ext)) {
                throw new IllegalStateException("Required device extension missing: " + ext
                        + " (device=" + deviceName + ") — the renderer depends on it."
                        + " Available: " + new java.util.TreeSet<String>(available));
            }
        }
        // Confirm the features are actually supported, not just the extensions listed. The
        // dynamic-rendering struct is only chained when advertised (Kepler's 1.2 driver
        // predates its sType).
        dynamicRendering = available.contains(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)
                && !Boolean.getBoolean("plutonium.vk.noDynamicRendering");
        VkPhysicalDeviceDynamicRenderingFeaturesKHR qDyn =
                VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default();
        VkPhysicalDeviceExtendedDynamicStateFeaturesEXT qEds =
                VkPhysicalDeviceExtendedDynamicStateFeaturesEXT.calloc(stack).sType$Default()
                        .pNext(dynamicRendering ? qDyn.address() : 0L);
        VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default().pNext(qEds.address());
        VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, query);
        if (!qEds.extendedDynamicState()) throw new IllegalStateException("extendedDynamicState feature unsupported");
        if (dynamicRendering && !qDyn.dynamicRendering()) dynamicRendering = false;
        if (dynamicRendering) {
            required.add(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME);
        }

        // Spec: must be enabled whenever the device advertises it.
        portabilitySubset = available.contains(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
        if (portabilitySubset) {
            required.add(KHRPortabilitySubset.VK_KHR_PORTABILITY_SUBSET_EXTENSION_NAME);
        }

        PointerBuffer exts = stack.mallocPointer(required.size());
        for (String e : required) exts.put(stack.UTF8(e));
        exts.flip();

        VkPhysicalDeviceDynamicRenderingFeaturesKHR dynRendering =
                VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack)
                        .sType$Default()
                        .dynamicRendering(true);
        VkPhysicalDeviceExtendedDynamicStateFeaturesEXT eds =
                VkPhysicalDeviceExtendedDynamicStateFeaturesEXT.calloc(stack)
                        .sType$Default()
                        .pNext(dynamicRendering ? dynRendering.address() : 0L)
                        .extendedDynamicState(true);

        VkDeviceQueueCreateInfo.Buffer queueCi = VkDeviceQueueCreateInfo.calloc(1, stack);
        queueCi.get(0)
                .sType$Default()
                .queueFamilyIndex(queueFamily)
                .pQueuePriorities(stack.floats(1.0f));

        VkDeviceCreateInfo ci = VkDeviceCreateInfo.calloc(stack)
                .sType$Default()
                .pNext(eds.address())
                .pQueueCreateInfos(queueCi)
                .ppEnabledExtensionNames(exts);

        PointerBuffer pDevice = stack.mallocPointer(1);
        check(vkCreateDevice(physicalDevice, ci, null, pDevice), "vkCreateDevice");
        device = new VkDevice(pDevice.get(0), physicalDevice, ci);

        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(device, queueFamily, 0, pQueue);
        queue = new VkQueue(pQueue.get(0), device);
    }

    private static Set<String> enumerateDeviceExtensions(MemoryStack stack) {
        IntBuffer count = stack.mallocInt(1);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, null),
                "enumerate device extensions");
        VkExtensionProperties.Buffer props = VkExtensionProperties.malloc(count.get(0), stack);
        check(vkEnumerateDeviceExtensionProperties(physicalDevice, (String) null, count, props),
                "enumerate device extensions");
        Set<String> names = new HashSet<String>();
        for (int i = 0; i < props.capacity(); i++) names.add(props.get(i).extensionNameString());
        return names;
    }

    public static void shutdown() {
        if (device != null) {
            vkDeviceWaitIdle(device);
            vkDestroyDevice(device, null);
            device = null;
        }
        if (surface != VK_NULL_HANDLE) {
            KHRSurface.vkDestroySurfaceKHR(instance, surface, null);
            surface = VK_NULL_HANDLE;
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
            instance = null;
        }
    }

    public static void check(int err, String what) {
        if (err != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult=" + err);
        }
    }

    public static VkInstance instance() { return instance; }
    public static VkPhysicalDevice physicalDevice() { return physicalDevice; }
    public static VkDevice device() { return device; }
    public static VkQueue queue() { return queue; }
    public static int queueFamily() { return queueFamily; }
    public static long surface() { return surface; }
    public static String deviceName() { return deviceName; }
    public static String apiVersion() { return apiVersionString; }
    public static boolean dynamicRendering() { return dynamicRendering; }
}
