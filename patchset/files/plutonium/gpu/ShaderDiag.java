package plutonium.gpu;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

/**
 * M1 harness: translate every BSL overworld program through {@link GlslTranslator} and compile it
 * on the live core 3.3 context, reporting per-program PASS/FAIL + first errors. Resolves #includes
 * itself (the GLSL preprocessor then handles BSL's #if/#define from lib/settings.glsl), so this
 * tests subsystem A faithfully without booting OptiFine's pipeline. Run once from
 * {@link CoreShaders#init()} (GL thread, context current). Temporary — remove once the translator
 * is wired into Shaders.createVert/FragShader and proven.
 */
public class ShaderDiag {

    private static final String PACK = "jars/shaderpacks/BSL_v10.0.zip";
    private static final String DIM = "shaders/world0/";

    public static void run() {
        System.err.println("\n========== [SHADER-DIAG] BSL translate+compile probe ==========");
        System.err.println("[SHADER-DIAG] GL_VERSION  = " + GL11.glGetString(GL11.GL_VERSION));
        System.err.println("[SHADER-DIAG] GLSL_VERSION= " + GL11.glGetString(GL20.GL_SHADING_LANGUAGE_VERSION));

        Map<String, String> files = new HashMap<String, String>();
        try {
            ZipFile zip = new ZipFile(PACK);
            java.util.Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                files.put(e.getName(), readAll(zip.getInputStream(e)));
            }
            zip.close();
        } catch (Exception ex) {
            System.err.println("[SHADER-DIAG] FAILED to read pack: " + ex);
            return;
        }

        int pass = 0, fail = 0;
        for (String name : new java.util.TreeSet<String>(files.keySet())) {
            if (!name.startsWith(DIM)) continue;
            boolean vsh = name.endsWith(".vsh");
            boolean fsh = name.endsWith(".fsh");
            if (!vsh && !fsh) continue;
            if (shortName(name).startsWith("dh_")) continue;   // Distant Horizons programs; never loaded on 1.8.9

            String src;
            try {
                src = resolveIncludes(files.get(name), name, files, 0);
                src = injectMacros(src);   // replicate OptiFine's ShaderMacros (real path does this pre-compile)
                src = GlslTranslator.translate(src, vsh);
            } catch (Exception ex) {
                System.err.println("[SHADER-DIAG] FAIL : " + shortName(name) + "  (preprocess: " + ex + ")");
                fail++;
                continue;
            }

            int sh = GL20.glCreateShader(vsh ? GL20.GL_VERTEX_SHADER : GL20.GL_FRAGMENT_SHADER);
            GL20.glShaderSource(sh, src);
            GL20.glCompileShader(sh);
            boolean ok = GL20.glGetShaderi(sh, GL20.GL_COMPILE_STATUS) != GL11.GL_FALSE;
            String log = GL20.glGetShaderInfoLog(sh);
            GL20.glDeleteShader(sh);

            if (ok) {
                pass++;
                System.err.println("[SHADER-DIAG] PASS : " + shortName(name));
            } else {
                fail++;
                System.err.println("[SHADER-DIAG] FAIL : " + shortName(name));
                if (log != null) {
                    String[] lines = log.split("\n");
                    for (int i = 0; i < Math.min(3, lines.length); i++) {
                        if (lines[i].trim().length() > 0) System.err.println("[SHADER-DIAG]      " + lines[i]);
                    }
                }
            }
        }
        System.err.println("[SHADER-DIAG] world0 result: " + pass + " PASS, " + fail + " FAIL");
        System.err.println("========== [SHADER-DIAG] end probe ==========\n");
    }

    /** Insert OptiFine's standard #define macros (MC_VERSION etc.) right after #version. */
    private static String injectMacros(String src) {
        String macros =
            "#define MC_VERSION 10809\n" +
            "#define MC_GL_VERSION 410\n" +
            "#define MC_GLSL_VERSION 410\n" +
            "#define MC_OS_MAC\n" +
            "#define MC_GL_VENDOR_OTHER\n" +
            "#define MC_GL_RENDERER_OTHER\n" +
            "#define MC_RENDER_QUALITY 1.0\n" +
            "#define MC_SHADOW_QUALITY 1.0\n" +
            "#define MC_HAND_DEPTH 0.125\n" +
            "#define MC_NORMAL_MAP\n" +
            "#define MC_SPECULAR_MAP\n";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^[ \\t]*#version[^\\n]*\\n").matcher(src);
        if (m.find()) return src.substring(0, m.end()) + macros + src.substring(m.end());
        return macros + src;
    }

    /** Recursively expand #include directives. Absolute ("/x") resolve from shaders root; relative from the file's dir. */
    private static String resolveIncludes(String src, String path, Map<String, String> files, int depth) {
        if (depth > 64) throw new RuntimeException("include depth exceeded at " + path);
        StringBuilder out = new StringBuilder(src.length());
        for (String line : src.split("\n", -1)) {
            String t = line.trim();
            if (t.startsWith("#include")) {
                int q1 = line.indexOf('"'), q2 = line.lastIndexOf('"');
                if (q1 >= 0 && q2 > q1) {
                    String inc = line.substring(q1 + 1, q2);
                    String resolved = inc.startsWith("/") ? "shaders" + inc : dirOf(path) + "/" + inc;
                    String content = files.get(resolved);
                    if (content == null) throw new RuntimeException("missing include " + resolved + " (from " + path + ")");
                    out.append(resolveIncludes(content, resolved, files, depth + 1)).append('\n');
                    continue;
                }
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    private static String shortName(String name) {
        return name.substring(DIM.length());
    }

    private static String readAll(InputStream is) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        is.close();
        return new String(bos.toByteArray(), "UTF-8");
    }
}
