package plutonium;

import net.minecraft.network.NetworkManager;

/**
 * Per-second network-path cost counters (-Dplutonium.netdebug=true), the networking
 * counterpart of {@link plutonium.gpu.DrawStats}: where inbound CPU goes, not how the
 * connection feels (that's NetMonitor's job). One [NET] line per second answers which
 * is the bigger fish — zlib inflate (→ libdeflate), chunk apply on the main thread
 * (→ off-thread parsing), or the per-packet main-thread handoff (→ batch draining).
 *
 * Inflate fields are written only by the Netty IO thread, chunk/task fields only by
 * the main thread; volatile is for cross-thread reads at print time. A reset racing
 * one in-flight increment can drop a sample, which is fine for a debug line.
 */
public final class NetStats {

    public static final boolean ENABLED = Boolean.getBoolean("plutonium.netdebug");

    // Netty IO thread: compression-decoder throughput (post-framing, pre-decode)
    private static volatile long wireBytes;
    private static volatile long inflatedBytes;
    private static volatile long inflateNs;
    private static volatile long inflatePackets;

    // Main thread: chunk-data apply cost and scheduled-task backlog at drain time
    private static volatile long chunkApplyNs;
    private static volatile long chunkCount;
    private static volatile int taskQueuePeak;

    // Main thread: what the inbound path costs the frame loop. A burst of enqueued packets
    // is drained whole at the top of one frame, so the peaks matter and the averages hide it.
    private static volatile long drainNs;
    private static volatile long drainMaxNs;
    private static volatile long frameMaxNs;

    // rx/tx deltas ride on NetworkManager's existing NetMonitor counters
    private static NetworkManager lastConnection;
    private static long lastRxTotal, lastTxTotal;

    private NetStats() {}

    /** Packet arrived below the compression threshold (or compression is off). */
    public static void recordUncompressed(int bytes) {
        wireBytes += bytes;
    }

    public static void recordInflate(int compressedBytes, int decompressedBytes, long ns) {
        wireBytes += compressedBytes;
        inflatedBytes += decompressedBytes;
        inflateNs += ns;
        inflatePackets++;
    }

    /** Main-thread cost of applying S21/S26 chunk data (fillChunk + render invalidation). */
    public static void recordChunkApply(long ns, int chunks) {
        chunkApplyNs += ns;
        chunkCount += chunks;
    }

    /** Scheduled-task backlog observed when the tick drains it (mostly enqueued packets). */
    public static void recordTaskQueueDepth(int depth) {
        if (depth > taskQueuePeak) taskQueuePeak = depth;
    }

    /** Main-thread time one frame spent draining that backlog. */
    public static void recordDrain(long ns) {
        drainNs += ns;
        if (ns > drainMaxNs) drainMaxNs = ns;
    }

    /** Wall time one frame's work took, excluding any deliberate framerate-cap sleep. */
    public static void recordFrame(long ns) {
        if (ns > frameMaxNs) frameMaxNs = ns;
    }

    /** Print one summary line and zero the buckets. Called once per second by the FPS roll. */
    public static void printAndReset(NetworkManager connection) {
        String rx = "?", tx = "?";
        if (connection != null) {
            long rxTotal = connection.inboundPacketCount.get();
            long txTotal = connection.outboundPacketCount.get();
            if (connection == lastConnection) {
                rx = String.valueOf(rxTotal - lastRxTotal);
                tx = String.valueOf(txTotal - lastTxTotal);
            }
            lastRxTotal = rxTotal;
            lastTxTotal = txTotal;
        }
        lastConnection = connection;

        if (connection == null && wireBytes == 0 && chunkCount == 0 && taskQueuePeak == 0) {
            return;   // not connected, nothing moved — keep stderr quiet
        }

        System.err.println("[NET] rx=" + rx + "/s tx=" + tx + "/s"
                + " wire=" + (wireBytes / 1024) + "KB inflated=" + (inflatedBytes / 1024) + "KB"
                + " inflate=" + (inflateNs / 1000) + "us/" + inflatePackets + "pk"
                + " chunkApply=" + (chunkApplyNs / 1000) + "us/" + chunkCount + "ch"
                + " taskQpeak=" + taskQueuePeak
                + " drain=" + (drainNs / 1000) + "us/max=" + (drainMaxNs / 1000) + "us"
                + " frameMax=" + (frameMaxNs / 1000) + "us");
        System.err.flush();

        wireBytes = 0; inflatedBytes = 0; inflateNs = 0; inflatePackets = 0;
        chunkApplyNs = 0; chunkCount = 0; taskQueuePeak = 0;
        drainNs = 0; drainMaxNs = 0; frameMaxNs = 0;
    }
}
