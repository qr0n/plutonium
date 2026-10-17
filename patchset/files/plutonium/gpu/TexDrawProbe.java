package plutonium.gpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * TEMPORARY diagnostic (enable with -Dplutonium.tracetex=true): aggregates the call
 * sites of world-phase position_tex immediate draws (the unexplained immW tex spike,
 * 14 -> 122 draws/frame during /kp2 combat) and prints the top signatures once per
 * second. Stack capture is expensive — never enable for a benchmark run.
 */
public final class TexDrawProbe {

    public static final boolean ENABLED = Boolean.getBoolean("plutonium.tracetex");

    private static final HashMap<String, Integer> COUNTS = new HashMap<String, Integer>();
    private static long lastDump = 0;

    private TexDrawProbe() {}

    public static void hit() {
        if (!ENABLED) return;
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder(160);
        for (int i = 2; i < Math.min(st.length, 10); i++) {
            String c = st[i].getClassName();
            sb.append(c.substring(c.lastIndexOf('.') + 1)).append('.')
              .append(st[i].getMethodName()).append(" < ");
        }
        String sig = sb.toString();
        Integer prev = COUNTS.get(sig);
        COUNTS.put(sig, prev == null ? 1 : prev + 1);

        long now = System.currentTimeMillis();
        if (now - lastDump >= 1000) {
            lastDump = now;
            ArrayList<Map.Entry<String, Integer>> es =
                    new ArrayList<Map.Entry<String, Integer>>(COUNTS.entrySet());
            es.sort((a, b) -> b.getValue() - a.getValue());
            System.err.println("[TEX-PROBE] --- immW_tex call sites this second ---");
            for (int i = 0; i < Math.min(8, es.size()); i++) {
                System.err.println("[TEX-PROBE] " + es.get(i).getValue() + "x  " + es.get(i).getKey());
            }
            System.err.flush();
            COUNTS.clear();
        }
    }
}
