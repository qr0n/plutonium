package plutonium.module.modules;

import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.SliderSetting;

public class TimeChanger extends Module {

    private final SliderSetting time = reg(new SliderSetting("Time", "World time (0=sunrise, 6000=noon, 18000=midnight)", 6000f, 0f, 24000f, 500f));

    public TimeChanger() {
        super("Time Changer", Category.MISC);
        initEnabled(true);
    }

    @Override
    public void onTick() {
        if (mc.theWorld == null) return;
        long targetTime = (long) time.getValue().floatValue();
        long cur = mc.theWorld.getWorldTime() % 24000L;
        if (Math.abs(cur - targetTime) > 5) {
            mc.theWorld.setWorldTime(
                (mc.theWorld.getWorldTime() / 24000L) * 24000L + targetTime
            );
        }
    }
}
