package plutonium.gpu;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import plutonium.gpu.vk.VkMemory;

/**
 * Logs frames slower than HITCH_MS to the console (tee'd into jars/logs/console.log), with
 * what happened during them: GC pauses and new vkAllocateMemory calls. Testers can't run a
 * profiler, so this is how a "sometimes choppy" report turns into a cause.
 */
public final class FrameHitch {

    private static final long HITCH_MS = 50;

    private static final List<GarbageCollectorMXBean> gcs = ManagementFactory.getGarbageCollectorMXBeans();
    private static long last;
    private static long lastGcCount, lastGcMillis, lastAllocs;
    private static int hitches;

    private FrameHitch() {}

    /** Call once per frame, from present(). */
    public static void frame() {
        long now = System.nanoTime();
        long ms = last == 0 ? 0 : (now - last) / 1_000_000L;
        last = now;

        // Baselines move every frame so a hitch reports only what happened inside it.
        // Reading the MX bean counters costs well under a microsecond per frame.
        long gcCount = 0, gcMillis = 0;
        for (int i = 0; i < gcs.size(); i++) {
            GarbageCollectorMXBean gc = gcs.get(i);
            gcCount += Math.max(0, gc.getCollectionCount());
            gcMillis += Math.max(0, gc.getCollectionTime());
        }
        long allocs = VkMemory.totalAllocations;
        if (ms >= HITCH_MS && ++hitches > 1) {   // the first is startup/world load
            System.out.println("[hitch] " + ms + "ms frame; gc +" + (gcCount - lastGcCount)
                    + " (" + (gcMillis - lastGcMillis) + "ms); vkAllocateMemory +"
                    + (allocs - lastAllocs) + " (live " + VkMemory.liveAllocations + ")"
                    + "; heap " + usedHeapMb() + "MB");
        }
        lastGcCount = gcCount;
        lastGcMillis = gcMillis;
        lastAllocs = allocs;
    }

    private static long usedHeapMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) >> 20;
    }
}
