package plutonium.module.modules;

import net.minecraft.client.gui.clickgui.DrawUtils;
import net.minecraft.client.settings.GameSettings;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class Keystrokes extends Module {

    private final SliderSetting keySize = reg(new SliderSetting("Key Size", "Size of each key", 16f, 12f, 24f, 1f));

    public Keystrokes() {
        super("Keystrokes", Category.VISUAL);
    }

    @Override
    public void onRenderHUD(int sw, int sh) {
        GameSettings gs = mc.gameSettings;

        boolean w     = gs.keyBindForward.isKeyDown();
        boolean a     = gs.keyBindLeft.isKeyDown();
        boolean s     = gs.keyBindBack.isKeyDown();
        boolean d     = gs.keyBindRight.isKeyDown();
        boolean space = gs.keyBindJump.isKeyDown();
        boolean lmb   = gs.keyBindAttack.isKeyDown();
        boolean rmb   = gs.keyBindUseItem.isKeyDown();

        int sz  = (int) keySize.getValue().floatValue();
        int gap = 2;
        int totalW = sz * 3 + gap * 2;

        int baseX = resolveHudX(sw, sh);
        int baseY = resolveHudY(sw, sh);

        // LMB/RMB row
        int mouseY = baseY;
        int halfW = (totalW - gap) / 2;
        drawKey(baseX, mouseY, halfW, sz, "LMB", lmb);
        drawKey(baseX + halfW + gap, mouseY, totalW - halfW - gap, sz, "RMB", rmb);

        // Row 1: [W] centered
        int row1Y = mouseY + sz + gap;
        drawKey(baseX + sz + gap, row1Y, sz, sz, "W", w);

        // Row 2: [A] [S] [D]
        int row2Y = row1Y + sz + gap;
        drawKey(baseX, row2Y, sz, sz, "A", a);
        drawKey(baseX + sz + gap, row2Y, sz, sz, "S", s);
        drawKey(baseX + (sz + gap) * 2, row2Y, sz, sz, "D", d);

        // Row 3: [SPACE] full width
        int row3Y = row2Y + sz + gap;
        drawKey(baseX, row3Y, totalW, sz, "SPACE", space);
    }

    private void drawKey(int x, int y, int w, int h, String label, boolean pressed) {
        // Colours pulled live from the active theme so the HUD follows theme/profile switches.
        DrawUtils.drawRoundedRect(x, y, w, h, pressed ? DrawUtils.T_ACCENT : DrawUtils.T_WIN_BG);

        int textColor = pressed ? DrawUtils.T_TEXT_PRIMARY : DrawUtils.T_TEXT_SECONDARY;
        int textW = mc.fontRendererObj.getStringWidth(label);
        int textX = x + (w - textW) / 2;
        int textY = y + (h - 8) / 2;
        mc.fontRendererObj.drawString(label, textX, textY, textColor);
    }

    @Override public int getHudWidth()  { return (int)(keySize.getValue() * 3) + 4; }
    @Override public int getHudHeight() { return (int)(keySize.getValue() * 4) + 6; }
    @Override protected int defaultHudX(int sw, int sh) { return sw - getHudWidthScaled() - 4; }
    @Override protected int defaultHudY(int sw, int sh) { return sh - getHudHeightScaled() - 4; }
}
