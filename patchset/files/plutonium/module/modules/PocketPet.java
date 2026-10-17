package plutonium.module.modules;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.util.ChatComponentText;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.util.ResourceLocation;
import net.minecraft.client.gui.clickgui.DrawUtils;
import net.minecraft.client.gui.FontRenderer;
import plutonium.gpu.KeyboardShim;
import plutonium.gpu.MouseShim;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.pet.GuiPocketPet;
import plutonium.pet.PetArt;
import plutonium.pet.PetState;
import plutonium.setting.BoolSetting;
import plutonium.setting.KeySetting;
import plutonium.setting.SliderSetting;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Pocket Pet — a tiny Tamagotchi that lives on your HUD.
 *
 * The pet hatches from an egg, grows through five stages, and its needs
 * (hunger, happiness, energy, cleanliness) decay over time. Neglect drains
 * health; at zero the pet dies and you can hatch a new one. Everything is
 * persisted to plutonium_pet.json, and time keeps passing while the game is
 * closed (capped, and the pet can't die offline — it just comes back grumpy).
 *
 * LMB on the HUD widget opens the pet panel; RMB gives a little pat.
 */
public class PocketPet extends Module {

    private static final String SAVE_FILE = "plutonium_pet.json";

    // HUD chip layout
    private static final int ART_PX = 5;                 // 12x12 art @ 5px = 60
    private static final int CHIP_W = 176;
    private static final int CHIP_H = 78;
    private static final int ART_X = 8, ART_Y = 9;
    private static final int COL_X = ART_X + ART_PX * 12 + 8;   // right text column
    private static final int COL_W = 100;

    private final PetState pet = new PetState();

    private int  prevLevel;
    private int  prevStage;
    private boolean prevAlive = true;

    private long lastSave = 0;

    private final SliderSetting decayRate = reg(new SliderSetting("Decay Rate",
            "How fast the pet's needs drain (1 = normal)", 1f, 0.25f, 3f, 0.25f));
    private final BoolSetting showStats = reg(new BoolSetting("Show Stats",
            "Draw the stat bars next to the pet on the HUD", true));
    private final BoolSetting sfx = reg(new BoolSetting("Sounds",
            "Play sound effects for pet actions", true));
    private final KeySetting openPanelKey = reg(new KeySetting("Open Panel Key",
            "Open the pet panel (mouse is grabbed in first-person, so use this)", KeyboardShim.KEY_P));

    private boolean panelKeyHeld = false;

    public PocketPet() {
        super("Pocket Pet", Category.MISC);
        initEnabled(true);
    }

    public PetState getPet() { return pet; }
    public boolean soundsEnabled() { return sfx.getValue(); }
    public String openPanelKeyName() { return openPanelKey.getKeyName(); }

    public void openPanel() {
        if (mc.currentScreen == null) mc.displayGuiScreen(new GuiPocketPet(this));
    }

    // ------------------------------------------------------------------
    // lifecycle / persistence
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        loadPet();
        prevLevel = pet.level;
        prevStage = pet.stage;
        prevAlive = pet.alive;
        String away = pet.applyOfflineAgeing();
        if (away != null) chat(away);
        lastSave = System.currentTimeMillis();
    }

    @Override
    public void onDisable() {
        savePetNow();
    }

    @Override
    public void onTick() {
        // panel keybind (edge-triggered, like Stopwatch)
        int k = openPanelKey.getValue();
        if (k != 0 && mc.currentScreen == null && KeyboardShim.isKeyDown(k)) {
            if (!panelKeyHeld) {
                panelKeyHeld = true;
                openPanel();
            }
        } else {
            panelKeyHeld = false;
        }

        pet.tick(decayRate.getValue());
        if (pet.flashTicks > 0) pet.flashTicks--;

        // announce transitions (level-ups, evolutions, death)
        if (pet.level > prevLevel) {
            sfx("random.levelup", 1.0f);
            chat(pet.name + " reached level " + pet.level + "!");
        }
        prevLevel = pet.level;
        if (pet.stage > prevStage) {
            sfx("random.levelup", 0.7f);
            chat(pet.name + " evolved into a " + PetState.STAGE_NAMES[pet.stage] + "!");
        }
        prevStage = pet.stage;
        if (prevAlive && !pet.alive) {
            prevAlive = false;
            chat(pet.name + " has passed away... Open the pet panel to hatch a new one.");
        }
        prevAlive = pet.alive;

        if (System.currentTimeMillis() - lastSave > 5000) {
            savePetNow();
        }
    }

    public void savePetNow() {
        try {
            File f = new File(mc.mcDataDir, SAVE_FILE);
            JsonObject o = new JsonObject();
            pet.toJson(o);
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(o, w);
            }
            lastSave = System.currentTimeMillis();
        } catch (IOException e) {
            System.err.println("[Plutonium] Failed to save pocket pet: " + e.getMessage());
        }
    }

    private void loadPet() {
        try {
            File f = new File(mc.mcDataDir, SAVE_FILE);
            if (!f.exists()) return;
            try (Reader r = new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8)) {
                JsonObject o = new JsonParser().parse(r).getAsJsonObject();
                PetState loaded = PetState.fromJson(o);
                pet.name = loaded.name;
                pet.species = loaded.species;
                pet.stage = loaded.stage;
                pet.alive = loaded.alive;
                pet.asleep = loaded.asleep;
                pet.warming = loaded.warming;
                pet.warmingTicks = loaded.warmingTicks;
                pet.hunger = loaded.hunger;
                pet.happiness = loaded.happiness;
                pet.energy = loaded.energy;
                pet.cleanliness = loaded.cleanliness;
                pet.health = loaded.health;
                pet.xp = loaded.xp;
                pet.level = loaded.level;
                pet.bornAt = loaded.bornAt;
                pet.lastSeen = loaded.lastSeen;
                pet.deadAt = loaded.deadAt;
            }
        } catch (Exception e) {
            // corrupted save -> fresh egg
        }
    }

    // ------------------------------------------------------------------
    // HUD widget
    // ------------------------------------------------------------------

    @Override
    public int getHudWidth()  { return CHIP_W; }
    @Override
    public int getHudHeight() { return CHIP_H; }

    @Override
    protected int defaultHudX(int sw, int sh) { return 2; }
    @Override
    protected int defaultHudY(int sw, int sh) { return sh - CHIP_H - 2; }

    private boolean isHovered(int sw, int sh) {
        int x = resolveHudX(sw, sh);
        int y = resolveHudY(sw, sh);
        int w = getHudWidthScaled();
        int h = getHudHeightScaled();
        int mx = MouseShim.getX() * sw / mc.displayWidth;
        int my = sh - MouseShim.getY() * sh / mc.displayHeight - 1;
        return DrawUtils.inBox(mx, my, x, y, w, h);
    }

    @Override
    public void onRenderHUD(int sw, int sh) {
        int x = resolveHudX(sw, sh);
        int y = resolveHudY(sw, sh);
        long now = System.currentTimeMillis();
        FontRenderer fr = mc.fontRendererObj;

        boolean hovered = isHovered(sw, sh);
        DrawUtils.drawChip(x, y, CHIP_W, CHIP_H);
        if (hovered) DrawUtils.drawRoundedRectBorder(x, y, CHIP_W, CHIP_H, DrawUtils.T_ACCENT);

        PetArt.drawPet(pet, x + ART_X, y + ART_Y, ART_PX, now);

        int tx = x + COL_X;
        // name + level
        fr.drawStringWithShadow(pet.name + "  Lv." + pet.level, tx, y + 6, DrawUtils.CHIP_TEXT);
        // stage + mood
        String stageLine = pet.alive
                ? PetState.STAGE_NAMES[pet.stage] + "  ·  " + pet.moodLabel()
                : "passed away  ·  click to hatch";
        int stageColor = pet.alive ? pet.moodColor() : 0xFF9A9AA8;
        fr.drawStringWithShadow(stageLine, tx, y + 16, stageColor);

        if (pet.stage == PetState.STAGE_EGG && pet.alive) {
            String hint = pet.warming ? "warming..." : "RMB to warm";
            int w = fr.getStringWidth(hint);
            fr.drawStringWithShadow(hint, tx + (COL_W - w) / 2, y + 30, DrawUtils.CHIP_DIM);
            if (pet.warming) {
                int bw = COL_W;
                DrawUtils.drawRect(tx, y + 44, bw, 3, 0xFF1A1A2E);
                int fill = bw * (int) Math.min(100, pet.warmingTicks) / 120;
                DrawUtils.drawRect(tx, y + 44, fill, 3, DrawUtils.CHIP_ACCENT);
            }
            return;
        }

        if (!showStats.getValue()) return;

        // stat bars
        String[] labels = {"F", "H", "E", "C", "HP"};
        float[] vals = {pet.hunger, pet.happiness, pet.energy, pet.cleanliness, pet.health};
        int[] colors = {0xFFE8A855, 0xFFE87BA8, 0xFF6BB8FF, 0xFF7BE0A8, 0xFF7BE86B};
        int by = y + 28;
        for (int i = 0; i < 5; i++) {
            int c = colors[i];
            if (i == 4 && pet.health < 30f) c = 0xFFE85555;
            fr.drawStringWithShadow(labels[i], tx, by + 1, DrawUtils.CHIP_DIM);
            int bw = COL_W - 8;
            DrawUtils.drawRect(tx + 8, by, bw, 3, 0xFF1A1A2E);
            int fill = (int) (bw * vals[i] / 100f);
            if (fill > 0) DrawUtils.drawRect(tx + 8, by, fill, 3, c);
            by += 7;
        }
        // xp bar
        fr.drawStringWithShadow("XP", tx, by + 1, DrawUtils.CHIP_DIM);
        int bw = COL_W - 8;
        DrawUtils.drawRect(tx + 8, by, bw, 3, 0xFF1A1A2E);
        int fill = (int) (bw * pet.xp / (float) pet.xpToNext());
        if (fill > 0) DrawUtils.drawRect(tx + 8, by, fill, 3, DrawUtils.CHIP_ACCENT);
    }

    @Override
    public void onHudClick(int button, int mouseX, int mouseY) {
        // mouse coords arrive in scaled GUI pixels, same space the HUD renders in
        ScaledResolution sr = new ScaledResolution(mc);
        int sw = sr.getScaledWidth();
        int sh = sr.getScaledHeight();
        int x = resolveHudX(sw, sh);
        int y = resolveHudY(sw, sh);
        int w = getHudWidthScaled();
        int h = getHudHeightScaled();
        if (!DrawUtils.inBox(mouseX, mouseY, x, y, w, h)) return;

        if (button == 0) {
            openPanel();
        } else if (button == 1) {
            if (pet.stage == PetState.STAGE_EGG) {
                pet.startWarming();
                sfx("random.pop", 1.2f);
            } else if (pet.pat()) {
                sfx("random.pop", 1.6f);
                savePetNow();
            }
        }
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    public void chat(String msg) {
        if (mc.ingameGUI == null) return;
        mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText("§8[§bPet§8] §r" + msg));
    }

    private void sfx(String name, float pitch) {
        if (!sfx.getValue()) return;
        try {
            mc.getSoundHandler().playSound(PositionedSoundRecord.create(new ResourceLocation(name), pitch));
        } catch (Exception ignored) {
        }
    }
}
