package plutonium.module.modules;

import net.minecraft.client.gui.clickgui.DrawUtils;
import net.minecraft.potion.Potion;
import net.minecraft.potion.PotionEffect;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class PotionEffects extends Module {

    private static final int BOX_W = 100;
    private static final int BOX_H = 20;
    private static final int GAP = 2;

    private final BoolSetting showDuration = reg(new BoolSetting("Duration", "Show remaining time", true));

    private int lastCount = 2; // editor fallback height

    public PotionEffects() {
        super("Potion Effects", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    public void onRenderHUD(int sw, int sh) {
        if (mc.thePlayer == null) return;

        Collection<PotionEffect> effects = mc.thePlayer.getActivePotionEffects();
        if (effects.isEmpty()) return;

        List<PotionEffect> list = new ArrayList<PotionEffect>(effects);
        lastCount = list.size();

        int startX = resolveHudX(sw, sh);
        int y = resolveHudY(sw, sh);

        for (PotionEffect effect : list) {
            int potionId = effect.getPotionID();
            if (potionId < 0 || potionId >= Potion.potionTypes.length) continue;
            Potion potion = Potion.potionTypes[potionId];
            if (potion == null) continue;

            DrawUtils.drawChip(startX, y, BOX_W, BOX_H, 0xFF000000 | potion.getLiquidColor());

            String name = potion.getName();
            if (name.startsWith("potion.")) name = name.substring(7);
            name = capitalise(name);
            int amp = effect.getAmplifier();
            if (amp > 0) name += " " + toRoman(amp + 1);

            mc.fontRendererObj.drawStringWithShadow(name, startX + 7, y + 3, DrawUtils.CHIP_TEXT);

            if (showDuration.getValue()) {
                int ticks = effect.getDuration();
                String duration = ticks > 20 * 60 * 60 ? "**:**" : formatTicks(ticks);
                mc.fontRendererObj.drawStringWithShadow(duration, startX + 7, y + 12, DrawUtils.CHIP_DIM);
            }

            y += BOX_H + GAP;
        }
    }

    private static String formatTicks(int ticks) {
        int secs = ticks / 20;
        return String.format("%d:%02d", secs / 60, secs % 60);
    }

    private static String capitalise(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String toRoman(int n) {
        String[] r = {"", "I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};
        return n < r.length ? r[n] : String.valueOf(n);
    }

    @Override public int getHudWidth()  { return BOX_W; }
    @Override public int getHudHeight() { return Math.max(1, lastCount) * (BOX_H + GAP) - GAP; }
    @Override protected int defaultHudX(int sw, int sh) { return sw - getHudWidthScaled() - 2; }
    @Override protected int defaultHudY(int sw, int sh) { return 2; }
}
