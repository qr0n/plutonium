package plutonium;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Session;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AccountManager {

    private static final List<Account> accounts = new ArrayList<Account>();
    private static File file;
    private static String lastActive = null;

    public static void init(File gameDir) {
        file = new File(gameDir, "plutonium_accounts.json");
        migrateOldFormat(gameDir);
        load();
        if (lastActive != null) {
            System.out.println("[AccountManager] Auto-restoring last active account: " + lastActive);
            switchTo(lastActive);
        }
    }

    public static List<Account> getAccounts() {
        List<Account> sorted = new ArrayList<Account>(accounts);
        Collections.sort(sorted, new java.util.Comparator<Account>() {
            @Override
            public int compare(Account a, Account b) {
                int aVal = a.getType() == Account.Type.MICROSOFT ? 0 : 1;
                int bVal = b.getType() == Account.Type.MICROSOFT ? 0 : 1;
                return aVal - bVal;
            }
        });
        return Collections.unmodifiableList(sorted);
    }

    public static Account getByUsername(String name) {
        Account found = null;
        for (Account a : accounts) {
            if (a.getUsername().equals(name)) {
                if (a.getType() == Account.Type.MICROSOFT) return a;
                if (found == null) found = a;
            }
        }
        return found;
    }

    public static void addOffline(String name) {
        name = name.trim();
        if (name.isEmpty() || getByUsername(name) != null) return;
        accounts.add(Account.offline(name));
        save();
    }

    public static void addMicrosoft(Account account) {
        for (int i = 0; i < accounts.size(); i++) {
            Account existing = accounts.get(i);
            if (existing.getUsername().equals(account.getUsername())) {
                accounts.set(i, account);
                save();
                return;
            }
        }
        accounts.add(account);
        save();
    }

    public static void remove(String name) {
        Account a = getByUsername(name);
        if (a != null && accounts.remove(a)) save();
    }

    private static String switchStatus = null;
    private static int switchStatusColor = 0;
    private static boolean switching = false;

    public static String getSwitchStatus() { return switchStatus; }
    public static int getSwitchStatusColor() { return switchStatusColor; }
    public static boolean isSwitching() { return switching; }

    public static void switchTo(String name) {
        Account account = getByUsername(name);
        if (account == null) {
            switchToOffline(name);
        } else if (account.getType() == Account.Type.MICROSOFT) {
            switchToMicrosoftAsync(account);
        } else {
            switchToOffline(name);
        }

        lastActive = name;
        save();
    }

    private static void switchToMicrosoftAsync(final Account account) {
        if (switching) return;
        switching = true;
        switchStatus = "Refreshing token...";
        switchStatusColor = 0xFFFFFF55;

        new Thread(new Runnable() {
            @Override
            public void run() {
                if (account.getRefreshToken() != null) {
                    System.out.println("[AccountManager] Refreshing token for " + account.getUsername() + "...");
                    boolean ok = MicrosoftAuth.refreshAccount(account);
                    if (ok) {
                        save();
                        System.out.println("[AccountManager] Token refreshed successfully");
                    } else {
                        System.err.println("[AccountManager] Token refresh failed — using stored token");
                    }
                }

                Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                    @Override
                    public void run() {
                        applyMicrosoftSession(account);
                        switching = false;
                        if ("Expired".equals(account.getStatus())) {
                            switchStatus = "Token expired — re-login required";
                            switchStatusColor = 0xFFFF5555;
                        } else {
                            switchStatus = "Switched to " + account.getUsername();
                            switchStatusColor = 0xFF55FF55;
                        }
                    }
                });
            }
        }, "AccountSwitch").start();
    }

    private static void applyMicrosoftSession(Account account) {
        try {
            Minecraft.getMinecraft().setSession(new Session(account.getUsername(),
                    account.getUuid().replace("-", ""), account.getAccessToken(), "mojang"));
        } catch (Exception e) {
            System.err.println("[AccountManager] microsoft session patch failed: " + e.getMessage());
        }
        patchPlayerProfile(account.getUsername());
    }

    private static void switchToOffline(String name) {
        try {
            String offlineUuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes()).toString().replace("-", "");
            Minecraft.getMinecraft().setSession(new Session(name, offlineUuid, "0", "legacy"));
        } catch (Exception e) {
            System.err.println("[AccountManager] offline session patch failed: " + e.getMessage());
        }
        patchPlayerProfile(name);
    }

    private static void patchPlayerProfile(String name) {
        if (Minecraft.getMinecraft().thePlayer != null) {
            try {
                setField(GameProfile.class, Minecraft.getMinecraft().thePlayer.getGameProfile(), "name", name);
            } catch (Exception e) {
                System.err.println("[AccountManager] profile patch failed: " + e.getMessage());
            }
        }
    }

    private static void setField(Class<?> clazz, Object obj, String fieldName, Object value) throws Exception {
        Field f = clazz.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(obj, value);
    }

    private static String formatUuid(String raw) {
        if (raw == null || raw.contains("-")) return raw;
        return raw.replaceFirst(
                "(\\w{8})(\\w{4})(\\w{4})(\\w{4})(\\w{12})",
                "$1-$2-$3-$4-$5"
        );
    }

    public static String currentName() {
        return Minecraft.getMinecraft().getSession().getUsername();
    }

    public static void add(String name) {
        addOffline(name);
    }

    // --- Persistence ---

    private static void loadAccounts(JSONArray arr) {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject obj = arr.getJSONObject(i);
            String type = obj.optString("type", "OFFLINE");
            if ("MICROSOFT".equals(type)) {
                Account a = Account.microsoft(
                        obj.optString("email", null),
                        obj.getString("username"),
                        obj.getString("accessToken"),
                        obj.getString("uuid"),
                        obj.optString("refreshToken", null)
                );
                a.setStatus(obj.optString("status", "Valid"));
                accounts.add(a);
            } else {
                accounts.add(Account.offline(obj.getString("username")));
            }
        }
    }

    private static void load() {
        accounts.clear();
        lastActive = null;
        if (file == null || !file.exists()) return;
        try {
            String content = readFile(file);
            content = content.trim();
            if (content.startsWith("{")) {
                JSONObject root = new JSONObject(content);
                loadAccounts(root.getJSONArray("accounts"));
                lastActive = root.optString("lastActive", null);
            } else {
                loadAccounts(new JSONArray(content));
            }
        } catch (Exception e) {
            System.err.println("[AccountManager] failed to load accounts: " + e.getMessage());
        }
    }

    private static void save() {
        if (file == null) return;
        JSONArray arr = new JSONArray();
        for (Account a : accounts) {
            JSONObject obj = new JSONObject();
            obj.put("type", a.getType().name());
            obj.put("username", a.getUsername());
            if (a.getType() == Account.Type.MICROSOFT) {
                if (a.getEmail() != null) obj.put("email", a.getEmail());
                obj.put("accessToken", a.getAccessToken());
                obj.put("uuid", a.getUuid());
                if (a.getRefreshToken() != null) obj.put("refreshToken", a.getRefreshToken());
                obj.put("status", a.getStatus());
            }
            arr.put(obj);
        }
        JSONObject root = new JSONObject();
        root.put("accounts", arr);
        if (lastActive != null) root.put("lastActive", lastActive);
        try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
            w.print(root.toString(2));
        } catch (IOException e) {
            System.err.println("[AccountManager] failed to save accounts: " + e.getMessage());
        }
    }

    private static void migrateOldFormat(File gameDir) {
        File oldFile = new File(gameDir, "plutonium_accounts.txt");
        if (!oldFile.exists() || file.exists()) return;
        try (BufferedReader r = new BufferedReader(new FileReader(oldFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) accounts.add(Account.offline(line));
            }
            save();
            System.out.println("[AccountManager] migrated " + accounts.size() + " accounts from old format");
        } catch (IOException e) {
            System.err.println("[AccountManager] migration failed: " + e.getMessage());
        }
    }

    private static String readFile(File f) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
