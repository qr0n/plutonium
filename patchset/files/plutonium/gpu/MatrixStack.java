package plutonium.gpu;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;
import plutonium.gpu.math.Matrix4f;
import plutonium.gpu.math.Vector3f;

public class MatrixStack {

    private static final int GL_MODELVIEW = 5888;
    private static final int GL_PROJECTION = 5889;
    private static final int GL_TEXTURE = 5890;

    private static final int MV_DEPTH = 32;
    private static final int PROJ_DEPTH = 4;
    private static final int TEX_DEPTH = 4;

    private static final Matrix4f[] modelviewStack = new Matrix4f[MV_DEPTH];
    private static final Matrix4f[] projectionStack = new Matrix4f[PROJ_DEPTH];
    private static final Matrix4f[] textureStack = new Matrix4f[TEX_DEPTH];

    private static int mvPointer = 0;
    private static int projPointer = 0;
    private static int texPointer = 0;

    private static int activeMode = GL_MODELVIEW;

    private static final Matrix4f mvpCache = new Matrix4f();
    // Bumped on every MV/PROJ mutation (incl. pop). Lets per-program uniform sync skip
    // re-uploading an unchanged MVP — conservative: false bumps re-upload, never stale-skip.
    private static long mvpVersion = 1;
    private static boolean mvpDirty = true;
    private static boolean mvDirty = true;
    private static boolean projDirty = true;
    private static boolean texDirty = true;

    private static final FloatBuffer matrixBuf = MemoryUtil.memAllocFloat(16);

    private static final Vector3f tempAxis = new Vector3f();

    static {
        for (int i = 0; i < MV_DEPTH; i++) modelviewStack[i] = new Matrix4f();
        for (int i = 0; i < PROJ_DEPTH; i++) projectionStack[i] = new Matrix4f();
        for (int i = 0; i < TEX_DEPTH; i++) textureStack[i] = new Matrix4f();
    }

    public static void setActiveStack(int mode) {
        activeMode = mode;
    }

    public static int getActiveMode() {
        return activeMode;
    }

    public static void push() {
        switch (activeMode) {
            case GL_MODELVIEW:
                if (mvPointer >= MV_DEPTH - 1) throw new RuntimeException("Modelview stack overflow");
                modelviewStack[mvPointer + 1].load(modelviewStack[mvPointer]);
                mvPointer++;
                break;
            case GL_PROJECTION:
                if (projPointer >= PROJ_DEPTH - 1) throw new RuntimeException("Projection stack overflow");
                projectionStack[projPointer + 1].load(projectionStack[projPointer]);
                projPointer++;
                break;
            case GL_TEXTURE:
                if (texPointer >= TEX_DEPTH - 1) throw new RuntimeException("Texture stack overflow");
                textureStack[texPointer + 1].load(textureStack[texPointer]);
                texPointer++;
                break;
        }
    }

    public static void pop() {
        switch (activeMode) {
            case GL_MODELVIEW:
                if (mvPointer <= 0) throw new RuntimeException("Modelview stack underflow");
                mvPointer--;
                mvDirty = true;
                mvpDirty = true;
                mvpVersion++;
                break;
            case GL_PROJECTION:
                if (projPointer <= 0) throw new RuntimeException("Projection stack underflow");
                projPointer--;
                projDirty = true;
                mvpDirty = true;
                mvpVersion++;
                break;
            case GL_TEXTURE:
                if (texPointer <= 0) throw new RuntimeException("Texture stack underflow");
                texPointer--;
                texDirty = true;
                break;
        }
    }

    public static void loadIdentity() {
        getCurrent().setIdentity();
        markDirty();
    }

    public static void translate(float x, float y, float z) {
        getCurrent().translate(x, y, z);
        markDirty();
    }

    public static void rotate(float angleDeg, float x, float y, float z) {
        float angleRad = angleDeg * (float)(Math.PI / 180.0);
        tempAxis.x = x;
        tempAxis.y = y;
        tempAxis.z = z;
        Matrix4f current = getCurrent();
        Matrix4f.rotate(angleRad, tempAxis, current, current);
        markDirty();
    }

    public static void scale(float x, float y, float z) {
        getCurrent().scale(x, y, z);
        markDirty();
    }

    public static void ortho(double left, double right, double bottom, double top, double near, double far) {
        Matrix4f o = Matrix4f.ortho((float) left, (float) right, (float) bottom, (float) top, (float) near, (float) far);
        Matrix4f current = getCurrent();
        Matrix4f.mul(current, o, current);
        markDirty();
    }

    public static void perspective(float fovy, float aspect, float near, float far) {
        Matrix4f p = Matrix4f.perspective(fovy, aspect, near, far);
        Matrix4f current = getCurrent();
        Matrix4f.mul(current, p, current);
        markDirty();
    }

    public static void multiply(FloatBuffer buf) {
        Matrix4f temp = new Matrix4f();
        int oldPos = buf.position();
        temp.load(buf);
        buf.position(oldPos);
        Matrix4f current = getCurrent();
        Matrix4f.mul(current, temp, current);
        markDirty();
    }

    public static Matrix4f getModelView() {
        return modelviewStack[mvPointer];
    }

    public static Matrix4f getProjection() {
        return projectionStack[projPointer];
    }

    public static Matrix4f getTexture() {
        return textureStack[texPointer];
    }

    public static Matrix4f getMVP() {
        if (mvpDirty) {
            Matrix4f.mul(projectionStack[projPointer], modelviewStack[mvPointer], mvpCache);
            mvpDirty = false;
        }
        return mvpCache;
    }

    public static void storeModelView(FloatBuffer buf) {
        modelviewStack[mvPointer].store(buf);
    }

    public static void storeProjection(FloatBuffer buf) {
        projectionStack[projPointer].store(buf);
    }

    public static void storeMVP(FloatBuffer buf) {
        getMVP().store(buf);
    }

    public static long getMvpVersion() { return mvpVersion; }

    public static boolean isModelViewDirty() { return mvDirty; }
    public static boolean isProjectionDirty() { return projDirty; }
    public static boolean isTextureDirty() { return texDirty; }

    public static void clearDirtyFlags() {
        mvDirty = false;
        projDirty = false;
        texDirty = false;
    }

    public static boolean isTextureIdentity() {
        Matrix4f t = textureStack[texPointer];
        return t.m00 == 1 && t.m11 == 1 && t.m22 == 1 && t.m33 == 1
            && t.m01 == 0 && t.m02 == 0 && t.m03 == 0
            && t.m10 == 0 && t.m12 == 0 && t.m13 == 0
            && t.m20 == 0 && t.m21 == 0 && t.m23 == 0
            && t.m30 == 0 && t.m31 == 0 && t.m32 == 0;
    }

    public static FloatBuffer getSharedBuffer() {
        return matrixBuf;
    }

    private static Matrix4f getCurrent() {
        switch (activeMode) {
            case GL_MODELVIEW: return modelviewStack[mvPointer];
            case GL_PROJECTION: return projectionStack[projPointer];
            case GL_TEXTURE: return textureStack[texPointer];
            default: return modelviewStack[mvPointer];
        }
    }

    private static void markDirty() {
        switch (activeMode) {
            case GL_MODELVIEW:
                mvDirty = true;
                mvpDirty = true;
                mvpVersion++;
                break;
            case GL_PROJECTION:
                projDirty = true;
                mvpDirty = true;
                mvpVersion++;
                break;
            case GL_TEXTURE:
                texDirty = true;
                break;
        }
    }
}
