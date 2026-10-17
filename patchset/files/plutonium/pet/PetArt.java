package plutonium.pet;

import net.minecraft.client.gui.clickgui.DrawUtils;

/**
 * 12x12 pixel art for the Pocket Pet. Each stage has a base grid; transient
 * states (blink, sleep, sick, dead, egg-warming) tweak the palette or cells.
 * Species only change the body palette.
 */
public final class PetArt {

    private PetArt() {}

    // Cell legend:
    //  .  transparent          O  outline
    //  B  body                 S  body shadow
    //  H  body highlight       E  eye (dark)
    //  W  eye shine            P  blush
    //  M  mouth / dark detail

    private static final String[] EGG = {
            "............",
            "....OOOO....",
            "...OBBBBO...",
            "..OBHBBBSBO.",
            "..OBBSBBBBO.",
            "..OBHBBBSBO.",
            "..OBBBBSBBO.",
            "...OBBBSBO..",
            "...OBBBBO...",
            "....OOOO....",
            "............",
            "............",
    };

    private static final String[] BABY = {
            "............",
            "............",
            "....OOOO....",
            "...OBBBBO...",
            "..OBEBBEBO..",
            "..OBWBWBBO..",
            "..OBBMMBBO..",
            "..OBPBBPBO..",
            "...OBBBBO...",
            "....OOOO....",
            "............",
            "............",
    };

    private static final String[] CHILD = {
            "............",
            "...OOOOOO...",
            "..OBBBBBBO..",
            ".OBEBBBBEBO.",
            ".OBWBBBWBBO.",
            ".OBPBBBBPBO.",
            ".OBBBMMBBBO.",
            ".OBBBBBBBBO.",
            "..OBBHBBBO..",
            "...OBBBBBO..",
            "....OOOOO...",
            "............",
    };

    private static final String[] TEEN = {
            "............",
            "..O..OO..O..",
            "..OBBBBBBO..",
            ".OBEBBBBEBO.",
            ".OBWBBBWBBO.",
            ".OBBBBBBBBO.",
            ".OBBMMMMBO..",
            ".OBBBBBBBBO.",
            ".OBBHHHHBBO.",
            "..OBBBBSBO..",
            "...OOOOOO...",
            "............",
    };

    private static final String[] ADULT = {
            "............",
            "..O..OO..O..",
            ".OOBBBBBBBO.",
            "OBEBBBBBBEBO",
            "OBWBBBBBBWBO",
            "OBBBBBBBBBBO",
            "OBPBBBMMBBBO",
            "OBBBBBBBBBBO",
            "OBBBHHHHBBBO",
            ".OBBBBBBBBO.",
            "..OBBB.BBO..",
            "............",
    };

    private static final String[] ELDER = {
            "............",
            "....HHHH....",
            ".OOBBBBBBBO.",
            "OBEBBBBBBEBO",
            "OBWBBBBBBWBO",
            "OBBBBBBBBBBO",
            "OBPBBBMMBBBO",
            "OBBMMMMMMBBO",
            "OBBBMMMMBBBO",
            ".OBBBBBBBBO.",
            "..OBBB.BBO..",
            "............",
    };

    private static final String[][] STAGE_GRIDS = {EGG, BABY, CHILD, TEEN, ADULT, ELDER};

    // 8x8 treat (apple)
    private static final String[] TREAT = {
            "...O....",
            "..OOO...",
            ".ORRRRO.",
            "ORRWWRRO",
            "ORRWWRRO",
            "ORRRRRRO",
            ".ORRRRO.",
            "..OOOO..",
    };

    // 3x3 heart (blush colour)
    private static final String[] HEART = {
            "P.P",
            "PPP",
            ".P.",
    };

    // ------------------------------------------------------------------
    // palettes
    // ------------------------------------------------------------------

    public static final int OUTLINE = 0xFF2B2B3A;
    public static final int EYE     = 0xFF23232E;
    public static final int MOUTH   = 0xFF3A3140;
    public static final int BLUSH   = 0xFFFF9E9E;

    /** body / shadow / highlight per species. */
    public static int[] palette(String species) {
        switch (species) {
            case "bloop":  return new int[]{0xFF7FB4FF, 0xFF5A86D6, 0xFFC4E0FF};
            case "sprout": return new int[]{0xFF8FD97A, 0xFF63AC52, 0xFFC9F0B4};
            case "ember":  return new int[]{0xFFFFA45B, 0xFFD4763A, 0xFFFFD2A1};
            case "gloom":  return new int[]{0xFFB48CFF, 0xFF8A63D2, 0xFFE0CCFF};
            case "nix":    return new int[]{0xFF8A8FA8, 0xFF61657D, 0xFFC6CADD};
            case "puff":
            default:       return new int[]{0xFFF3E2C8, 0xFFD9BC94, 0xFFFFF6E6};
        }
    }

