package plutonium.module.modules;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.clickgui.DrawUtils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;

public class ArmorStatus extends Module {

    private static final int SLOT = 16;
    private static final int BAR_H = 2;
    private static final int CELL_H = SLOT + 1 + BAR_H + 3; // item + bar + spacing
    private static final int PAD = 4;

    private final BoolSetting showDurabilityText = reg(new BoolSetting("Durability Text", "Show durability percentage", false));

    public ArmorStatus() {
        super("Armor Status", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    public void onRenderHUD(int sw, int sh) {
        if (mc.thePlayer == null) return;

        int x = resolveHudX(sw, sh);
        int y = resolveHudY(sw, sh);

        DrawUtils.drawChip(x, y, getHudWidth(), getHudHeight());

        int itemX = x + PAD + 2;
        RenderHelper.enableGUIStandardItemLighting();
        GlStateManager.enableDepth();

        for (int i = 3; i >= 0; i--) {
            ItemStack stack = mc.thePlayer.inventory.armorInventory[i];
            int iy = y + PAD + (3 - i) * CELL_H;

            if (stack != null) {
                mc.getRenderItem().renderItemAndEffectIntoGUI(stack, itemX, iy);
                mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, stack, itemX, iy, null);

                if (stack.isItemStackDamageable()) {
                    float frac = 1.0f - (float) stack.getItemDamage() / stack.getMaxDamage();
                    int barW = (int) (frac * SLOT);
                    int color = getDurabilityColor(frac);
                    Gui.drawRect(itemX, iy + SLOT + 1, itemX + SLOT, iy + SLOT + 1 + BAR_H, 0xFF000000);
                    Gui.drawRect(itemX, iy + SLOT + 1, itemX + barW, iy + SLOT + 1 + BAR_H, color);

                    if (showDurabilityText.getValue()) {
                        int pct = (int)(frac * 100);
                        mc.fontRendererObj.drawStringWithShadow(pct + "%", itemX + SLOT + 4, iy + 4, color);
                    }
                }
            } else {
                Gui.drawRect(itemX, iy, itemX + SLOT, iy + SLOT, 0x22FFFFFF);
            }
        }

        GlStateManager.disableDepth();
        RenderHelper.disableStandardItemLighting();
    }

    private static int getDurabilityColor(float frac) {
        if (frac > 0.6f) return 0xFF55FF55;
        if (frac > 0.3f) return 0xFFFFFF55;
        return 0xFFFF5555;
    }

    @Override public int getHudWidth()  { return PAD + 2 + SLOT + (showDurabilityText.getValue() ? 30 : 0) + PAD; }
    @Override public int getHudHeight() { return PAD * 2 + CELL_H * 4 - 3; }
    @Override protected int defaultHudX(int sw, int sh) { return 2; }
    @Override protected int defaultHudY(int sw, int sh) { return sh - getHudHeightScaled() - 42; } // above Coordinates' default spot
}
