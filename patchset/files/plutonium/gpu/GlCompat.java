package plutonium.gpu;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Drop-in replacements for the legacy ARB_shader_objects / ARB_vertex_shader /
 * EXT_framebuffer_object extension entry points OptiFine's shader code calls. Those
 * extensions are NOT exposed on a core forward-compatible context (this branch creates
 * core 3.3), so calling e.g. {@code ARBShaderObjects.glCreateProgramObjectARB} aborts the
 * JVM ("function not available in the current context"). Each method here delegates to the
 * equivalent core {@link GL20}/{@link GL30} call. OptiFine's call sites are retargeted from
 * the ARB and EXT extension classes to this one (same method names and signatures, so it's a
 * pure qualifier swap). 1:many ARB calls (delete/get-param/get-log used one entry point for both shader and
 * program objects) are disambiguated here via {@code glIsProgram}.
 */
public class GlCompat {

    // --- constants (ARB values equal their core counterparts numerically) ---
    public static final int GL_VERTEX_SHADER_ARB = GL20.GL_VERTEX_SHADER;
    public static final int GL_FRAGMENT_SHADER_ARB = GL20.GL_FRAGMENT_SHADER;
    public static final int GL_OBJECT_INFO_LOG_LENGTH_ARB = GL20.GL_INFO_LOG_LENGTH;

    // --- program / shader objects ---
    public static int glCreateProgramObjectARB() { return GL20.glCreateProgram(); }
    public static int glCreateShaderObjectARB(int type) { return GL20.glCreateShader(type); }
    public static void glShaderSourceARB(int sh, CharSequence src) { GL20.glShaderSource(sh, src); }
    public static void glCompileShaderARB(int sh) { GL20.glCompileShader(sh); }
    public static void glAttachObjectARB(int prog, int sh) { GL20.glAttachShader(prog, sh); }
    public static void glDetachObjectARB(int prog, int sh) { GL20.glDetachShader(prog, sh); }
    public static void glLinkProgramARB(int prog) { GL20.glLinkProgram(prog); }
    public static void glUseProgramObjectARB(int prog) { GL20.glUseProgram(prog); }
    public static void glValidateProgramARB(int prog) { GL20.glValidateProgram(prog); }

    public static void glDeleteObjectARB(int obj) {
        if (GL20.glIsProgram(obj)) GL20.glDeleteProgram(obj);
        else GL20.glDeleteShader(obj);
    }

    public static void glGetObjectParameterivARB(int obj, int pname, IntBuffer params) {
        if (GL20.glIsProgram(obj)) GL20.glGetProgramiv(obj, pname, params);
        else GL20.glGetShaderiv(obj, pname, params);
    }

    public static void glGetInfoLogARB(int obj, IntBuffer length, ByteBuffer infoLog) {
        if (GL20.glIsProgram(obj)) GL20.glGetProgramInfoLog(obj, length, infoLog);
        else GL20.glGetShaderInfoLog(obj, length, infoLog);
    }

    public static int glGetUniformLocationARB(int prog, CharSequence name) {
        return GL20.glGetUniformLocation(prog, name);
    }

    public static void glBindAttribLocationARB(int prog, int index, CharSequence name) {
        GL20.glBindAttribLocation(prog, index, name);
    }

    // --- uniforms ---
    public static void glUniform1iARB(int l, int a) { GL20.glUniform1i(l, a); }
    public static void glUniform2iARB(int l, int a, int b) { GL20.glUniform2i(l, a, b); }
    public static void glUniform4iARB(int l, int a, int b, int c, int d) { GL20.glUniform4i(l, a, b, c, d); }
    public static void glUniform1fARB(int l, float a) { GL20.glUniform1f(l, a); }
    public static void glUniform2fARB(int l, float a, float b) { GL20.glUniform2f(l, a, b); }
    public static void glUniform3fARB(int l, float a, float b, float c) { GL20.glUniform3f(l, a, b, c); }
    public static void glUniform4fARB(int l, float a, float b, float c, float d) { GL20.glUniform4f(l, a, b, c, d); }
    public static void glUniformMatrix4fvARB(int l, boolean transpose, FloatBuffer m) { GL20.glUniformMatrix4fv(l, transpose, m); }

    // --- geometry shader I/O params: no core equivalent (declared via in-shader layout); no-op.
    //     BSL has no geometry shaders, so this path never executes for it anyway. ---
    public static void glProgramParameteriARB(int prog, int pname, int value) { /* core uses in-shader layout */ }

    // --- framebuffers (EXT -> core GL30) ---
    public static int glGenFramebuffersEXT() { return GL30.glGenFramebuffers(); }
    public static void glDeleteFramebuffersEXT(int fb) { GL30.glDeleteFramebuffers(fb); }
    public static void glBindFramebufferEXT(int target, int fb) { GL30.glBindFramebuffer(target, fb); }
    public static void glFramebufferTexture2DEXT(int target, int attachment, int textarget, int texture, int level) {
        GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
    }
    public static int glCheckFramebufferStatusEXT(int target) { return GL30.glCheckFramebufferStatus(target); }
}
