package plutonium.module;

import plutonium.module.modules.*;
import plutonium.module.modules.Zoom;

import java.util.ArrayList;
import java.util.List;

public class ModuleManager {
    private final List<Module> modules = new ArrayList<Module>();

    public ModuleManager() {
        // --- Original modules ---
        register(new Sprint());
        register(new SneakToggle());
        register(new Fullbright());
        register(new Keystrokes());
        register(new Coordinates());
        register(new FPSCounter());
        register(new AutoRespawn());
        // --- Visual ---
        register(new Zoom());
        register(new ArmorStatus());
        register(new PotionEffects());
        register(new CpsCounter());
        register(new PingDisplay());
        register(new DirectionHUD());
        register(new Clock());
        register(new Crosshair());
        register(new MemoryUsage());
        register(new SaturationDisplay());
        register(new OldAnimations());
        register(new FOVChanger());
        register(new BlockOutline());
        register(new ChunkBorders());
        register(new MotionBlur());
        register(new NoHurtCam());
        register(new ScoreboardMod());
        register(new NameTags());
        register(new Hitbox());
        register(new ParticleMultiplier());
        register(new InventoryViewer());
        register(new DropToShiftClick());
        register(new DropFilter());
        register(new NetMonitor());
        // --- Combat ---
        register(new LagExtrapolation());
        // --- Misc ---
        register(new TimeChanger());
        register(new ComboCounter());
        register(new Stopwatch());
        register(new ReachDisplay());
        register(new DayCounter());
        register(new ChatFilter());
        register(new DiscordRPC());
        register(new Translator());
        register(new PocketPet());
        // --- Movement ---
        register(new FreeLook());
    }

    private void register(Module m) {
        modules.add(m);
    }

    public List<Module> getModules() {
        return modules;
    }

    public List<Module> getByCategory(Category cat) {
        List<Module> result = new ArrayList<Module>();
        for (Module m : modules) {
            if (m.getCategory() == cat) result.add(m);
        }
        return result;
    }

    public Module getByName(String name) {
        for (Module m : modules) {
            if (m.getName().equalsIgnoreCase(name)) return m;
        }
        return null;
    }
}
