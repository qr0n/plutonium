package plutonium.gpu.math;

public class Vector4f {
    public float x, y, z, w;

    public Vector4f() {}

    public Vector4f(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Vector4f(Vector4f src) {
        this.x = src.x;
        this.y = src.y;
        this.z = src.z;
        this.w = src.w;
    }

    public void set(float x, float y, float z, float w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public void set(Vector4f src) {
        this.x = src.x;
        this.y = src.y;
        this.z = src.z;
        this.w = src.w;
    }

    public float lengthSquared() {
        return x * x + y * y + z * z + w * w;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public Vector4f normalise(Vector4f dest) {
        float l = length();
        if (dest == null) dest = new Vector4f();
        dest.x = x / l;
        dest.y = y / l;
        dest.z = z / l;
        dest.w = w / l;
        return dest;
    }

    @Override
    public String toString() {
        return "Vector4f[" + x + ", " + y + ", " + z + ", " + w + "]";
    }
}
