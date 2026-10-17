package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;
import plutonium.setting.SliderSetting;

/**
 * During a connection stall other players normally freeze for the duration and then
 * snap to their real position. This keeps them moving along their last known velocity
 * instead (client-side dead reckoning in EntityOtherPlayerMP); when real data resumes,
 * the vanilla 3-tick lerp corrects from the extrapolated position. Visual only —
 * nothing the server sees changes.
 */
public class LagExtrapolation extends Module {

    public static LagExtrapolation instance;

    private final SliderSetting maxTime  = reg(new SliderSetting("Max Time", "Longest stall (ms) to extrapolate through", 500f, 100f, 2000f, 50f));
    private final BoolSetting   vertical = reg(new BoolSetting("Vertical", "Also extrapolate vertical motion", false));

    public LagExtrapolation() {
        super("Lag Extrapolation", Category.COMBAT);
        instance = this;
    }

    public static boolean isActive() {
        return instance != null && instance.isEnabled();
    }

    public static long maxTimeMs() {
        return instance == null ? 0L : (long) instance.maxTime.getValue().floatValue();
    }

    public static boolean extrapolateVertical() {
        return instance != null && instance.vertical.getValue();
    }
}
