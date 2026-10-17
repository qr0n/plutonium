package plutonium.module.modules;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;
import plutonium.module.Category;
import plutonium.module.Module;
import plutonium.setting.BoolSetting;
import plutonium.setting.SliderSetting;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class Translator extends Module {

    public static Translator instance;

    private static final String[] LANG_CODES = {
        "en", "es", "fr", "de", "pt", "ru", "zh", "ja", "ko", "ar", "hi", "it", "nl", "pl", "tr"
    };
    private static final String[] LANG_NAMES = {
        "English", "Spanish", "French", "German", "Portuguese", "Russian",
        "Chinese", "Japanese", "Korean", "Arabic", "Hindi", "Italian", "Dutch", "Polish", "Turkish"
    };

    private final SliderSetting language = reg(new SliderSetting(
        "Language", "Target language index (0=EN, 1=ES, 2=FR, 3=DE, 4=PT, 5=RU, 6=ZH, 7=JA, 8=KO, 9=AR, 10=HI, 11=IT, 12=NL, 13=PL, 14=TR)",
        0f, 0f, 14f, 1f
    ));

    private final BoolSetting showOriginal = reg(new BoolSetting(
        "Show Original", "Show the original message above the translation", true
    ));

    private final BoolSetting onlyIncoming = reg(new BoolSetting(
        "Only Incoming", "Only translate messages from other players, not your own", true
    ));

    // Daemon threads so an in-flight (or idle pooled) translation request can never keep the
    // JVM alive at shutdown. The default thread factory creates non-daemon threads.
    private final ExecutorService executor = Executors.newFixedThreadPool(2, new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger(1);
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Translator-" + n.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    public Translator() {
        super("Translator", Category.MISC);
        instance = this;
    }

    public String getTargetLangCode() {
        return LANG_CODES[Math.round(language.getValue())];
    }

    public String getTargetLangName() {
        return LANG_NAMES[Math.round(language.getValue())];
    }

    /**
     * Called from GuiNewChat.setChatLine before the message is added to chat.
     * Returns null if no translation should happen; otherwise translates async.
     */
    private static final String TRANSLATED_PREFIX = "[T]";

    public static boolean shouldTranslate(String unformatted) {
        if (instance == null || !instance.isEnabled()) return false;
        if (unformatted == null || unformatted.isEmpty()) return false;
        if (unformatted.length() < 3) return false;
        // Skip our own translated output to avoid infinite loop
        if (unformatted.contains(TRANSLATED_PREFIX)) return false;
        return true;
    }

    /**
     * Submits an async translation request. The translated text is appended to chat
     * as a new line when the result arrives.
     */
    public static void translateAsync(final IChatComponent original) {
        if (instance == null) return;
        final String text = original.getUnformattedText();
        final String targetLang = instance.getTargetLangCode();
        final boolean showOrig = instance.showOriginal.getValue();
        final String langName = instance.getTargetLangName();

        if (instance.onlyIncoming.getValue()) {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.thePlayer != null) {
                String playerName = mc.thePlayer.getName();
                if (text.contains("<" + playerName + ">") || text.startsWith(playerName)) {
                    return;
                }
            }
        }

        instance.executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    String translated = doTranslate(text, targetLang);
                    if (translated != null && !translated.isEmpty() && !translated.equals(text)) {
                        final String result = translated;
                        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                            @Override
                            public void run() {
                                Minecraft mc = Minecraft.getMinecraft();
                                if (mc.ingameGUI == null) return;
                                String msg;
                                if (showOrig) {
                                    msg = "§7[§b" + langName + " " + TRANSLATED_PREFIX + "§7] §f" + result;
                                } else {
                                    msg = "§7[§b" + TRANSLATED_PREFIX + "§7] §f" + result;
                                }
                                mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(msg));
                            }
                        });
                    }
                } catch (Exception ignored) {
                }
            }
        });
    }

    /**
     * Scans an outgoing message for a ">xx" language tag anywhere in it.
     * Examples:
     *   ">es hello"             → sends "hola"
     *   "/msg player >es hello" → sends "/msg player hola"
     *   "/r >fr how are you"    → sends "/r comment allez-vous"
     * Returns true if a tag was found and the message is being handled async.
     */
    public static boolean handleOutgoing(final String message) {
        if (instance == null || !instance.isEnabled()) return false;

        // Discoverability: ">?" / ">help" / ">list" / ">langs" / ">codes" prints the
        // available language codes instead of being sent to chat.
        String trimmed = message.trim();
        if (trimmed.equals(">?") || trimmed.equalsIgnoreCase(">help")
                || trimmed.equalsIgnoreCase(">list") || trimmed.equalsIgnoreCase(">langs")
                || trimmed.equalsIgnoreCase(">codes")) {
            showLangList();
            return true;
        }

        int tagStart = message.indexOf('>');
        if (tagStart < 0 || tagStart + 3 >= message.length()) return false;

        // Extract the code after '>' — must be 2-3 lowercase letters followed by a space
        int codeEnd = tagStart + 1;
        while (codeEnd < message.length() && codeEnd < tagStart + 4 && Character.isLetter(message.charAt(codeEnd))) {
            codeEnd++;
        }
        if (codeEnd == tagStart + 1 || codeEnd >= message.length() || message.charAt(codeEnd) != ' ') return false;

        final String code = message.substring(tagStart + 1, codeEnd).toLowerCase();
        boolean valid = false;
        String langName = code;
        for (int i = 0; i < LANG_CODES.length; i++) {
            if (LANG_CODES[i].equals(code)) {
                valid = true;
                langName = LANG_NAMES[i];
                break;
            }
        }
        if (!valid) return false;

        final String prefix = message.substring(0, tagStart).trim();
        final String textToTranslate = message.substring(codeEnd + 1).trim();
        if (textToTranslate.isEmpty()) return false;

        final String displayLang = langName;
        instance.executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    final String translated = doTranslate(textToTranslate, code);
                    if (translated == null || translated.isEmpty()) {
                        showLocalMessage("§c[Translator] Translation failed.");
                        return;
                    }
                    Minecraft.getMinecraft().addScheduledTask(new Runnable() {
                        @Override
                        public void run() {
                            Minecraft mc = Minecraft.getMinecraft();
                            if (mc.thePlayer == null) return;
                            String toSend = prefix.isEmpty() ? translated : prefix + " " + translated;
                            mc.thePlayer.sendChatMessage(toSend);
                            showLocalMessage("§7[§b" + displayLang + " " + TRANSLATED_PREFIX + "§7] §7You: §f" + toSend);
                        }
                    });
                } catch (Exception e) {
                    showLocalMessage("§c[Translator] Translation failed.");
                }
            }
        });
        return true;
    }

    @Override
    public void onEnable() {
        // Only surface the hint in-world, not at the main menu / on config restore.
        if (Minecraft.getMinecraft().thePlayer != null) {
            showLocalMessage("§b[Translator] §7Enabled. Translate & send with §f>es <message>§7 "
                + "(any code). Type §f>?§7 to list codes.");
        }
    }

    /** Prints usage + all supported language codes to local chat. */
    private static void showLangList() {
        showLocalMessage("§b[Translator] §7Usage: §f>" + LANG_CODES[1] + " <message>§7 — source is auto-detected. "
            + "Current default target: §f" + (instance != null ? instance.getTargetLangName() : "English"));
        StringBuilder sb = new StringBuilder("§b[Translator] §7Codes: ");
        for (int i = 0; i < LANG_CODES.length; i++) {
            sb.append("§f").append(LANG_CODES[i]).append("§8=").append(LANG_NAMES[i]).append("§7  ");
        }
        showLocalMessage(sb.toString());
    }

    private static void showLocalMessage(final String msg) {
        Minecraft.getMinecraft().addScheduledTask(new Runnable() {
            @Override
            public void run() {
                Minecraft mc = Minecraft.getMinecraft();
                if (mc.ingameGUI != null) {
                    mc.ingameGUI.getChatGUI().printChatMessage(new ChatComponentText(msg));
                }
            }
        });
    }

    private static String doTranslate(String text, String targetLang) {
        HttpURLConnection conn = null;
        try {
            String encoded = URLEncoder.encode(text, "UTF-8");
            String urlStr = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                + targetLang + "&dt=t&q=" + encoded;
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            int code = conn.getResponseCode();
            if (code != 200) return null;

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();

            return parseGoogleResponse(sb.toString());
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // Response format: [[["translated","original","",""],...],null,"detected_lang"]
    // Each translation segment is at [0][i][0]; concatenate them.
    private static String parseGoogleResponse(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            JsonArray segments = new JsonParser().parse(json).getAsJsonArray().get(0).getAsJsonArray();
            StringBuilder result = new StringBuilder();
            for (JsonElement seg : segments) {
                JsonElement text = seg.getAsJsonArray().get(0);
                if (!text.isJsonNull()) result.append(text.getAsString());
            }
            return result.length() > 0 ? result.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
