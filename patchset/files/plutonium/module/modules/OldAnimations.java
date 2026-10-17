package plutonium.module.modules;

import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.MathHelper;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;

public class OldAnimations extends Module {

    public static OldAnimations instance;

    private final BoolSetting swingAnim = reg(new BoolSetting("Swing", "1.7 swing animation", true));
    private final BoolSetting blockHitAnim = reg(new BoolSetting("Block Hit", "Show swing while blocking", true));

    public OldAnimations() {
        super("1.7 Animations", Category.VISUAL);
        instance = this;
        initEnabled(true);
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }

    public static boolean shouldSwing() {
        return instance != null && instance.isEnabled() && instance.swingAnim.getValue();
    }

    public static boolean shouldBlockHit() {
        return instance != null && instance.isEnabled() && instance.blockHitAnim.getValue();
    }

    public static void applyTransform(float equipProgress, float swingProgress) {
        GlStateManager.translate(0.56F, -0.52F, -0.71999997F);
        GlStateManager.rotate(45.0F, 0.0F, 1.0F, 0.0F);
        float swingSqrt = MathHelper.sin(MathHelper.sqrt_float(swingProgress) * (float) Math.PI);
        float swingSq = MathHelper.sin(swingProgress * swingProgress * (float) Math.PI);
        GlStateManager.rotate(-20.0F * swingSqrt, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(-20.0F * swingSq, 0.0F, 0.0F, 1.0F);
        GlStateManager.rotate(-80.0F * swingSqrt, 1.0F, 0.0F, 0.0F);
        GlStateManager.scale(0.4F, 0.4F, 0.4F);
    }
}
