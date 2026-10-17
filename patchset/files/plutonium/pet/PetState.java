package plutonium.pet;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Random;

/**
 * Data model + simulation for a single Pocket Pet.
 *
 * Stats are 0..100 and decay every game tick. Health drains when any need is
 * critical and recovers when the pet is well cared for; at 0 health the pet
 * dies. The pet grows Egg -> Baby -> Child -> Teen -> Adult -> Elder via XP.
 */
public class PetState {

    public static final int STAGE_EGG   = 0;
    public static final int STAGE_BABY  = 1;
    public static final int STAGE_CHILD = 2;
    public static final int STAGE_TEEN  = 3;
    public static final int STAGE_ADULT = 4;
    public static final int STAGE_ELDER = 5;
    public static final String[] STAGE_NAMES = {"Egg", "Baby", "Child", "Teen", "Adult", "Elder"};
    /** Level at which the pet reaches the given stage index. */
    private static final int[] STAGE_LEVELS = {1, 1, 3, 5, 7, 10};

    // Moods
    public static final int MOOD_DEAD = 0, MOOD_EGG = 1, MOOD_SLEEP = 2, MOOD_SICK = 3,
            MOOD_HUNGRY = 4, MOOD_DIRTY = 5, MOOD_TIRED = 6, MOOD_SAD = 7, MOOD_HAPPY = 8;

    public static final String[] MOOD_LABELS = {"gone", "egg", "sleeping", "sick",
            "hungry", "dirty", "tired", "sad", "happy"};

    public static final int[] MOOD_COLORS = {0xFF9A9AA8, 0xFFD8CFA8, 0xFF9FB4FF, 0xFF7BE07B,
            0xFFFFC46B, 0xFFB8A88A, 0xFF8FA8E8, 0xFF8FA0C8, 0xFF8FE8A8};

    private static final Random RNG = new Random();

    private static final String[] FIRST_NAMES = {"Mochi", "Pip", "Boba", "Nori", "Waffles",
            "Puck", "Tofu", "Miso", "Bun", "Clover", "Dumpling", "Sprout", "Pepper", "Muffin",
            "Onyx", "Taco", "Biscuit", "Ziggy", "Maple", "Pebble"};
    private static final String[] SPECIES = {"puff", "bloop", "sprout", "ember", "gloom", "nix"};

    public String name = "Mochi";
    public String species = "puff";
    public int    stage = STAGE_EGG;
    public boolean alive   = true;
    public boolean asleep  = false;
    public boolean warming = false;   // egg being held warm
    public int    warmingTicks = 0;

    public float hunger      = 80f;
    public float happiness   = 70f;
    public float energy      = 80f;
    public float cleanliness = 80f;
    public float health      = 100f;

    public int xp = 0;
    public int level = 1;

    public long bornAt      = System.currentTimeMillis();
    public long lastSeen    = System.currentTimeMillis();
    public long lastActionAt = 0;
    public int  flashTicks  = 0;      // happy bounce after an action
    public long deadAt      = 0;

    // ------------------------------------------------------------------
    // lifecycle
    // ------------------------------------------------------------------

    public static PetState newEgg() {
        PetState p = new PetState();
        p.name = pick(FIRST_NAMES);
        p.species = pick(SPECIES);
        p.stage = STAGE_EGG;
        p.alive = true;
        p.hunger = 80f; p.happiness = 70f; p.energy = 80f; p.cleanliness = 80f; p.health = 100f;
        p.bornAt = System.currentTimeMillis();
        p.lastSeen = p.bornAt;
        return p;
    }

    /** Replace this pet with a brand new egg (used after death). */
    public void hatchNewEgg() {
        PetState fresh = newEgg();
        this.name = fresh.name;
        this.species = fresh.species;
        this.stage = STAGE_EGG;
        this.alive = true;
        this.asleep = false;
        this.warming = false;
        this.warmingTicks = 0;
        this.hunger = fresh.hunger;
        this.happiness = fresh.happiness;
        this.energy = fresh.energy;
        this.cleanliness = fresh.cleanliness;
        this.health = 100f;
        this.xp = 0;
        this.level = 1;
        this.bornAt = System.currentTimeMillis();
        this.lastSeen = this.bornAt;
        this.deadAt = 0;
        this.flashTicks = 40;
    }

    public void startWarming() {
        if (stage == STAGE_EGG && alive) warming = true;
    }

    public int xpToNext() {
        return 30 + (level - 1) * 15;
    }

    public void addXp(int amount) {
        if (!alive || stage == STAGE_EGG) return;
        xp += amount;
        while (xp >= xpToNext()) {
            xp -= xpToNext();
            level++;
            // evolve if we crossed the next stage threshold
            if (stage < STAGE_ELDER && level >= STAGE_LEVELS[stage + 1]) {
                stage++;
                flashTicks = 50;
            } else {
                flashTicks = Math.max(flashTicks, 25);
            }
        }
    }

    // ------------------------------------------------------------------
    // actions (return false when not allowed)
    // ------------------------------------------------------------------

