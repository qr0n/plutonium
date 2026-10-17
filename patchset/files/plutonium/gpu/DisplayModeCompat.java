package plutonium.gpu;

/**
 * Drop-in replacement for org.lwjgl.opengl.DisplayMode (LWJGL 2).
 * Provides the same API surface so callers (Config.java, GameSettings.java, etc.)
 * can migrate with minimal changes — just swap the import.
 */
public class DisplayModeCompat {

    private final int width;
    private final int height;
    private final int bpp;
    private final int freq;

    public DisplayModeCompat(int width, int height) {
        this(width, height, 32, 60);
    }

    public DisplayModeCompat(int width, int height, int bpp, int freq) {
        this.width = width;
        this.height = height;
        this.bpp = bpp;
        this.freq = freq;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getBitsPerPixel() {
        return bpp;
    }

    public int getFrequency() {
        return freq;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof DisplayModeCompat)) return false;
        DisplayModeCompat other = (DisplayModeCompat) obj;
        return width == other.width && height == other.height && bpp == other.bpp && freq == other.freq;
    }

    @Override
    public int hashCode() {
        return 31 * (31 * (31 * width + height) + bpp) + freq;
    }

    @Override
    public String toString() {
        return width + " x " + height + " x " + bpp + " @" + freq + "Hz";
    }
}
