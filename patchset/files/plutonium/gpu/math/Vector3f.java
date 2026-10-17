package plutonium.gpu.math;

public class Vector3f {
    public float x, y, z;

    public Vector3f() {}

    public Vector3f(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f(Vector3f src) {
        this.x = src.x;
        this.y = src.y;
        this.z = src.z;
    }

    public void set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public void set(Vector3f src) {
        this.x = src.x;
        this.y = src.y;
        this.z = src.z;
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public float length() {
        return (float) Math.sqrt(lengthSquared());
    }

    public Vector3f normalise(Vector3f dest) {
        float l = length();
        if (dest == null) dest = new Vector3f();
        dest.x = x / l;
        dest.y = y / l;
        dest.z = z / l;
        return dest;
    }

    public Vector3f negate(Vector3f dest) {
        if (dest == null) dest = new Vector3f();
        dest.x = -x;
        dest.y = -y;
        dest.z = -z;
        return dest;
    }

    public Vector3f scale(float scale) {
        x *= scale;
        y *= scale;
        z *= scale;
        return this;
    }

    public static Vector3f add(Vector3f left, Vector3f right, Vector3f dest) {
        if (dest == null) dest = new Vector3f();
        dest.x = left.x + right.x;
        dest.y = left.y + right.y;
        dest.z = left.z + right.z;
        return dest;
    }

    public static Vector3f sub(Vector3f left, Vector3f right, Vector3f dest) {
        if (dest == null) dest = new Vector3f();
        dest.x = left.x - right.x;
        dest.y = left.y - right.y;
        dest.z = left.z - right.z;
        return dest;
    }

    public static Vector3f cross(Vector3f left, Vector3f right, Vector3f dest) {
        if (dest == null) dest = new Vector3f();
        float cx = left.y * right.z - left.z * right.y;
        float cy = left.z * right.x - left.x * right.z;
        float cz = left.x * right.y - left.y * right.x;
        dest.x = cx;
        dest.y = cy;
        dest.z = cz;
        return dest;
    }

    public static float dot(Vector3f left, Vector3f right) {
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof Vector3f)) return false;
        Vector3f o = (Vector3f) obj;
        return x == o.x && y == o.y && z == o.z;
    }

    @Override
    public String toString() {
        return "Vector3f[" + x + ", " + y + ", " + z + "]";
    }
}
