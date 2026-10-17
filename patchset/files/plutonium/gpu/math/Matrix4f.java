package plutonium.gpu.math;

import java.nio.FloatBuffer;

public class Matrix4f {
    public float m00, m01, m02, m03;
    public float m10, m11, m12, m13;
    public float m20, m21, m22, m23;
    public float m30, m31, m32, m33;

    public Matrix4f() {
        setIdentity();
    }

    public Matrix4f(Matrix4f src) {
        load(src);
    }

    public void setIdentity() {
        m00 = 1; m01 = 0; m02 = 0; m03 = 0;
        m10 = 0; m11 = 1; m12 = 0; m13 = 0;
        m20 = 0; m21 = 0; m22 = 1; m23 = 0;
        m30 = 0; m31 = 0; m32 = 0; m33 = 1;
    }

    public Matrix4f load(Matrix4f src) {
        m00 = src.m00; m01 = src.m01; m02 = src.m02; m03 = src.m03;
        m10 = src.m10; m11 = src.m11; m12 = src.m12; m13 = src.m13;
        m20 = src.m20; m21 = src.m21; m22 = src.m22; m23 = src.m23;
        m30 = src.m30; m31 = src.m31; m32 = src.m32; m33 = src.m33;
        return this;
    }

    public static Matrix4f mul(Matrix4f left, Matrix4f right, Matrix4f dest) {
        if (dest == null) dest = new Matrix4f();
        float n00 = left.m00 * right.m00 + left.m10 * right.m01 + left.m20 * right.m02 + left.m30 * right.m03;
        float n01 = left.m01 * right.m00 + left.m11 * right.m01 + left.m21 * right.m02 + left.m31 * right.m03;
        float n02 = left.m02 * right.m00 + left.m12 * right.m01 + left.m22 * right.m02 + left.m32 * right.m03;
        float n03 = left.m03 * right.m00 + left.m13 * right.m01 + left.m23 * right.m02 + left.m33 * right.m03;
        float n10 = left.m00 * right.m10 + left.m10 * right.m11 + left.m20 * right.m12 + left.m30 * right.m13;
        float n11 = left.m01 * right.m10 + left.m11 * right.m11 + left.m21 * right.m12 + left.m31 * right.m13;
        float n12 = left.m02 * right.m10 + left.m12 * right.m11 + left.m22 * right.m12 + left.m32 * right.m13;
        float n13 = left.m03 * right.m10 + left.m13 * right.m11 + left.m23 * right.m12 + left.m33 * right.m13;
        float n20 = left.m00 * right.m20 + left.m10 * right.m21 + left.m20 * right.m22 + left.m30 * right.m23;
        float n21 = left.m01 * right.m20 + left.m11 * right.m21 + left.m21 * right.m22 + left.m31 * right.m23;
        float n22 = left.m02 * right.m20 + left.m12 * right.m21 + left.m22 * right.m22 + left.m32 * right.m23;
        float n23 = left.m03 * right.m20 + left.m13 * right.m21 + left.m23 * right.m22 + left.m33 * right.m23;
        float n30 = left.m00 * right.m30 + left.m10 * right.m31 + left.m20 * right.m32 + left.m30 * right.m33;
        float n31 = left.m01 * right.m30 + left.m11 * right.m31 + left.m21 * right.m32 + left.m31 * right.m33;
        float n32 = left.m02 * right.m30 + left.m12 * right.m31 + left.m22 * right.m32 + left.m32 * right.m33;
        float n33 = left.m03 * right.m30 + left.m13 * right.m31 + left.m23 * right.m32 + left.m33 * right.m33;
        dest.m00 = n00; dest.m01 = n01; dest.m02 = n02; dest.m03 = n03;
        dest.m10 = n10; dest.m11 = n11; dest.m12 = n12; dest.m13 = n13;
        dest.m20 = n20; dest.m21 = n21; dest.m22 = n22; dest.m23 = n23;
        dest.m30 = n30; dest.m31 = n31; dest.m32 = n32; dest.m33 = n33;
        return dest;
    }

