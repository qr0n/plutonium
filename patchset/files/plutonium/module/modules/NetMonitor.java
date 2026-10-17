package plutonium.module.modules;

import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.NetworkManager;
import plutonium.module.Category;
import plutonium.module.TextHudModule;
import plutonium.setting.BoolSetting;
import plutonium.setting.SliderSetting;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Connection-health chip: packet rates plus the gap since the last packet from the
 * server. Gaps above the stall threshold turn the line red while ongoing and are
 * appended to logs/net_stalls.log once they end, for correlating lag with fights.
 * Note a gap is only measured when the packet that ends it arrives, so a dead
 * connection shows a growing live gap but never logs.
 */
public class NetMonitor extends TextHudModule {

    private final SliderSetting stallMs   = reg(new SliderSetting("Stall Threshold", "Gap (ms) counted as a stall", 250f, 100f, 1000f, 50f));
    private final BoolSetting   logStalls = reg(new BoolSetting("Log Stalls", "Append stalls to logs/net_stalls.log", true));

    // Per-second peak-gap buckets covering the last WINDOW seconds
    private static final int WINDOW = 10;
    private final long[] gapBuckets = new long[WINDOW];
    private int bucketIdx;
    private long lastRollMs;

    private long lastRxCount = -1, lastTxCount = -1;
    private int rxRate = -1, txRate = -1;

    private long liveGap, peakGap;

    public NetMonitor() {
        super("Net Monitor", Category.VISUAL);
        initEnabled(true);
    }

    @Override
    public void onTick() {
        NetworkManager nm = currentConnection();
        if (nm == null) {
            resetStats();
            return;
        }

        long now = System.currentTimeMillis();
        if (lastRollMs == 0) lastRollMs = now;
        long elapsed = now - lastRollMs;
        if (elapsed < 1000) return;

        // Roll a one-second bucket: take (and clear) the peak gap the Netty thread recorded
        long peak = nm.maxInboundGapMs;
        nm.maxInboundGapMs = 0;
        bucketIdx = (bucketIdx + 1) % WINDOW;
        gapBuckets[bucketIdx] = peak;
        if (logStalls.getValue() && peak >= stallMs.getValue()) logStall(peak);

        long rx = nm.inboundPacketCount.get();
        long tx = nm.outboundPacketCount.get();
        if (lastRxCount >= 0) {
            rxRate = (int) ((rx - lastRxCount) * 1000 / elapsed);
            txRate = (int) ((tx - lastTxCount) * 1000 / elapsed);
        }
        lastRxCount = rx;
        lastTxCount = tx;
        lastRollMs = now;
    }

    @Override
    protected String[] getHudLines() {
        NetworkManager nm = currentConnection();
        if (nm == null || nm.isLocalChannel()) return null;

        long last = nm.lastInboundTimeMs;
        liveGap = last == 0 ? 0 : Math.max(0, System.currentTimeMillis() - last);
        peakGap = Math.max(nm.maxInboundGapMs, liveGap);
        for (int i = 0; i < WINDOW; i++) {
            if (gapBuckets[i] > peakGap) peakGap = gapBuckets[i];
        }

        String rates = (rxRate < 0 ? "RX --/s" : "RX " + rxRate + "/s")
                     + (txRate < 0 ? "  TX --/s" : "  TX " + txRate + "/s");
        return new String[]{rates, "Gap " + liveGap + "ms  Peak " + peakGap + "ms"};
    }

    @Override
    protected int getLineColor(int index) {
        if (index == 1) {
            if (liveGap >= stallMs.getValue()) return 0xFFFF5555;
            if (peakGap >= stallMs.getValue()) return 0xFFFFFF55;
        }
        return super.getLineColor(index);
    }

    @Override protected int defaultHudX(int sw, int sh) { return sw - getHudWidthScaled() - 2; }
    @Override protected int defaultHudY(int sw, int sh) { return 40; }

    private NetworkManager currentConnection() {
        return mc.getNetHandler() != null ? mc.getNetHandler().getNetworkManager() : null;
    }

    private void resetStats() {
        for (int i = 0; i < WINDOW; i++) gapBuckets[i] = 0;
        lastRxCount = lastTxCount = -1;
        rxRate = txRate = -1;
        lastRollMs = 0;
        liveGap = peakGap = 0;
    }

    private void logStall(long ms) {
        try {
            File f = new File(mc.mcDataDir, "logs/net_stalls.log");
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            ServerData sd = mc.getCurrentServerData();
            FileWriter w = new FileWriter(f, true);
            try {
                w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date())
                        + " stall=" + ms + "ms server=" + (sd != null ? sd.serverIP : "?") + "\n");
            } finally {
                w.close();
            }
        } catch (Exception ignored) {}
    }
}
