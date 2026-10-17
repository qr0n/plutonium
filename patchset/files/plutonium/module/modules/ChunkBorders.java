package plutonium.module.modules;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import org.lwjgl.opengl.GL11;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.ColorSetting;

public class ChunkBorders extends Module {

    public static ChunkBorders instance;

    private final ColorSetting lineColor = reg(new ColorSetting("Color", "Border line color", 0x99FFFF00));

    public ChunkBorders() {
        super("Chunk Borders", Category.VISUAL);
        instance = this;
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }

    public static void renderInWorld(float partialTicks) {
        if (instance == null || !instance.isEnabled()) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) return;

        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        double px = view.lastTickPosX + (view.posX - view.lastTickPosX) * partialTicks;
        double py = view.lastTickPosY + (view.posY - view.lastTickPosY) * partialTicks;
        double pz = view.lastTickPosZ + (view.posZ - view.lastTickPosZ) * partialTicks;

        int chunkX = ((int) px) >> 4;
        int chunkZ = ((int) pz) >> 4;

        double ox = chunkX * 16;
        double oz = chunkZ * 16;

        int c = instance.lineColor.getValue();
        float r = ((c >> 16) & 0xFF) / 255f;
        float g = ((c >> 8) & 0xFF) / 255f;
        float b = (c & 0xFF) / 255f;
        float a = ((c >> 24) & 0xFF) / 255f;

        double minY = 0;
        double maxY = 256;

        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        // lineWidth fixed at 1 under Vulkan/Metal

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();

        double rx = ox - px;
        double rz = oz - pz;

        double y1 = minY - py;
        double y2 = maxY - py;

        wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(rx, y1, rz).color(r, g, b, a).endVertex();
        wr.pos(rx, y2, rz).color(r, g, b, a).endVertex();
        wr.pos(rx + 16.0, y1, rz).color(r, g, b, a).endVertex();
        wr.pos(rx + 16.0, y2, rz).color(r, g, b, a).endVertex();
        wr.pos(rx, y1, rz + 16.0).color(r, g, b, a).endVertex();
        wr.pos(rx, y2, rz + 16.0).color(r, g, b, a).endVertex();
        wr.pos(rx + 16.0, y1, rz + 16.0).color(r, g, b, a).endVertex();
        wr.pos(rx + 16.0, y2, rz + 16.0).color(r, g, b, a).endVertex();
        tess.draw();

        wr.begin(GL11.GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR);
        wr.pos(rx, 0.0, rz).color(r, g, b, a).endVertex();
        wr.pos(rx + 16.0, 0.0, rz).color(r, g, b, a).endVertex();
        wr.pos(rx + 16.0, 0.0, rz + 16.0).color(r, g, b, a).endVertex();
        wr.pos(rx, 0.0, rz + 16.0).color(r, g, b, a).endVertex();
        tess.draw();

        GlStateManager.enableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
    }
}
