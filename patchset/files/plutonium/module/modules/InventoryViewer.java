package plutonium.module.modules;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.client.gui.clickgui.DrawUtils;
import plutonium.module.Category;
import plutonium.module.Module;

public class InventoryViewer extends Module {

    private static final int SLOT = 16;
    private static final int PAD = 2;
    private static final int CELL = SLOT + PAD;
    private static final int COLS = 9;
    private static final int ROWS = 4;
    private static final int MARGIN = 4;
    private static final int HEADER = 14;
    private static final int WIDTH = MARGIN * 2 + COLS * CELL - PAD;
    private static final int HEIGHT = MARGIN + HEADER + MARGIN + ROWS * CELL - PAD + MARGIN;

    public InventoryViewer() {
        super("Inventory Viewer", Category.VISUAL);
    }

    @Override
    public void onRenderHUD(int sw, int sh) {
        if (mc.thePlayer == null) return;

        int x = resolveHudX(sw, sh);
        int y = resolveHudY(sw, sh);

        DrawUtils.drawRoundedRect(x + 3, y + 4, WIDTH, HEIGHT, 0x55000000);
        DrawUtils.drawRoundedRect(x, y, WIDTH, HEIGHT, DrawUtils.T_WIN_BG);
        DrawUtils.drawRoundedRectBorder(x, y, WIDTH, HEIGHT, DrawUtils.T_BORDER);

        DrawUtils.drawGradientRect(x + 2, y + 1, WIDTH - 4, HEADER, DrawUtils.T_HEADER_TOP, DrawUtils.T_HEADER_BOT);
        DrawUtils.drawGradientRect(x + 2, y + 1, WIDTH - 4, HEADER / 2, 0x22FFFFFF, 0x00FFFFFF);

        String title = "Inventory";
        int titleW = mc.fontRendererObj.getStringWidth(title);
        mc.fontRendererObj.drawString(title, x + WIDTH / 2 - titleW / 2, y + 3, DrawUtils.T_TEXT_PRIMARY);

        Gui.drawRect(x + 2, y + HEADER, x + WIDTH - 2, y + HEADER + 1, 0x28FFFFFF);

        int gridX = x + MARGIN;
        int gridY = y + HEADER + MARGIN;

        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();

        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int slotX = gridX + col * CELL;
                int slotY = gridY + row * CELL;

                int invIndex;
                if (row < 3) {
                    invIndex = 9 + row * 9 + col;
                } else {
                    invIndex = col;
                }

                Gui.drawRect(slotX - 1, slotY - 1, slotX + SLOT + 1, slotY + SLOT + 1, 0x22FFFFFF);

                ItemStack stack = mc.thePlayer.inventory.mainInventory[invIndex];
                if (stack != null) {
                    mc.getRenderItem().renderItemAndEffectIntoGUI(stack, slotX, slotY);
                    mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, stack, slotX, slotY, null);
                }
            }
        }

        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
    }

    @Override public int getHudWidth()  { return WIDTH; }
    @Override public int getHudHeight() { return HEIGHT; }
    @Override protected int defaultHudX(int sw, int sh) { return sw / 2 - getHudWidthScaled() / 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh / 2 + 20; }
}
