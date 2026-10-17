package plutonium.gpu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;

public class GLUCompat {

    // Reused per-call to avoid leaking a fresh direct buffer ~5-6x/frame.
    // MatrixStack.multiply consumes it synchronously, so a single static buffer is safe.
    private static final FloatBuffer PERSPECTIVE = org.lwjgl.BufferUtils.createFloatBuffer(16);

    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar) {
        float f = (float) (1.0 / Math.tan(Math.toRadians(fovy / 2.0)));
        PERSPECTIVE.clear();
        PERSPECTIVE.put(new float[] {
            f / aspect, 0, 0, 0,
            0, f, 0, 0,
            0, 0, (zFar + zNear) / (zNear - zFar), -1,
            0, 0, (2 * zFar * zNear) / (zNear - zFar), 0
        });
        PERSPECTIVE.flip();
        MatrixStack.multiply(PERSPECTIVE);
    }

    public static boolean gluProject(float objX, float objY, float objZ,
                                      FloatBuffer modelview, FloatBuffer projection,
                                      IntBuffer viewport, FloatBuffer winPos) {
        float[] m = new float[16];
        float[] p = new float[16];
        modelview.get(m); modelview.position(modelview.position() - 16);
        projection.get(p); projection.position(projection.position() - 16);

        float x = m[0] * objX + m[4] * objY + m[8] * objZ + m[12];
        float y = m[1] * objX + m[5] * objY + m[9] * objZ + m[13];
        float z = m[2] * objX + m[6] * objY + m[10] * objZ + m[14];
        float w = m[3] * objX + m[7] * objY + m[11] * objZ + m[15];

        float cx = p[0] * x + p[4] * y + p[8] * z + p[12] * w;
        float cy = p[1] * x + p[5] * y + p[9] * z + p[13] * w;
        float cz = p[2] * x + p[6] * y + p[10] * z + p[14] * w;
        float cw = p[3] * x + p[7] * y + p[11] * z + p[15] * w;

        if (cw == 0.0f) return false;

        float ndcX = cx / cw;
        float ndcY = cy / cw;
        float ndcZ = cz / cw;

        int vpX = viewport.get(viewport.position());
        int vpY = viewport.get(viewport.position() + 1);
        int vpW = viewport.get(viewport.position() + 2);
        int vpH = viewport.get(viewport.position() + 3);

        winPos.put(winPos.position(), vpX + (ndcX * 0.5f + 0.5f) * vpW);
        winPos.put(winPos.position() + 1, vpY + (ndcY * 0.5f + 0.5f) * vpH);
        winPos.put(winPos.position() + 2, ndcZ * 0.5f + 0.5f);
        return true;
    }

    public static boolean gluUnProject(float winX, float winY, float winZ,
                                        FloatBuffer modelview, FloatBuffer projection,
                                        IntBuffer viewport, FloatBuffer objPos) {
        float[] m = new float[16];
        float[] p = new float[16];
        modelview.get(m); modelview.position(modelview.position() - 16);
        projection.get(p); projection.position(projection.position() - 16);

        float[] pm = new float[16];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                pm[i * 4 + j] = 0;
                for (int k = 0; k < 4; k++) {
                    pm[i * 4 + j] += p[k * 4 + j] * m[i * 4 + k];
                }
            }
        }

        float[] inv = new float[16];
        if (!invertMatrix(pm, inv)) return false;

        int vpX = viewport.get(viewport.position());
        int vpY = viewport.get(viewport.position() + 1);
        int vpW = viewport.get(viewport.position() + 2);
        int vpH = viewport.get(viewport.position() + 3);

        float ndcX = (winX - vpX) / vpW * 2.0f - 1.0f;
        float ndcY = (winY - vpY) / vpH * 2.0f - 1.0f;
        float ndcZ = winZ * 2.0f - 1.0f;

        float ox = inv[0] * ndcX + inv[4] * ndcY + inv[8] * ndcZ + inv[12];
        float oy = inv[1] * ndcX + inv[5] * ndcY + inv[9] * ndcZ + inv[13];
        float oz = inv[2] * ndcX + inv[6] * ndcY + inv[10] * ndcZ + inv[14];
        float ow = inv[3] * ndcX + inv[7] * ndcY + inv[11] * ndcZ + inv[15];

        if (ow == 0.0f) return false;

        objPos.put(objPos.position(), ox / ow);
        objPos.put(objPos.position() + 1, oy / ow);
        objPos.put(objPos.position() + 2, oz / ow);
        return true;
    }

    private static boolean invertMatrix(float[] m, float[] inv) {
        float[] t = new float[16];
        t[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        t[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        t[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        t[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
        t[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        t[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        t[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        t[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
        t[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6];
        t[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6];
        t[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5];
        t[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5];
        t[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6];
        t[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6];
        t[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] - m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5];
        t[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] + m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5];

        float det = m[0]*t[0] + m[1]*t[4] + m[2]*t[8] + m[3]*t[12];
        if (det == 0) return false;
        det = 1.0f / det;
        for (int i = 0; i < 16; i++) inv[i] = t[i] * det;
        return true;
    }

    public static String gluErrorString(int error) {
        switch (error) {
            case 0: return "No error";
            case 0x0500: return "Invalid enum";
            case 0x0501: return "Invalid value";
            case 0x0502: return "Invalid operation";
            case 0x0503: return "Stack overflow";
            case 0x0504: return "Stack underflow";
            case 0x0505: return "Out of memory";
            case 0x0506: return "Invalid framebuffer operation";
            default: return "Unknown error " + error;
        }
    }
}
