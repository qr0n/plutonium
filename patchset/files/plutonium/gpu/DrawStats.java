package plutonium.gpu;

/** Lightweight per-frame draw-call counters for perf profiling. */
public final class DrawStats {
    public static long chunk;      // terrain chunk-layer draws
    public static long entity;     // entity-shader draws (models)
    public static long immOther;   // world-phase immediate draws (particles, items, nametags)
    public static long immGui;     // gui-phase immediate draws (hud, font, scoreboard, tab)
    public static boolean inGui;   // true while the in-game GUI overlay is rendering
    // world-phase immediate draws broken out by program (diagnostic)
    public static long immW_color, immW_tex, immW_texcol, immW_block;

    // world-pass time buckets (ns) — where the main-thread render goes (plan: render-cuts)
    public static long nsSky, nsClouds, nsSetup, nsChunks, nsEntities, nsParticles, nsHand;
    // hot-path counters: actual ModelBatch draws, font quads built, uniform syncs,
    // MVP matrix uploads actually performed (vs skipped by the version gate)
    public static long modelFlushes, fontQuads, uniformSyncs, mvpUploads;
    // glyph-layout cache effectiveness (GlyphCache)
    public static long glyphHits, glyphMisses;
    // render-time buckets OUTSIDE world+gui — hunting the unattributed remainder
    public static long nsFrameInit, nsMouse, nsClearBind, nsPostWorld, nsNtFlush;
    // inside the ent bucket: per-entity render CPU (renderEntitySimple, includes nested
    // ModelBatch flushes), total ModelBatch upload time, tile-entity dispatch; call counts
    public static long nsEntCpu, nsModelFlush, nsTile;
    public static long cEnt, cTile;
    // quads captured by the deferred arrow/item batches (proof they're active in a scene)
    public static long arrowQuads, itemQuads;

    public static void resetFrame() {
        chunk = 0; entity = 0; immOther = 0; immGui = 0;
        immW_color = 0; immW_tex = 0; immW_texcol = 0; immW_block = 0;
        nsSky = 0; nsClouds = 0; nsSetup = 0; nsChunks = 0;
        nsEntities = 0; nsParticles = 0; nsHand = 0;
        modelFlushes = 0; fontQuads = 0; uniformSyncs = 0; mvpUploads = 0;
        glyphHits = 0; glyphMisses = 0;
        nsFrameInit = 0; nsMouse = 0; nsClearBind = 0; nsPostWorld = 0; nsNtFlush = 0;
        nsEntCpu = 0; nsModelFlush = 0; nsTile = 0;
        cEnt = 0; cTile = 0;
        arrowQuads = 0; itemQuads = 0;
    }
    public static long total() { return chunk + entity + immOther + immGui; }
}
