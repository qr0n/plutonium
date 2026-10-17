package plutonium.gpu.vk;

/**
 * CPU shadow of the GL-style mutable render state. Written only by GlStateManager
 * (and OpenGlHelper.glBlendFunc); read by VkDrawDispatch, which turns it into a
 * pipeline-cache key (blend, color mask) plus dynamic state (depth, cull, viewport,
 * scissor, depth bias). All enums stay in GL form here; VkPipelines maps them.
 *
 * colorLogicOp has no Vulkan equivalent on Metal — when enabled, dispatch substitutes
 * the classic invert emulation blend (ONE_MINUS_DST_COLOR, ZERO), which is exactly
 * what MC's text-selection highlight needs.
 */
public final class VkRenderState {

    public static boolean blendEnabled = false;
    public static int blendSrcRGB = 1, blendDstRGB = 0;   // GL_ONE, GL_ZERO
    public static int blendSrcA = 1, blendDstA = 0;

    public static boolean depthTest = false;
    public static boolean depthWrite = true;
    public static int depthFunc = 513;                    // GL_LESS

    public static boolean cullEnabled = false;
    public static int cullMode = 1029;                    // GL_BACK

    public static boolean maskR = true, maskG = true, maskB = true, maskA = true;

    public static boolean scissorEnabled = false;
    public static int scissorX, scissorY, scissorW, scissorH;   // GL bottom-left origin

    public static int viewportX, viewportY, viewportW = 1, viewportH = 1;

    public static boolean polygonOffsetEnabled = false;
    public static float polygonOffsetFactor = 0, polygonOffsetUnits = 0;

    public static boolean logicOpEnabled = false;

    public static float clearR, clearG, clearB, clearA;
    public static double clearDepth = 1.0;

    private VkRenderState() {}
}
