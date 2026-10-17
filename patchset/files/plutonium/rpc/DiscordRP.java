package plutonium.rpc;

import club.minnced.discord.rpc.DiscordEventHandlers;
import club.minnced.discord.rpc.DiscordRPC;
import club.minnced.discord.rpc.DiscordRichPresence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.server.integrated.IntegratedServer;

public class DiscordRP {
    // Resolved in start(): touching DiscordRPC.INSTANCE loads the native library.
    private static DiscordRPC lib;
    private static final String CLIENT_ID = "1508727006089576549";

    private static boolean initialized = false;
    private static Thread callbackThread;
    private static long activityTimestamp = System.currentTimeMillis() / 1000L;
    private static GameActivity lastActivity = null;

    private enum GameActivity {
        MAIN_MENU, MULTIPLAYER_MENU, SINGLEPLAYER, MULTIPLAYER
    }

    public static void start() {
        if (initialized) return;

        try {
            // Both lines load native code (JNA, then the discord-rpc library), so both sit
            // inside the guard.
            DiscordEventHandlers handlers = new DiscordEventHandlers();
            handlers.ready = (user) -> System.out.println("[Plutonium] Discord RPC connected as " + user.username);
            lib = DiscordRPC.INSTANCE;
            lib.Discord_Initialize(CLIENT_ID, handlers, true, "");
        } catch (LinkageError e) {
            // The stock java-discord-rpc jar ships no arm64 macOS natives (JNA or discord-rpc).
            System.err.println("[Plutonium] Discord RPC unavailable: " + e);
            return;
        }
        initialized = true;

        callbackThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted() && initialized) {
                lib.Discord_RunCallbacks();
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "RPC-Callback-Handler");
        callbackThread.setDaemon(true);
        callbackThread.start();

        update();
    }

    public static void update() {
        if (!initialized) return;

        Minecraft mc = Minecraft.getMinecraft();
        GameActivity current = detectActivity(mc);

        if (current != lastActivity) {
            activityTimestamp = System.currentTimeMillis() / 1000L;
            lastActivity = current;
        }

        DiscordRichPresence presence = new DiscordRichPresence();
        presence.startTimestamp = activityTimestamp;
        presence.largeImageKey = "splash_atom_transparent";
        presence.largeImageText = "Plutonium Client";

        switch (current) {
            case MAIN_MENU:
                presence.details = "In the Menus";
                presence.state = "Main Menu";
                break;
            case MULTIPLAYER_MENU:
                presence.details = "In the Menus";
                presence.state = "Server Browser";
                break;
            case SINGLEPLAYER:
                presence.details = "Playing Singleplayer";
                presence.state = getWorldName(mc);
                break;
            case MULTIPLAYER:
                ServerData server = mc.getCurrentServerData();
                presence.details = "Playing Multiplayer";
                presence.state = server != null ? server.serverIP : "Unknown Server";
                break;
        }

        String username = mc.getSession().getUsername();
        presence.smallImageKey = "splash_atom_transparent";
        presence.smallImageText = username;

        lib.Discord_UpdatePresence(presence);
    }

    public static void shutdown() {
        if (initialized) {
            initialized = false;
            lastActivity = null;
            if (callbackThread != null) {
                callbackThread.interrupt();
            }
            lib.Discord_Shutdown();
        }
    }

    public static boolean isInitialized() {
        return initialized;
    }

    private static GameActivity detectActivity(Minecraft mc) {
        if (mc.theWorld == null) {
            if (mc.currentScreen instanceof GuiMultiplayer) {
                return GameActivity.MULTIPLAYER_MENU;
            }
            return GameActivity.MAIN_MENU;
        }

        if (mc.isSingleplayer()) {
            return GameActivity.SINGLEPLAYER;
        }

        return GameActivity.MULTIPLAYER;
    }

    private static String getWorldName(Minecraft mc) {
        if (mc.getIntegratedServer() != null) {
            String name = mc.getIntegratedServer().getWorldName();
            if (name != null && !name.isEmpty()) return name;
        }
        return "Singleplayer World";
    }
}
