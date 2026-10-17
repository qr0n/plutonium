package plutonium.module.modules;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;

/**
 * Refuses to throw anything but an empty bowl on the ground. Hooked from every
 * client-side drop path: the drop key in-world (Minecraft.runTick), container
 * drops (GuiContainer.handleMouseClick) and creative drops
 * (GuiContainerCreative.handleMouseClick).
 */
public class DropFilter extends Module {

    public static DropFilter instance;

    private final BoolSetting notify = reg(new BoolSetting("Notify",
        "Say so in chat when a drop is blocked.", true));

    /** Throttles the chat line — a held drop key fires several times a second. */
    private long lastNotify = 0L;

    public DropFilter() {
        super("Drop Filter", Category.PLAYER);
        instance = this;
        initEnabled(true);
    }

    /**
     * True when {@code stack} is allowed to leave the inventory. A null stack means the
     * click throws nothing, so it is always allowed through to vanilla.
     */
    public static boolean allowDrop(ItemStack stack) {
        if (stack == null || instance == null || !instance.isEnabled()) return true;
        if (stack.getItem() == Items.bowl) return true;

        instance.notifyBlocked();
        return false;
    }

    private void notifyBlocked() {
        if (!notify.getValue() || mc.thePlayer == null) return;

        long now = System.currentTimeMillis();
        if (now - lastNotify < 1500L) return;
        lastNotify = now;

        mc.ingameGUI.getChatGUI().printChatMessage(
            new ChatComponentText("§cDrop Filter§7: only empty bowls can be dropped."));
    }
}