    public static Matrix4f rotate(float angle, Vector3f axis, Matrix4f src, Matrix4f dest) {
        if (dest == null) dest = new Matrix4f();
        float c = (float) Math.cos(angle);
        float s = (float) Math.sin(angle);
        float oneminusc = 1.0f - c;
        float xy = axis.x * axis.y;
        float yz = axis.y * axis.z;
        float xz = axis.x * axis.z;
        float xs = axis.x * s;
        float ys = axis.y * s;
        float zs = axis.z * s;

        float f00 = axis.x * axis.x * oneminusc + c;
        float f01 = xy * oneminusc + zs;
        float f02 = xz * oneminusc - ys;
        float f10 = xy * oneminusc - zs;
        float f11 = axis.y * axis.y * oneminusc + c;
        float f12 = yz * oneminusc + xs;
        float f20 = xz * oneminusc + ys;
        float f21 = yz * oneminusc - xs;
        float f22 = axis.z * axis.z * oneminusc + c;

        float t00 = src.m00 * f00 + src.m10 * f01 + src.m20 * f02;
        float t01 = src.m01 * f00 + src.m11 * f01 + src.m21 * f02;
        float t02 = src.m02 * f00 + src.m12 * f01 + src.m22 * f02;
        float t03 = src.m03 * f00 + src.m13 * f01 + src.m23 * f02;
        float t10 = src.m00 * f10 + src.m10 * f11 + src.m20 * f12;
        float t11 = src.m01 * f10 + src.m11 * f11 + src.m21 * f12;
        float t12 = src.m02 * f10 + src.m12 * f11 + src.m22 * f12;
        float t13 = src.m03 * f10 + src.m13 * f11 + src.m23 * f12;
        dest.m20 = src.m00 * f20 + src.m10 * f21 + src.m20 * f22;
        dest.m21 = src.m01 * f20 + src.m11 * f21 + src.m21 * f22;
        dest.m22 = src.m02 * f20 + src.m12 * f21 + src.m22 * f22;
        dest.m23 = src.m03 * f20 + src.m13 * f21 + src.m23 * f22;
        dest.m00 = t00; dest.m01 = t01; dest.m02 = t02; dest.m03 = t03;
        dest.m10 = t10; dest.m11 = t11; dest.m12 = t12; dest.m13 = t13;
        return dest;
    }

    public static Vector4f transform(Matrix4f left, Vector4f right, Vector4f dest) {
        if (dest == null) dest = new Vector4f();
        float x = left.m00 * right.x + left.m10 * right.y + left.m20 * right.z + left.m30 * right.w;
        float y = left.m01 * right.x + left.m11 * right.y + left.m21 * right.z + left.m31 * right.w;
        float z = left.m02 * right.x + left.m12 * right.y + left.m22 * right.z + left.m32 * right.w;
        float w = left.m03 * right.x + left.m13 * right.y + left.m23 * right.z + left.m33 * right.w;
        dest.x = x; dest.y = y; dest.z = z; dest.w = w;
        return dest;
    }

    public Matrix4f transpose() {
        float t01 = m01; m01 = m10; m10 = t01;
        float t02 = m02; m02 = m20; m20 = t02;
        float t03 = m03; m03 = m30; m30 = t03;
        float t12 = m12; m12 = m21; m21 = t12;
        float t13 = m13; m13 = m31; m31 = t13;
        float t23 = m23; m23 = m32; m32 = t23;
        return this;
    }

