package elucent.albedo.lighting;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * A coloured point or cone light. The heading (rx, ry, rz) gives the direction of a cone light and its length is the
 * light's radius; lights with an angle of at least PI shine in all directions.
 */
public class Light {
    /** Light count the shaders can hold ({@code uniform Light lights[100]}). */
    public static final int MAX_SHADER_LIGHTS = 100;

    public float x;
    public float y;
    public float z;
    public float r;
    public float g;
    public float b;
    public float a;
    public float rx;
    public float ry;
    public float rz;
    public float angle;
    /**
     * Radius as set by the constructors, kept for mods built against Albedo 0.1.x.
     *
     * @deprecated use {@link #radius()}, which follows changes to the heading
     */
    @Deprecated
    public float radius;

    // Full-precision position. A float only has 24 bits of mantissa, so positions far from the world origin
    // (more than a few million blocks) snap to a coarse grid; see getPreciseX().
    private double preciseX;
    private double preciseY;
    private double preciseZ;

    public Light(float x, float y, float z, float r, float g, float b, float a, float radius) {
        this(x, y, z, r, g, b, a, 0, radius, 0, (float) (Math.PI * 2));
    }

    public Light(float x, float y, float z, float r, float g, float b, float a, float rx, float ry, float rz, float angle) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
        this.rx = rx;
        this.ry = ry;
        this.rz = rz;
        this.angle = angle;
        this.radius = radius();
        this.preciseX = x;
        this.preciseY = y;
        this.preciseZ = z;
    }

    public static Builder builder() {
        return new Builder();
    }

    public float radius() {
        return (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
    }

    /** Sets the position with full double precision (also updates the float fields). */
    public Light setPosition(double x, double y, double z) {
        this.x = (float) x;
        this.y = (float) y;
        this.z = (float) z;
        this.preciseX = x;
        this.preciseY = y;
        this.preciseZ = z;
        return this;
    }

    /** The position with full precision, or the float field if a mod changed it directly. */
    public double getPreciseX() {
        return (float) preciseX == x ? preciseX : x;
    }

    public double getPreciseY() {
        return (float) preciseY == y ? preciseY : y;
    }

    public double getPreciseZ() {
        return (float) preciseZ == z ? preciseZ : z;
    }

    /** True if position, colour and heading are all finite numbers (shaders turn NaN into black pixels). */
    public boolean isValid() {
        return Float.isFinite(x) && Float.isFinite(y) && Float.isFinite(z)
                && Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b) && Float.isFinite(a)
                && Float.isFinite(rx) && Float.isFinite(ry) && Float.isFinite(rz) && Float.isFinite(angle);
    }

    public static final class Builder {
        private double x = Double.NaN;
        private double y = Double.NaN;
        private double z = Double.NaN;

        private float r = Float.NaN;
        private float g = Float.NaN;
        private float b = Float.NaN;
        private float a = Float.NaN;

        private float rx = Float.NaN;
        private float ry = Float.NaN;
        private float rz = Float.NaN;
        private float angle = Float.NaN;

        public Builder pos(BlockPos pos) {
            return pos(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }

        public Builder pos(Vec3d pos) {
            return pos(pos.x, pos.y, pos.z);
        }

        public Builder pos(Entity e) {
            return pos(e.posX, e.posY, e.posZ);
        }

        public Builder pos(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public Builder pos(float x, float y, float z) {
            return pos((double) x, (double) y, (double) z);
        }

        public Builder color(int c, boolean hasAlpha) {
            return color(extract(c, 2), extract(c, 1), extract(c, 0), hasAlpha ? extract(c, 3) : 1);
        }

        private float extract(int i, int idx) {
            return ((i >> (idx * 8)) & 0xFF) / 255f;
        }

        public Builder color(float r, float g, float b) {
            return color(r, g, b, 1f);
        }

        public Builder color(float r, float g, float b, float a) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
            return this;
        }

        /** Makes this a point light shining in all directions. */
        public Builder radius(float radius) {
            this.rx = 0;
            this.ry = radius;
            this.rz = 0;
            this.angle = (float) (Math.PI * 2);
            return this;
        }

        /** Makes this a cone light; the length of the direction vector is the radius. */
        public Builder direction(float x, float y, float z, float angle) {
            this.rx = x;
            this.ry = y;
            this.rz = z;
            this.angle = angle;
            return this;
        }

        public Builder direction(Vec3d vec, float angle) {
            return direction((float) vec.x, (float) vec.y, (float) vec.z, angle);
        }

        public Light build() {
            if (Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z) &&
                    Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b) && Float.isFinite(a) &&
                    Float.isFinite(rx) && Float.isFinite(ry) && Float.isFinite(rz) && Float.isFinite(angle)) {
                return new Light((float) x, (float) y, (float) z, r, g, b, a, rx, ry, rz, angle).setPosition(x, y, z);
            } else {
                throw new IllegalArgumentException("Position, color, and radius must be set, and cannot be infinite");
            }
        }
    }
}
