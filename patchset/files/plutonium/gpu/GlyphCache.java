package plutonium.gpu;

import java.util.ArrayList;
import java.util.HashMap;

import net.minecraft.client.gui.FontRenderer;
import net.minecraft.util.ResourceLocation;

/**
 * Caches the laid-out glyph quads of whole strings so repeat draws skip the per-character
 * work in {@code FontRenderer.renderStringAtPos} (the 256-char table {@code indexOf} per
 * char, style parsing, per-glyph bindTexture, advance arithmetic). HUD text is highly
 * repetitive frame-to-frame — tab list, scoreboard, chat history — but was re-laid-out
 * every frame (~1.2-1.6k quads/frame at /kp2).
 *
 * <p>Record: on a cache miss {@code FontRenderer.renderString} renders normally while
 * {@link FontBatch#glyph} mirrors every emitted quad here, positions stored relative to the
 * string origin, grouped into runs per font page. Replay: re-emit the recorded quads into
 * FontBatch at the new origin — under the CURRENT modelview (FontBatch bakes the modelview
 * at emit time), so cached strings work in GUIs and on nametag billboards alike.
 *
 * <p>Re-tinting: each glyph stores whether its colour was the call's base colour or an
 * absolute §-code colour. Replay applies the caller's base colour (and always its alpha) to
 * base-flagged glyphs, so one entry serves every colour/alpha variant of the same text —
 * chat-line fade does not bloat the cache. The key therefore EXCLUDES colour:
 * shadow flag + unicode flag + font identity + text.
 *
 * <p>Not cached (recording aborts, string just renders normally): §k obfuscated text
 * (re-randomised per frame) and underline/strikethrough (drawn via immediate Tessellator
 * quads, not glyphs). Cache cleared on font resource reload; wholesale-cleared when the
 * entry cap is hit (churny keys like fps counters age out that way).
 */
public final class GlyphCache {

    public static boolean ENABLED = true;

    private static final int MAX_ENTRIES = 4096;
    /** Per glyph: 16 pos/uv floats + rgb + base-colour flag. (Read by FontBatch.glyphRun.) */
    static final int STRIDE = 20;

    private static final class Run {
        final Object page;
        float[] data = new float[8 * STRIDE];
        int len = 0;

        Run(Object page) { this.page = page; }

        void ensure(int more) {
            if (len + more > data.length) {
                float[] n = new float[Math.max(data.length * 2, len + more)];
                System.arraycopy(data, 0, n, 0, len);
                data = n;
            }
        }
    }

    private static final class Entry {
        final ArrayList<Run> runs = new ArrayList<Run>(2);
        float advance;
    }

    private static final HashMap<String, Entry> CACHE = new HashMap<String, Entry>();

    // --- recording state (single-threaded render path) ---
    private static boolean recording = false;
    private static Entry rec;
    private static String recKey;
    private static float recX, recY, baseR, baseG, baseB;

    private GlyphCache() {}

    public static boolean isRecording() { return recording; }

    /** Build the cache key. Colour is intentionally excluded (see re-tinting above). */
    public static String key(FontRenderer fr, String text, boolean dropShadow, boolean unicode) {
        return (dropShadow ? 'S' : 'N') + (unicode ? "U" : "A")
                + System.identityHashCode(fr) + '|' + text;
    }

    public static void beginRecord(String key, float x, float y, float r, float g, float b) {
        if (!ENABLED) return;
        recording = true;
        rec = new Entry();
        recKey = key;
        recX = x; recY = y;
        baseR = r; baseG = g; baseB = b;
    }

    /** Bail out of caching the in-flight string (obfuscated / underline / strikethrough). */
    public static void abortRecord() {
        recording = false;
        rec = null;
    }

    public static void endRecord(float advance) {
        if (!recording) return;
        recording = false;
        rec.advance = advance;
        if (CACHE.size() >= MAX_ENTRIES) CACHE.clear();
        CACHE.put(recKey, rec);
        rec = null;
    }

    /** Mirror one emitted glyph quad (called from {@link FontBatch#glyph} while recording). */
    public static void record(Object page,
                              float x0, float y0, float u0, float v0,
                              float x1, float y1, float u1, float v1,
                              float x2, float y2, float u2, float v2,
                              float x3, float y3, float u3, float v3,
                              float r, float g, float b) {
        Run run = rec.runs.isEmpty() ? null : rec.runs.get(rec.runs.size() - 1);
        if (run == null || run.page != page) {
            run = new Run(page);
            rec.runs.add(run);
        }
        run.ensure(STRIDE);
        float[] d = run.data;
        int i = run.len;
        d[i]      = x0 - recX; d[i + 1]  = y0 - recY; d[i + 2]  = u0; d[i + 3]  = v0;
        d[i + 4]  = x1 - recX; d[i + 5]  = y1 - recY; d[i + 6]  = u1; d[i + 7]  = v1;
        d[i + 8]  = x2 - recX; d[i + 9]  = y2 - recY; d[i + 10] = u2; d[i + 11] = v2;
        d[i + 12] = x3 - recX; d[i + 13] = y3 - recY; d[i + 14] = u3; d[i + 15] = v3;
        d[i + 16] = r; d[i + 17] = g; d[i + 18] = b;
        d[i + 19] = (r == baseR && g == baseG && b == baseB) ? 1.0f : 0.0f;
        run.len += STRIDE;
    }

    /**
     * Replay a cached string at (x,y) tinted with the caller's base colour.
     * Returns the cached advance, or NaN on miss.
     */
    public static float replay(FontRenderer fr, String key, float x, float y,
                               float r, float g, float b, float a) {
        if (!ENABLED) return Float.NaN;
        Entry e = CACHE.get(key);
        if (e == null) { DrawStats.glyphMisses++; return Float.NaN; }
        DrawStats.glyphHits++;
        int n = e.runs.size();
        for (int ri = 0; ri < n; ri++) {
            Run run = e.runs.get(ri);
            fr.glyphBeginForReplay((ResourceLocation) run.page);
            FontBatch.glyphRun(run.data, run.len, x, y, r, g, b, a);
        }
        return e.advance;
    }

    /** Drop everything (font resource reload, glyph metrics changed). */
    public static void clear() {
        CACHE.clear();
        abortRecord();
    }
}
