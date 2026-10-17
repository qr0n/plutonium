package plutonium.pet;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.clickgui.DrawUtils;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import plutonium.module.modules.PocketPet;

import java.io.IOException;


/**
 * The Pocket Pet panel. Care actions, live stats, rename, and the
 * "catch the treats" minigame (5 catches in 8s).
 */
public class GuiPocketPet extends GuiScreen {

    private static final int PANEL_W = 320;
    private static final int PANEL_H = 222;

    // pet display area
    private static final int PET_X = 16, PET_Y = 30, PET_S = 96;
    // right column
    private static final int RX = 128, RW = 176;

    // treat game
    private static final int TREAT_PX = 6;              // 8x8 art -> 48x48
    private static final int CATCHES_TO_WIN = 5;
    private static final int MISSES_TO_LOSE = 3;
    private static final long GAME_MS = 8000;

    private final PocketPet module;

    private int px, py; // panel origin (scaled px)

    // treat game state
    private boolean gameActive;
    private long   gameEndsAt;
    private int    catches, misses;
    private int    treatX, treatY;
    private String resultMsg = null;
    private long   resultUntil;

    // rename state
    private boolean renameMode;
    private final StringBuilder renameBuf = new StringBuilder();

    public GuiPocketPet(PocketPet module) {
        this.module = module;
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    @Override
    public void initGui() {
        super.initGui();
        px = (this.width - PANEL_W) / 2;
        py = (this.height - PANEL_H) / 2;

        buttonList.clear();
        PetState pet = module.getPet();
        if (!pet.alive) {
            buttonList.add(new GuiButton(7, px + 16, py + 136, PANEL_W - 32, 20, "Hatch a new egg"));
            return;
        }
        if (pet.stage == PetState.STAGE_EGG) {
            buttonList.add(new GuiButton(8, px + 16, py + 136, PANEL_W - 32, 20,
                    pet.warming ? "Warming... hold on" : "Warm the Egg (RMB on the HUD works too)"));
            return;
        }
        int cy = py + 136;
        buttonList.add(new GuiButton(1, px + 16,  cy,      148, 18, "Feed"));
        buttonList.add(new GuiButton(2, px + 156, cy,      148, 18, "Play Treats"));
        buttonList.add(new GuiButton(3, px + 16,  cy + 22, 148, 18, pet.asleep ? "Wake Up" : "Nap"));
        buttonList.add(new GuiButton(4, px + 156, cy + 22, 148, 18, "Clean"));
        buttonList.add(new GuiButton(5, px + 16,  cy + 44, 148, 18, "Pat"));
        buttonList.add(new GuiButton(6, px + 156, cy + 44, 148, 18, "Rename"));
    }

    @Override
    protected void actionPerformed(GuiButton button) throws IOException {
        PetState pet = module.getPet();
        switch (button.id) {
            case 1:
                if (pet.feed()) { sfx("random.pop", 1.0f); }
                break;
            case 2:
                if (gameActive) break;
                if (!pet.offCooldown(3000) || pet.asleep) break;
                gameActive = true;
                catches = 0; misses = 0;
                gameEndsAt = System.currentTimeMillis() + GAME_MS;
                placeTreat();
                sfx("random.pop", 0.8f);
                break;
            case 3:
                pet.toggleSleep();
                sfx("random.fizz", pet.asleep ? 0.6f : 1.1f);
                module.savePetNow();
                initGui(); // refresh label
                break;
            case 4:
                if (pet.clean()) { sfx("random.fizz", 0.9f); }
                break;
            case 5:
                if (pet.pat()) { sfx("random.pop", 1.6f); }
                break;
            case 6:
                if (renameMode) {
                    commitRename();
                } else {
                    renameMode = true;
                    renameBuf.setLength(0);
                    renameBuf.append(pet.name);
                }
                break;
            case 7:
                pet.hatchNewEgg();
                sfx("random.levelup", 0.8f);
                module.chat("A new egg wobbles into view. It will be " + pet.name + "!");
                module.savePetNow();
                initGui();
                break;
            case 8:
                if (!pet.warming) {
                    pet.startWarming();
                    sfx("random.pop", 1.2f);
                    initGui();
                }
                break;
            default:
                break;
        }
        module.savePetNow();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        PetState pet = module.getPet();
        if (gameActive && inBox(mouseX, mouseY, px + PET_X, py + PET_Y, PET_S, PET_S)) {
            if (inBox(mouseX, mouseY, px + PET_X + treatX, py + PET_Y + treatY, TREAT_PX * 8, TREAT_PX * 8)) {
                catches++;
                sfx("random.pop", 1.1f + catches * 0.12f);
                if (catches >= CATCHES_TO_WIN) {
                    endGame(true);
                } else {
                    placeTreat();
                }
            } else {
                misses++;
                if (misses >= MISSES_TO_LOSE) endGame(false);
            }
            return;
        }
        // clicking the pet itself = a little pat
        if (!pet.alive || pet.asleep || gameActive || renameMode) return;
        if (inBox(mouseX, mouseY, px + PET_X, py + PET_Y, PET_S, PET_S) && pet.pat()) {
            sfx("random.pop", 1.6f);
            module.savePetNow();
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) throws IOException {
        if (renameMode) {
            if (keyCode == 1) {               // esc: cancel
                renameMode = false;
            } else if (keyCode == 28 || keyCode == 156) { // enter
                commitRename();
            } else if (keyCode == 14) {        // backspace
                if (renameBuf.length() > 0) renameBuf.setLength(renameBuf.length() - 1);
            } else if (typedChar >= 32 && typedChar < 127 && renameBuf.length() < 12) {
                renameBuf.append(typedChar);
            }
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void commitRename() {
        PetState pet = module.getPet();
        String n = renameBuf.toString().trim();
        if (!n.isEmpty()) {
            pet.name = n;
            module.chat(pet.name + " approves of the new name.");
        }
        renameMode = false;
        module.savePetNow();
    }

    private void placeTreat() {
        int max = PET_S - TREAT_PX * 8;
        treatX = 8 * ((int) (Math.random() * (max / 8 + 1)));
        treatY = 8 * ((int) (Math.random() * (max / 8 + 1)));
    }

    private void endGame(boolean won) {
        PetState pet = module.getPet();
        gameActive = false;
        if (won) {
            pet.winTreatGame();
            resultMsg = pet.name + " caught them all! +15 XP";
            sfx("random.levelup", 1.0f);
        } else {
            pet.loseTreatGame();
            resultMsg = catches > 0 ? "So close! " + catches + "/" + CATCHES_TO_WIN : "The treats got away...";
            sfx("mob.villager.no", 1.0f);
        }
        resultUntil = System.currentTimeMillis() + 2500;
        module.savePetNow();
    }

    @Override
    public void onGuiClosed() {
        module.savePetNow();
    }

    // ------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        FontRenderer fr = this.fontRendererObj;
        GlStateManager.color(1f, 1f, 1f, 1f);
        DrawUtils.drawRect(0, 0, this.width, this.height, 0x66000000);

        PetState pet = module.getPet();
        long now = System.currentTimeMillis();
        if (gameActive && now >= gameEndsAt) endGame(false);

        // panel
        DrawUtils.drawShadow(px, py, PANEL_W, PANEL_H, DrawUtils.CORNER_RADIUS);
        DrawUtils.drawRoundedRect(px, py, PANEL_W, PANEL_H, DrawUtils.T_WIN_BG);
        DrawUtils.drawRoundedRectBorder(px, py, PANEL_W, PANEL_H, DrawUtils.T_BORDER);

        // header
        fr.drawString("Pocket Pet", px + 10, py + 7, DrawUtils.T_TEXT_PRIMARY);
        String sub = pet.alive
                ? "Lv." + pet.level + "  " + PetState.STAGE_NAMES[pet.stage]
                : "passed away";
        fr.drawString(sub, px + PANEL_W - 10 - fr.getStringWidth(sub), py + 7, DrawUtils.T_TEXT_SECONDARY);
        DrawUtils.drawRect(px + 2, py + 20, PANEL_W - 4, 1, DrawUtils.T_SEPARATOR);

        // pet area
        DrawUtils.drawRect(px + PET_X, py + PET_Y, PET_S, PET_S, 0x5510141F);
        DrawUtils.drawRect(px + PET_X, py + PET_Y + PET_S - 8, PET_S, 8, 0x40302838);

        if (pet.stage == PetState.STAGE_EGG && pet.alive) {
            PetArt.drawPet(pet, px + PET_X + 18, py + PET_Y + 26, 5, now);
            if (pet.warming) {
                int w = 60 * (int) Math.min(100, pet.warmingTicks) / 120;
                DrawUtils.drawRect(px + PET_X + 18, py + PET_Y + 92, 60, 3, 0xFF1A1A2E);
                DrawUtils.drawRect(px + PET_X + 18, py + PET_Y + 92, w, 3, DrawUtils.T_ACCENT);
                fr.drawString("warming...", px + PET_X + 36 - 20, py + PET_Y + 84, DrawUtils.T_TEXT_SECONDARY);
            }
        } else if (pet.alive) {
            PetArt.drawPet(pet, px + PET_X + 18, py + PET_Y + 26, 5, now);
        } else {
            PetArt.drawPet(pet, px + PET_X + 18, py + PET_Y + 26, 5, now);
            fr.drawString("R.I.P.", px + PET_X + PET_S / 2 - 13, py + PET_Y + 12, 0xFF9A9AA8);
        }

        // treat game overlay
        if (gameActive) {
            int remaining = (int) Math.max(0, (gameEndsAt - now) * PET_S / GAME_MS);
            DrawUtils.drawRect(px + PET_X, py + PET_Y, remaining, 3, DrawUtils.T_ACCENT);
            PetArt.drawTreat(px + PET_X + treatX, py + PET_Y + treatY, TREAT_PX);
            fr.drawString(catches + "/" + CATCHES_TO_WIN, px + PET_X + 2, py + PET_Y + PET_S - 10, DrawUtils.T_TEXT_PRIMARY);
            String m = misses + "/" + MISSES_TO_LOSE;
            fr.drawString(m, px + PET_X + PET_S - 2 - fr.getStringWidth(m), py + PET_Y + PET_S - 10, 0xFFE8A855);
        }

        // right column
        if (pet.alive) {
            drawStatBlock(fr, pet, py);
        } else {
            fr.drawString(pet.name + " " + PetState.STAGE_NAMES[pet.stage]
                    + "  (" + ageOf(pet) + ")", RX + px, py + 34, 0xFF9A9AA8);
            fr.drawString("Take care of the next one a little better.",
                    RX + px, py + 46, DrawUtils.T_TEXT_SECONDARY);
        }

        for (GuiButton b : buttonList) {
            int bw = b.getButtonWidth();
            int bh = (b.id == 7 || b.id == 8) ? 20 : 18;
            boolean hover = b.mousePressed(mc, mouseX, mouseY);
            if (b.enabled) {
                DrawUtils.drawButton(fr, b.displayString, b.xPosition, b.yPosition, bw, bh, hover);
            } else {
                DrawUtils.drawRoundedRect(b.xPosition, b.yPosition, bw, bh, DrawUtils.T_BTN_DISABLED);
                fr.drawString(b.displayString,
                        b.xPosition + (bw - fr.getStringWidth(b.displayString)) / 2,
                        b.yPosition + (bh - 8) / 2, DrawUtils.T_TEXT_DISABLED);
            }
        }

        // disable care buttons while asleep / on cooldown (visual only)
        if (pet.alive) {
            applyCooldownStyles(pet);
        }

        // rename field
        if (renameMode) {
            int fx = px + RX, fy = py + 30;
            DrawUtils.drawRect(fx, fy, 120, 12, 0xFF101018);
            DrawUtils.drawRect(fx, fy, 120, 1, DrawUtils.T_BORDER);
            DrawUtils.drawRect(fx, fy + 11, 120, 1, DrawUtils.T_BORDER);
            String cur = renameBuf.toString();
            fr.drawString(cur, fx + 3, fy + 2, DrawUtils.T_TEXT_PRIMARY);
            if ((now / 400) % 2 == 0) {
                int cx = fx + 3 + fr.getStringWidth(cur);
                DrawUtils.drawRect(cx, fy + 2, 1, 8, DrawUtils.T_TEXT_PRIMARY);
            }
        }

        // footer
        String footer;
        if (now < resultUntil) footer = resultMsg;
        else if (gameActive) footer = "Catch the treats! 3 misses and you're done.";
        else if (renameMode) footer = "Enter: save name · Esc: cancel";
        else if (!pet.alive) footer = pet.name + " has left this world. Hatch a new pet to start over.";
        else if (pet.stage == PetState.STAGE_EGG) footer = "Hold the egg warm until it hatches...";
        else footer = module.openPanelKeyName() + ": open · RMB pet: pat · Esc: close";
        int fc = now < resultUntil ? DrawUtils.T_ACCENT : DrawUtils.T_TEXT_SECONDARY;
        fr.drawString(footer, px + 10, py + PANEL_H - 13, fc);
    }

    private void drawStatBlock(FontRenderer fr, PetState pet, int py) {
        int x = px + RX;
        // name + mood
        fr.drawString(pet.name, x, py + 34, DrawUtils.T_TEXT_PRIMARY);
        String mood = pet.moodLabel();
        int moodX = x + fr.getStringWidth(pet.name) + 4;
        fr.drawString("· " + mood, moodX, py + 34, pet.moodColor());
        int dotColor = PetArt.speciesColor(pet.species);
        DrawUtils.drawRect(moodX + fr.getStringWidth("· " + mood) + 4, py + 36, 4, 4, dotColor);
        String age = ageOf(pet);
        fr.drawString(age, x + RW - fr.getStringWidth(age), py + 34, DrawUtils.T_TEXT_SECONDARY);

        // stat rows
        String[] labels = {"Hunger", "Happy", "Energy", "Clean", "Health"};
        float[] vals = {pet.hunger, pet.happiness, pet.energy, pet.cleanliness, pet.health};
        int[] colors = {0xFFE8A855, 0xFFE87BA8, 0xFF6BB8FF, 0xFF7BE0A8, 0xFF7BE86B};
        for (int i = 0; i < 5; i++) {
            int y = py + 50 + i * 12;
            fr.drawString(labels[i], x, y + 1, DrawUtils.T_TEXT_SECONDARY);
            int bw = 100;
            DrawUtils.drawRect(x + 34, y, bw, 5, 0xFF1A1A2E);
            int fill = (int) (bw * vals[i] / 100f);
            int c = colors[i];
            if (i == 4 && pet.health < 30f) c = 0xFFE85555;
            if (fill > 0) DrawUtils.drawRect(x + 34, y, fill, 5, c);
            String v = String.valueOf((int) vals[i]);
            fr.drawString(v, x + 34 + bw + 4, y + 1, DrawUtils.T_TEXT_SECONDARY);
        }

        // xp row
        int y = py + 50 + 5 * 12;
        fr.drawString("XP", x, y + 1, DrawUtils.T_TEXT_SECONDARY);
        int bw = 100;
        DrawUtils.drawRect(x + 34, y, bw, 5, 0xFF1A1A2E);
        int fill = (int) (bw * pet.xp / (float) pet.xpToNext());
        if (fill > 0) DrawUtils.drawRect(x + 34, y, fill, 5, DrawUtils.T_ACCENT);
        String v = pet.xp + "/" + pet.xpToNext();
        fr.drawString(v, x + 34 + bw + 4, y + 1, DrawUtils.T_TEXT_SECONDARY);
    }

    /** Re-style care buttons that are currently unusable (asleep / cooldown). */
    private void applyCooldownStyles(PetState pet) {
        boolean asleep = pet.asleep;
        boolean cool3 = !pet.offCooldown(3000);
        boolean cool1 = !pet.offCooldown(1000);
        for (GuiButton b : buttonList) {
            if (b.id == 1 || b.id == 2 || b.id == 4) b.enabled = !asleep && !cool3;
            else if (b.id == 5) b.enabled = !asleep && !cool1;
            else if (b.id == 6) b.enabled = !gameActive;
            else if (b.id == 8) b.enabled = !pet.warming;
        }
    }

    private String ageOf(PetState pet) {
        try {
            return pet.ageString();
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean inBox(int mx, int my, int x, int y, int w, int h) {
        return DrawUtils.inBox(mx, my, x, y, w, h);
    }

    private void sfx(String name, float pitch) {
        if (!module.soundsEnabled()) return;
        try {
            this.mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation(name), pitch));
        } catch (Exception ignored) {
        }
    }
}