    public boolean canAct() {
        return alive && stage > STAGE_EGG;
    }

    /** Global action cooldown so buttons can't be mashed. */
    public boolean offCooldown(long ms) {
        if (!canAct()) return false;
        return System.currentTimeMillis() - lastActionAt >= ms;
    }

    private void markActed() {
        lastActionAt = System.currentTimeMillis();
    }

    public boolean feed() {
        if (!canAct() || asleep || !offCooldown(3000)) return false;
        hunger = clamp(hunger + 28f);
        if (hunger >= 99.5f) happiness = clamp(happiness - 6f); // stuffed, not thrilled
        else happiness = clamp(happiness + 4f);
        addXp(4);
        flashTicks = 20;
        markActed();
        return true;
    }

    public boolean play() {
        if (!canAct() || asleep || !offCooldown(3000)) return false;
        happiness = clamp(happiness + 10f);
        energy = clamp(energy - 8f);
        hunger = clamp(hunger - 5f);
        cleanliness = clamp(cleanliness - 3f);
        addXp(8);
        flashTicks = 25;
        markActed();
        return true;
    }

    public boolean clean() {
        if (!canAct() || asleep || !offCooldown(3000)) return false;
        cleanliness = clamp(cleanliness + 40f);
        happiness = clamp(happiness + 2f);
        addXp(3);
        flashTicks = 20;
        markActed();
        return true;
    }

    public boolean pat() {
        if (!canAct() || asleep || !offCooldown(1000)) return false;
        happiness = clamp(happiness + 5f);
        addXp(1);
        flashTicks = 15;
        markActed();
        return true;
    }

    public void toggleSleep() {
        if (!canAct()) return;
        if (asleep) {
            asleep = false;
        } else {
            if (energy > 95f) return; // too wired to fall asleep
            asleep = true;
        }
        markActed();
    }

    public void winTreatGame() {
        if (!canAct()) return;
        happiness = clamp(happiness + 8f);
        addXp(15);
        flashTicks = 40;
        markActed();
    }

    public void loseTreatGame() {
        if (!canAct()) return;
        happiness = clamp(happiness + 2f); // for trying
        markActed();
    }

    // ------------------------------------------------------------------
    // simulation
    // ------------------------------------------------------------------

    /** Advance the simulation by {@code dt} game ticks (usually 1). */
    public void tick(float dt) {
        if (!alive) return;
        if (stage == STAGE_EGG) {
            if (warming) {
                warmingTicks += dt;
                if (warmingTicks >= 120f) {   // ~6s of warming
                    warming = false;
                    warmingTicks = 0;
                    stage = STAGE_BABY;
                    flashTicks = 50;
                }
            }
            lastSeen = System.currentTimeMillis();
            return;
        }
        // Rates: full hunger lasts ~42 min, happiness ~75 min, energy ~60 min,
        // cleanliness ~2.4 h. A nap takes ~3 min to fully recharge energy.
        if (asleep) {
            hunger      = clamp(hunger - 0.0010f * dt);
            happiness   = clamp(happiness - 0.0004f * dt);
            cleanliness = clamp(cleanliness - 0.0003f * dt);
            energy      = clamp(energy + 0.025f * dt);
            if (energy >= 100f) {
                asleep = false;
                addXp(6);
                flashTicks = 30;
            }
        } else {
            hunger      = clamp(hunger - 0.0020f * dt);
            happiness   = clamp(happiness - 0.0011f * dt);
            energy      = clamp(energy - 0.0014f * dt);
            cleanliness = clamp(cleanliness - 0.0007f * dt);
        }

        float worst = Math.min(Math.min(hunger, cleanliness), Math.min(happiness, energy));
        if (worst <= 0f) {
            health = clamp(health - 0.006f * dt);
        } else if (hunger < 20f || cleanliness < 15f) {
            health = clamp(health - 0.003f * dt);
        } else if (worst > 55f) {
            health = clamp(health + 0.002f * dt);
        }

        if (health <= 0f) die();
        lastSeen = System.currentTimeMillis();
    }

    private void die() {
        alive = false;
        asleep = false;
        deadAt = System.currentTimeMillis();
        health = 0f;
    }