    public Matrix4f invert() {
        float[] m = {m00,m01,m02,m03, m10,m11,m12,m13, m20,m21,m22,m23, m30,m31,m32,m33};
        float[] inv = new float[16];
        inv[0] = m[5]*m[10]*m[15] - m[5]*m[11]*m[14] - m[9]*m[6]*m[15] + m[9]*m[7]*m[14] + m[13]*m[6]*m[11] - m[13]*m[7]*m[10];
        inv[4] = -m[4]*m[10]*m[15] + m[4]*m[11]*m[14] + m[8]*m[6]*m[15] - m[8]*m[7]*m[14] - m[12]*m[6]*m[11] + m[12]*m[7]*m[10];
        inv[8] = m[4]*m[9]*m[15] - m[4]*m[11]*m[13] - m[8]*m[5]*m[15] + m[8]*m[7]*m[13] + m[12]*m[5]*m[11] - m[12]*m[7]*m[9];
        inv[12] = -m[4]*m[9]*m[14] + m[4]*m[10]*m[13] + m[8]*m[5]*m[14] - m[8]*m[6]*m[13] - m[12]*m[5]*m[10] + m[12]*m[6]*m[9];
        inv[1] = -m[1]*m[10]*m[15] + m[1]*m[11]*m[14] + m[9]*m[2]*m[15] - m[9]*m[3]*m[14] - m[13]*m[2]*m[11] + m[13]*m[3]*m[10];
        inv[5] = m[0]*m[10]*m[15] - m[0]*m[11]*m[14] - m[8]*m[2]*m[15] + m[8]*m[3]*m[14] + m[12]*m[2]*m[11] - m[12]*m[3]*m[10];
        inv[9] = -m[0]*m[9]*m[15] + m[0]*m[11]*m[13] + m[8]*m[1]*m[15] - m[8]*m[3]*m[13] - m[12]*m[1]*m[11] + m[12]*m[3]*m[9];
        inv[13] = m[0]*m[9]*m[14] - m[0]*m[10]*m[13] - m[8]*m[1]*m[14] + m[8]*m[2]*m[13] + m[12]*m[1]*m[10] - m[12]*m[2]*m[9];
        inv[2] = m[1]*m[6]*m[15] - m[1]*m[7]*m[14] - m[5]*m[2]*m[15] + m[5]*m[3]*m[14] + m[13]*m[2]*m[7] - m[13]*m[3]*m[6];
        inv[6] = -m[0]*m[6]*m[15] + m[0]*m[7]*m[14] + m[4]*m[2]*m[15] - m[4]*m[3]*m[14] - m[12]*m[2]*m[7] + m[12]*m[3]*m[6];
        inv[10] = m[0]*m[5]*m[15] - m[0]*m[7]*m[13] - m[4]*m[1]*m[15] + m[4]*m[3]*m[13] + m[12]*m[1]*m[7] - m[12]*m[3]*m[5];
        inv[14] = -m[0]*m[5]*m[14] + m[0]*m[6]*m[13] + m[4]*m[1]*m[14] - m[4]*m[2]*m[13] - m[12]*m[1]*m[6] + m[12]*m[2]*m[5];
        inv[3] = -m[1]*m[6]*m[11] + m[1]*m[7]*m[10] + m[5]*m[2]*m[11] - m[5]*m[3]*m[10] - m[9]*m[2]*m[7] + m[9]*m[3]*m[6];
        inv[7] = m[0]*m[6]*m[11] - m[0]*m[7]*m[10] - m[4]*m[2]*m[11] + m[4]*m[3]*m[10] + m[8]*m[2]*m[7] - m[8]*m[3]*m[6];
        inv[11] = -m[0]*m[5]*m[11] + m[0]*m[7]*m[9] + m[4]*m[1]*m[11] - m[4]*m[3]*m[9] - m[8]*m[1]*m[7] + m[8]*m[3]*m[5];
        inv[15] = m[0]*m[5]*m[10] - m[0]*m[6]*m[9] - m[4]*m[1]*m[10] + m[4]*m[2]*m[9] + m[8]*m[1]*m[6] - m[8]*m[2]*m[5];
        float det = m[0]*inv[0] + m[1]*inv[4] + m[2]*inv[8] + m[3]*inv[12];
        if (det == 0) return this;
        det = 1.0f / det;
        m00 = inv[0]*det; m01 = inv[1]*det; m02 = inv[2]*det; m03 = inv[3]*det;
        m10 = inv[4]*det; m11 = inv[5]*det; m12 = inv[6]*det; m13 = inv[7]*det;
        m20 = inv[8]*det; m21 = inv[9]*det; m22 = inv[10]*det; m23 = inv[11]*det;
        m30 = inv[12]*det; m31 = inv[13]*det; m32 = inv[14]*det; m33 = inv[15]*det;
        return this;
    }