    /** Species body colour, for little indicator dots in the UI. */
    public static int speciesColor(String species) {
        switch (species) {
            case "bloop":  return 0xFF7FB4FF;
            case "sprout": return 0xFF8FD97A;
            case "ember":  return 0xFFFFA45B;
            case "gloom":  return 0xFFB48CFF;
            case "nix":    return 0xFF8A8FA8;
            case "puff":
            default:       return 0xFFF3E2C8;
        }
    }

    // ------------------------------------------------------------------
    // drawing
    // ------------------------------------------------------------------

    /**
     * Draw the pet's 12x12 art at (x, y) with pixel size {@code px}.
     *
     * @param timeMs wall-clock ms, drives idle bob / blink / egg shake
     */
    public static void drawPet(PetState pet, int x, int y, int px, long timeMs) {
        boolean dead = !pet.alive;
        boolean asleep = pet.asleep;
        boolean sick = pet.mood() == PetState.MOOD_SICK;
        boolean egg = pet.stage == PetState.STAGE_EGG;

        // idle motion
        int yOff = 0;
        if (!dead && !egg && !asleep) {
            yOff = (int) Math.round(Math.sin(timeMs / 320.0) * px * 0.35);
        } else if (!dead && !egg && asleep) {
            yOff = (int) Math.round(Math.sin(timeMs / 900.0) * px * 0.18); // slow breathing
        }
        int xOff = 0;
        if (egg && pet.warming) {
            xOff = (int) Math.round(Math.sin(timeMs / 70.0) * px * 0.3); // wobble
        }

        String[] grid = STAGE_GRIDS[pet.stage];
        int[] pal = palette(pet.species);
        int body = pal[0], shadow = pal[1], hi = pal[2];

        if (dead) {
            body = 0xFF9A9AA8; shadow = 0xFF74768A; hi = 0xFFC0C2CE;
        } else if (sick) {
            body = 0xFFB8D89A; shadow = 0xFF8FB474; hi = 0xFFDCEFC4;
        }

        // blink: every ~4.2s, eyes closed for ~180ms (asleep/dead stay closed)
        boolean eyesClosed = dead || asleep || egg || (timeMs % 4200) < 180;

        int blush = dead ? shadow : BLUSH;   // a dead pet's blush goes grey
        for (int gy = 0; gy < grid.length; gy++) {
            String row = grid[gy];
            for (int gx = 0; gx < row.length(); gx++) {
                int color = cellColor(row.charAt(gx), body, shadow, hi, eyesClosed, blush);
                if (color != 0) {
                    DrawUtils.drawRect(x + gx * px + xOff, y + gy * px + yOff, px, px, color);
                }
            }
        }

        // decorations
        if (pet.flashTicks > 0 && !dead && !egg) {
            drawHeart(x + 9 * px + xOff, y - 2 * px + yOff, px);
        }
        if (asleep) {
            // floating z's
            float z = (timeMs % 2000) / 2000f;
            DrawUtils.drawRect(x + 10 * px, y - 1 * px - (int) (z * 2), px, px, 0xFF9FB4FF);
            DrawUtils.drawRect(x + 11 * px, y - 2 * px - (int) (z * 2), px, px, 0xFFB8C8FF);
        }
        if (sick) {
            // stink squiggles above the head
            int sy = y - 2 * px;
            DrawUtils.drawRect(x + 4 * px, sy, px, px, 0xFF7BE07B);
            DrawUtils.drawRect(x + 5 * px, sy - px, px, px, 0xFF7BE07B);
            DrawUtils.drawRect(x + 6 * px, sy, px, px, 0xFF7BE07B);
        }
    }

    /** Draw the treat (apple) at (x, y) with pixel size {@code px}. */
    public static void drawTreat(int x, int y, int px) {
        int red = 0xFFE85555, shine = 0xFFFFF0D8;
        for (int gy = 0; gy < TREAT.length; gy++) {
            String row = TREAT[gy];
            for (int gx = 0; gx < row.length(); gx++) {
                char c = row.charAt(gx);
                int color = c == 'O' ? OUTLINE : c == 'R' ? red : c == 'W' ? shine : 0;
                if (color != 0) DrawUtils.drawRect(x + gx * px, y + gy * px, px, px, color);
            }
        }
    }

    public static void drawHeart(int x, int y, int px) {
        for (int gy = 0; gy < HEART.length; gy++) {
            String row = HEART[gy];
            for (int gx = 0; gx < row.length(); gx++) {
                if (row.charAt(gx) == 'P') DrawUtils.drawRect(x + gx * px, y + gy * px, px, px, BLUSH);
            }
        }
    }

    private static int cellColor(char c, int body, int shadow, int hi, boolean eyesClosed, int blush) {
        switch (c) {
            case '.':  return 0;
            case 'O':  return OUTLINE;
            case 'B':  return body;
            case 'S':  return shadow;
            case 'H':  return hi;
            case 'E':  return eyesClosed ? MOUTH : EYE;   // closed = dark line
            case 'W':  return eyesClosed ? body : 0xFFFFFFFF;
            case 'P':  return blush;
            case 'M':  return MOUTH;
            default:   return 0;
        }
    }
}