    /**
     * Apply decay for the time the game was closed. Capped at 24h and the pet
     * can't die offline (health floored at 1) — it just comes back grumpy.
     *
     * @return a human-readable summary of what happened, or null if nothing did.
     */
    public String applyOfflineAgeing() {
        long ms = System.currentTimeMillis() - lastSeen;
        long ticks = ms / 50;
        if (ticks <= 40 || !alive || stage == STAGE_EGG) {
            lastSeen = System.currentTimeMillis();
            return null;
        }
        ticks = Math.min(ticks, 24L * 3600L * 20L);

        boolean wasAsleep = asleep;
        // Coarse steps keep this cheap even for long absences.
        float step = 120f; // 6s per step
        for (float t = 0; t < ticks; t += step) {
            tick(Math.min(step, ticks - t));
        }
        health = Math.max(health, 1f); // survives the trip, barely

        if (!alive) {
            // too neglected while away — it barely made it, collapsed into a nap
            alive = true;
            health = 1f;
            deadAt = 0;
            asleep = true;
            energy = 15f;
            return name + " nearly didn't make it while you were away...";
        }
        if (!asleep && !wasAsleep && energy < 10f) {
            asleep = true;         // collapsed into a nap
            energy = 20f;
        }

        StringBuilder sb = new StringBuilder();
        if (hunger < 25f) sb.append(" is starving");
        if (cleanliness < 25f) { if (sb.length() > 0) sb.append(" and"); sb.append(" is filthy"); }
        if (happiness < 25f) { if (sb.length() > 0) sb.append(","); sb.append(" and feels lonely"); }
        if (sb.length() > 0) return name + sb + ". Hurry!";
        if (asleep) return name + " dozed off while you were away.";
        return name + " missed you while you were away.";
    }

    // ------------------------------------------------------------------
    // mood
    // ------------------------------------------------------------------

    public int mood() {
        if (!alive) return MOOD_DEAD;
        if (stage == STAGE_EGG) return MOOD_EGG;
        if (asleep) return MOOD_SLEEP;
        if (health < 25f) return MOOD_SICK;
        if (hunger < 25f) return MOOD_HUNGRY;
        if (cleanliness < 20f) return MOOD_DIRTY;
        if (energy < 18f) return MOOD_TIRED;
        if (happiness < 30f) return MOOD_SAD;
        return MOOD_HAPPY;
    }

    public String moodLabel() { return MOOD_LABELS[mood()]; }
    public int moodColor()    { return MOOD_COLORS[mood()]; }

    public String ageString() {
        long ms = System.currentTimeMillis() - bornAt;
        long days = ms / 86400000L;
        long hours = (ms / 3600000L) % 24;
        if (days > 0) return days + "d " + hours + "h";
        long minutes = (ms / 60000L) % 60;
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1, minutes) + "m";
    }

    // ------------------------------------------------------------------
    // persistence
    // ------------------------------------------------------------------

    public void toJson(JsonObject o) {
        o.addProperty("name", name);
        o.addProperty("species", species);
        o.addProperty("stage", stage);
        o.addProperty("alive", alive);
        o.addProperty("asleep", asleep);
        o.addProperty("warming", warming);
        o.addProperty("warming_ticks", (int) warmingTicks);
        o.addProperty("hunger", hunger);
        o.addProperty("happiness", happiness);
        o.addProperty("energy", energy);
        o.addProperty("cleanliness", cleanliness);
        o.addProperty("health", health);
        o.addProperty("xp", xp);
        o.addProperty("level", level);
        o.addProperty("born_at", bornAt);
        o.addProperty("last_seen", System.currentTimeMillis());
        o.addProperty("dead_at", deadAt);
    }

    public static PetState fromJson(JsonObject o) {
        PetState p = newEgg();
        p.name = str(o, "name", p.name);
        p.species = str(o, "species", p.species);
        if (!isSpecies(p.species)) p.species = "puff";
        p.stage = num(o, "stage", p.stage);
        if (p.stage < STAGE_EGG || p.stage > STAGE_ELDER) p.stage = STAGE_EGG;
        p.alive = o.has("alive") ? o.get("alive").getAsBoolean() : true;
        p.asleep = o.has("asleep") ? o.get("asleep").getAsBoolean() : false;
        p.warming = o.has("warming") ? o.get("warming").getAsBoolean() : false;
        p.warmingTicks = num(o, "warming_ticks", 0);
        p.hunger = clampF(num(o, "hunger", 80f));
        p.happiness = clampF(num(o, "happiness", 70f));
        p.energy = clampF(num(o, "energy", 80f));
        p.cleanliness = clampF(num(o, "cleanliness", 80f));
        p.health = clampF(num(o, "health", 100f));
        p.xp = Math.max(0, num(o, "xp", 0));
        p.level = Math.max(1, num(o, "level", 1));
        p.bornAt = o.has("born_at") ? o.get("born_at").getAsLong() : System.currentTimeMillis();
        p.lastSeen = o.has("last_seen") ? o.get("last_seen").getAsLong() : System.currentTimeMillis();
        p.deadAt = o.has("dead_at") ? o.get("dead_at").getAsLong() : 0;
        return p;
    }

    private static boolean isSpecies(String s) {
        for (String c : SPECIES) if (c.equals(s)) return true;
        return false;
    }

    private static String pick(String[] arr) { return arr[RNG.nextInt(arr.length)]; }

    private static String str(JsonObject o, String k, String def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }

    private static int num(JsonObject o, String k, int def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
    }

    private static float num(JsonObject o, String k, float def) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() ? e.getAsFloat() : def;
    }

    static float clamp(float v) { return v < 0f ? 0f : (v > 100f ? 100f : v); }
    private static float clampF(float v) { return clamp(v); }
}