    public Matrix4f translate(Vector3f vec) {
        m30 += m00 * vec.x + m10 * vec.y + m20 * vec.z;
        m31 += m01 * vec.x + m11 * vec.y + m21 * vec.z;
        m32 += m02 * vec.x + m12 * vec.y + m22 * vec.z;
        m33 += m03 * vec.x + m13 * vec.y + m23 * vec.z;
        return this;
    }

    public Matrix4f translate(float x, float y, float z) {
        m30 += m00 * x + m10 * y + m20 * z;
        m31 += m01 * x + m11 * y + m21 * z;
        m32 += m02 * x + m12 * y + m22 * z;
        m33 += m03 * x + m13 * y + m23 * z;
        return this;
    }

    public Matrix4f scale(float x, float y, float z) {
        m00 *= x; m01 *= x; m02 *= x; m03 *= x;
        m10 *= y; m11 *= y; m12 *= y; m13 *= y;
        m20 *= z; m21 *= z; m22 *= z; m23 *= z;
        return this;
    }

    public void store(FloatBuffer buf) {
        buf.put(m00); buf.put(m01); buf.put(m02); buf.put(m03);
        buf.put(m10); buf.put(m11); buf.put(m12); buf.put(m13);
        buf.put(m20); buf.put(m21); buf.put(m22); buf.put(m23);
        buf.put(m30); buf.put(m31); buf.put(m32); buf.put(m33);
        buf.flip();
    }

    public void load(FloatBuffer buf) {
        m00 = buf.get(); m01 = buf.get(); m02 = buf.get(); m03 = buf.get();
        m10 = buf.get(); m11 = buf.get(); m12 = buf.get(); m13 = buf.get();
        m20 = buf.get(); m21 = buf.get(); m22 = buf.get(); m23 = buf.get();
        m30 = buf.get(); m31 = buf.get(); m32 = buf.get(); m33 = buf.get();
    }

    public static Matrix4f ortho(float left, float right, float bottom, float top, float near, float far) {
        Matrix4f m = new Matrix4f();
        m.m00 = 2.0f / (right - left);
        m.m11 = 2.0f / (top - bottom);
        m.m22 = -2.0f / (far - near);
        m.m30 = -(right + left) / (right - left);
        m.m31 = -(top + bottom) / (top - bottom);
        m.m32 = -(far + near) / (far - near);
        m.m01 = 0; m.m02 = 0; m.m03 = 0;
        m.m10 = 0; m.m12 = 0; m.m13 = 0;
        m.m20 = 0; m.m21 = 0; m.m23 = 0;
        m.m33 = 1;
        return m;
    }

    public static Matrix4f perspective(float fovy, float aspect, float near, float far) {
        float f = 1.0f / (float) Math.tan(fovy * 0.5f);
        Matrix4f m = new Matrix4f();
        m.m00 = f / aspect;
        m.m11 = f;
        m.m22 = (far + near) / (near - far);
        m.m23 = -1.0f;
        m.m32 = (2.0f * far * near) / (near - far);
        m.m01 = 0; m.m02 = 0; m.m03 = 0;
        m.m10 = 0; m.m12 = 0; m.m13 = 0;
        m.m20 = 0; m.m21 = 0;
        m.m30 = 0; m.m31 = 0; m.m33 = 0;
        return m;
    }

    @Override
    public String toString() {
        return "Matrix4f";
    }
}
