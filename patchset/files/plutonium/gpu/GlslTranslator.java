package plutonium.gpu;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Translates compatibility-profile OptiFine shaderpack GLSL (the dialect BSL and friends are
 * written in: gl_Vertex, gl_ModelViewProjectionMatrix, ftransform, gl_FragData, attribute/
 * varying, texture2D) into core-profile GLSL (#version 330 core) that this branch's
 * forward-compatible core 3.3 context will accept.
 *
 * Hand-written (plain Java 8) but informed by Iris's transformer rules
 * (net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer / VanillaTransformer):
 * the gl_* -> iris_* rename conventions, the gl_FragColor -> gl_FragData[0] unification, the
 * shadow2D -> vec4(texture(...)) wrap, the texture-function family, and the `texture` sampler ->
 * `gtexture` collision rename. We reimplement the rules (no dependency): glsl-transformer is
 * Java 16+/AGPL and Iris's pipeline is welded to modern Minecraft.
 *
 * Runs on the post-#include assembled source (the StringBuilder in Shaders.createVert/FragShader),
 * never on raw files. The matching runtime attribute/uniform feeding lives in subsystems B/C.
 */
public class GlslTranslator {

    public static String translate(String src, boolean vertex) {
        // 1. Version: force core. Must remain the first directive.
        src = src.replaceFirst("(?m)^[ \\t]*#version[ \\t]+\\d+.*$", "#version 330 core");
        // If the pack had no #version line, prepend one.
        if (!src.contains("#version")) {
            src = "#version 330 core\n" + src;
        }

        // 2. Strip extensions that are core (or unsupported) under 330.
        src = src.replaceAll("(?m)^[ \\t]*#extension[ \\t]+GL_ARB_shader_texture_lod.*$", "");
        src = src.replaceAll("(?m)^[ \\t]*#extension[ \\t]+GL_ARB_shader_image_load_store.*$", "");

        // 3. Sampler-name collision: the legacy `texture` sampler clashes with the core built-in
        //    function `texture()`. Rename the sampler (and its uses) to `gtexture` BEFORE the
        //    texture2D->texture rename creates any `texture(` calls. `\btexture\b` does not match
        //    inside `texture2D` (no word boundary before the digit), so only the sampler is hit.
        src = src.replaceAll("\\btexture\\b", "gtexture");

        // 4. Texture functions -> core equivalents. shadow2D returns vec4 in legacy but float in
        //    core, and BSL indexes the result (.x/.z), so wrap: shadow2D(a,b) -> vec4(texture(a,b)).
        src = wrapCall(src, "shadow2DLod", "textureLod");
        src = wrapCall(src, "shadow2D", "texture");
        src = src.replaceAll("\\btexture2DGradARB\\b", "textureGrad");
        src = src.replaceAll("\\btexture2DGrad\\b", "textureGrad");
        src = src.replaceAll("\\btexture3DGrad\\b", "textureGrad");
        src = src.replaceAll("\\btexture2DLod\\b", "textureLod");
        src = src.replaceAll("\\btexture3DLod\\b", "textureLod");
        src = src.replaceAll("\\btexture2DProj\\b", "textureProj");
        src = src.replaceAll("\\btexture3DProj\\b", "textureProj");
        src = src.replaceAll("\\btexelFetch2D\\b", "texelFetch");
        src = src.replaceAll("\\btexelFetch3D\\b", "texelFetch");
        src = src.replaceAll("\\btextureSize2D\\b", "textureSize");
        src = src.replaceAll("\\btexture2D\\b", "texture");
        src = src.replaceAll("\\btexture3D\\b", "texture");

        // 5. Storage qualifiers.
        src = src.replaceAll("\\battribute\\b", "in");
        src = src.replaceAll("\\bvarying\\b", vertex ? "out" : "in");

        // 6. Matrix built-ins -> uniforms (both stages; deferred frag shaders read them too).
        //    Longest names first so inverses/MVP aren't clobbered by the shorter ones.
        src = src.replaceAll("\\bgl_ModelViewProjectionMatrix\\b", "(iris_ProjMat * iris_ModelViewMat)");
        src = src.replaceAll("\\bgl_ModelViewMatrixInverse\\b", "iris_ModelViewMatInverse");
        src = src.replaceAll("\\bgl_ProjectionMatrixInverse\\b", "iris_ProjMatInverse");
        src = src.replaceAll("\\bgl_NormalMatrix\\b", "iris_NormalMat");
        src = src.replaceAll("\\bgl_ModelViewMatrix\\b", "iris_ModelViewMat");
        src = src.replaceAll("\\bgl_ProjectionMatrix\\b", "iris_ProjMat");
        src = src.replace("gl_TextureMatrix[0]", "iris_TextureMat");
        src = src.replace("gl_TextureMatrix[1]", "iris_LightmapTextureMat");

        // 7. Vertex built-in attributes (vertex stage only).
        if (vertex) {
            src = src.replaceAll("\\bgl_Vertex\\b", "vec4(iris_Position, 1.0)");
            src = src.replaceAll("\\bgl_Normal\\b", "iris_Normal");
            src = src.replaceAll("\\bgl_Color\\b", "iris_Color");
            src = src.replaceAll("\\bgl_MultiTexCoord0\\b", "vec4(iris_UV0, 0.0, 1.0)");
            src = src.replaceAll("\\bgl_MultiTexCoord1\\b", "vec4(iris_UV1, 0.0, 1.0)");
            // ftransform -> renamed fn (defined in prelude).
            src = src.replaceAll("\\bftransform\\b", "iris_ftransform");
        }

        // 8. Fragment outputs.
        Set<Integer> fragDataIdx = new LinkedHashSet<Integer>();
        if (!vertex) {
            src = src.replaceAll("\\bgl_FragColor\\b", "gl_FragData[0]");
            Matcher m = Pattern.compile("gl_FragData\\[(\\d+)\\]").matcher(src);
            StringBuffer sb = new StringBuffer();
            while (m.find()) {
                int idx = Integer.parseInt(m.group(1));
                fragDataIdx.add(idx);
                m.appendReplacement(sb, "iris_FragData" + idx);
            }
            m.appendTail(sb);
            src = sb.toString();
        }

        // 9. Build the prelude (declare only what the translated source actually references),
        //    inserted right after the #version line.
        StringBuilder pre = new StringBuilder();
        boolean usesFtransform = vertex && uses(src, "iris_ftransform");
        if (vertex) {
            if (uses(src, "iris_Position") || usesFtransform) pre.append("in vec3 iris_Position;\n");
            if (uses(src, "iris_Normal"))   pre.append("in vec3 iris_Normal;\n");
            if (uses(src, "iris_Color"))    pre.append("in vec4 iris_Color;\n");
            if (uses(src, "iris_UV0"))      pre.append("in vec2 iris_UV0;\n");
            if (uses(src, "iris_UV1"))      pre.append("in vec2 iris_UV1;\n");
        }
        if (uses(src, "iris_ModelViewMat") || usesFtransform) pre.append("uniform mat4 iris_ModelViewMat;\n");
        if (uses(src, "iris_ModelViewMatInverse"))            pre.append("uniform mat4 iris_ModelViewMatInverse;\n");
        if (uses(src, "iris_ProjMat") || usesFtransform)      pre.append("uniform mat4 iris_ProjMat;\n");
        if (uses(src, "iris_ProjMatInverse"))                 pre.append("uniform mat4 iris_ProjMatInverse;\n");
        if (uses(src, "iris_NormalMat"))                      pre.append("uniform mat3 iris_NormalMat;\n");
        if (uses(src, "iris_TextureMat"))                     pre.append("uniform mat4 iris_TextureMat;\n");
        if (uses(src, "iris_LightmapTextureMat"))             pre.append("uniform mat4 iris_LightmapTextureMat;\n");
        for (Integer idx : fragDataIdx) {
            pre.append("layout(location = ").append(idx).append(") out vec4 iris_FragData").append(idx).append(";\n");
        }
        if (usesFtransform) {
            pre.append("vec4 iris_ftransform() { return (iris_ProjMat * iris_ModelViewMat) * vec4(iris_Position, 1.0); }\n");
        }

        if (pre.length() > 0) {
            src = insertAfterVersion(src, pre.toString());
        }
        return src;
    }

    /** True if {@code token} appears as a whole identifier (so iris_ProjMat doesn't match inside iris_ProjMatInverse). */
    private static boolean uses(String src, String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(src).find();
    }

    /** Insert {@code block} on the line after the #version directive (which must stay first). */
    private static String insertAfterVersion(String src, String block) {
        Matcher m = Pattern.compile("(?m)^[ \\t]*#version[^\\n]*\\n").matcher(src);
        if (m.find()) {
            return src.substring(0, m.end()) + block + src.substring(m.end());
        }
        return block + src;
    }

    /**
     * Rewrite {@code name(args)} -> {@code vec4(newFn(args))}, scanning balanced parens so the
     * whole call (including any nested parens in args) is wrapped. Used for shadow2D/shadow2DLod
     * whose core counterparts return a float but whose results the pack treats as vec4.
     */
    private static String wrapCall(String src, String name, String newFn) {
        Pattern p = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(");
        Matcher m = p.matcher(src);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int open = m.end() - 1;        // index of '('
            int depth = 0, i = open, close = -1;
            for (; i < src.length(); i++) {
                char c = src.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') { depth--; if (depth == 0) { close = i; break; } }
            }
            if (close < 0) break;          // unbalanced; bail and leave the rest untouched
            out.append(src, last, m.start());
            out.append("vec4(").append(newFn).append('(');
            out.append(src, open + 1, close);   // original args
            out.append("))");
            last = close + 1;
            m.region(last, src.length());
        }
        out.append(src.substring(last));
        return out.toString();
    }
}
