package plutonium.gpu;

/** Flush all deferred geometry batches. Each batch self-guards against re-entry. */
public final class Batches {
    private Batches() {}

    /**
     * True while RenderGlobal.renderEntities is on the stack. Gates ArrowBatch/ItemBatch
     * capture: entity-pass geometry may be deferred to the pre-renderHand flush (same
     * projection), but hand items / GUI items render under different matrices and must
     * stay inline.
     */
    public static boolean entityPassActive = false;

    /**
     * GUI quad/rect batching (RectBatch, GuiQuadBatch) is a HUD optimization that assumes a
     * simple draw order and no scissor clipping. Interactive screens (the ClickGUI, inventory,
     * menus) interleave rects/text with intended layering and clip scrollable regions with
     * glScissor — deferring their draws breaks both. Those screens are paused and not perf
     * critical, so batch only when no screen is open (the in-game HUD, where it matters).
     */
    public static boolean guiBatchingActive() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getMinecraft();
        return mc == null || mc.currentScreen == null;
    }

    public static void flushAll() {
        // The GL state setup INSIDE a batch's flush (e.g. RectBatch's enableBlend) re-triggers
        // this method via the GlStateManager flush hooks. Re-entering would drain the LATER
        // batches mid-sequence — FontBatch drew before RectBatch's upload, putting all HUD
        // text underneath its own translucent chip/scoreboard backgrounds. While any batch is
        // mid-flush, this sequence is already in control of the ordering; skip.
        if (ModelBatch.flushing || GuiQuadBatch.flushing || RectBatch.flushing
                || RoundRectBatch.flushing || FontBatch.flushing) return;
        ModelBatch.flush();
        GuiQuadBatch.flush();
        // Rounded panels/chips are backgrounds: flush BEFORE RectBatch so sharp foreground
        // rects (slot grids, separators, accent bars) and text land on top of them, matching
        // the old single-batch call order where rounded backgrounds were drawn first.
        RoundRectBatch.flush();
        RectBatch.flush();
        FontBatch.flush();
    }
}
