package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;

/**
 * Remaps the drop key (Q) inside container/inventory screens: when hovering a
 * non-empty slot, pressing Q quick-moves the stack (shift-click) instead of
 * throwing it on the ground. Hooked from GuiContainer.keyTyped.
 */
public class DropToShiftClick extends Module {

    public static DropToShiftClick instance;

    public DropToShiftClick() {
        super("Q Shift-Click", Category.PLAYER);
        instance = this;
    }

    /** True when the drop key should be treated as a shift-click in containers. */
    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }
}
