package plutonium.gpu;

import plutonium.gpu.vk.VkPipelines;

/**
 * Program registry. Under Vulkan the SPIR-V modules and pipeline layout live in
 * VkPipelines; the ShaderProgram objects here are façades selecting a pipeline
 * family. init() is called by WindowManager.create() once the device is up.
 */
public class CoreShaders {

    private static ShaderProgram positionColor;
    private static ShaderProgram positionTex;
    private static ShaderProgram positionTexColor;
    private static ShaderProgram block;
    private static ShaderProgram entity;
    private static ShaderProgram roundRect;
    private static ShaderProgram blur;
    private static boolean initialized = false;

    public static void init() {
        if (initialized) return;
        initialized = true;

        positionColor = new ShaderProgram(VkPipelines.PROG_POSITION_COLOR);
        positionTex = new ShaderProgram(VkPipelines.PROG_POSITION_TEX);
        positionTexColor = new ShaderProgram(VkPipelines.PROG_POSITION_TEX_COLOR);
        block = new ShaderProgram(VkPipelines.PROG_BLOCK);
        entity = new ShaderProgram(VkPipelines.PROG_ENTITY);
        roundRect = new ShaderProgram(VkPipelines.PROG_ROUNDRECT);
        blur = new ShaderProgram(VkPipelines.PROG_BLUR);
    }

    public static ShaderProgram getPositionColor() { return positionColor; }
    public static ShaderProgram getPositionTex() { return positionTex; }
    public static ShaderProgram getPositionTexColor() { return positionTexColor; }
    public static ShaderProgram getBlock() { return block; }
    public static ShaderProgram getEntity() { return entity; }
    public static ShaderProgram getRoundRect() { return roundRect; }
    public static ShaderProgram getBlur() { return blur; }

    public static boolean isInitialized() { return initialized; }
}
