package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.subtitle.CaptionPlacement.Point;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.joml.Vector4fc;

import java.util.Objects;

/**
 * High-performance, zero-allocation circular slot buffer for 3D geometry calculations.
 * <p>
 * Eliminates heap allocation during per-frame rendering operations such as billboard projection,
 * camera orientation transforms, and screen bounds clipping.
 * Thread-safe via {@link ThreadLocal} with zero synchronization overhead on hot execution paths.
 */
public final class GeometrySlotRing {
    public static final int DEFAULT_CAPACITY = 512;
    public static final int MAX_SCOPE_DEPTH = 32;
    private static final float NEAR_CLIP = 0.05F;

    private static final ThreadLocal<GeometrySlotRing> THREAD_LOCAL = ThreadLocal.withInitial(GeometrySlotRing::new);
    private static volatile boolean WARNED_OVERFLOW = false;

    private final Slot[] slots;
    private final int capacity;
    private final int mask;
    private int cursor;

    private final Scope[] scopePool;
    private int scopeDepth;
    private boolean overflowCheckEnabled = true;
    private boolean safeDegradationEnabled = true;

    // Dedicated scratch objects for internal compound operations to avoid consuming ring slots
    private final Vector3f scratchV3 = new Vector3f();
    private final Vector4f scratchV4 = new Vector4f();

    /**
     * Gets the current thread's geometry slot ring instance.
     */
    public static GeometrySlotRing current() {
        return THREAD_LOCAL.get();
    }

    /**
     * Alias for {@link #current()}.
     */
    public static GeometrySlotRing get() {
        return THREAD_LOCAL.get();
    }

    /**
     * Opens a scoped reservation context on the current thread's geometry ring.
     */
    public static Scope openScope() {
        return current().open();
    }

    /**
     * Creates a new ring with default capacity (128 slots).
     */
    public GeometrySlotRing() {
        this(DEFAULT_CAPACITY);
    }

    /**
     * Creates a new ring with the specified capacity (rounded up to power of two, minimum 16).
     */
    public GeometrySlotRing(int capacity) {
        int cap = Math.max(16, roundToPowerOfTwo(capacity));
        this.capacity = cap;
        this.mask = cap - 1;
        this.slots = new Slot[cap];
        for (int i = 0; i < cap; i++) {
            this.slots[i] = new Slot(i);
        }
        this.scopePool = new Scope[MAX_SCOPE_DEPTH];
        for (int i = 0; i < MAX_SCOPE_DEPTH; i++) {
            this.scopePool[i] = new Scope(i);
        }
    }

    private static int roundToPowerOfTwo(int value) {
        int n = value - 1;
        n |= n >>> 1;
        n |= n >>> 2;
        n |= n >>> 4;
        n |= n >>> 8;
        n |= n >>> 16;
        return n < 0 ? 1 : n + 1;
    }

    // ==========================================
    // Scope & Cursor Management
    // ==========================================

    /**
     * Opens a new nested scope. Closing the scope rolls the ring cursor back to
     * the state it was in when this scope was opened.
     */
    public Scope open() {
        if (scopeDepth >= scopePool.length) {
            throw new IllegalStateException("Exceeded maximum scope depth (" + scopePool.length + ")");
        }
        Scope scope = scopePool[scopeDepth++];
        scope.mark = this.cursor;
        scope.active = true;
        return scope;
    }

    /**
     * Records the current slot cursor position.
     */
    public int mark() {
        return cursor;
    }

    /**
     * Resets the slot cursor to a previously recorded mark.
     */
    public void reset(int mark) {
        int diff = cursor - mark;
        if (diff < 0) {
            throw new IllegalArgumentException("Invalid mark ahead of cursor: mark=" + mark + ", cursor=" + cursor);
        }
        if (diff > capacity) {
            throw new IllegalArgumentException("Mark is older than ring capacity: mark=" + mark + ", cursor=" + cursor);
        }
        this.cursor = mark;
    }

    /**
     * Returns true if the slot allocated at the given mark has already been overwritten.
     */
    public boolean isOverwritten(int mark) {
        int diff = cursor - mark;
        return diff >= capacity || diff < 0;
    }

    /**
     * Clears all scopes and resets the cursor to 0, providing a fresh state.
     */
    public void clear() {
        this.cursor = 0;
        this.scopeDepth = 0;
        for (int i = 0; i < scopePool.length; i++) {
            scopePool[i].active = false;
        }
    }

    /**
     * Resets the current thread's geometry ring to a clean initial state.
     */
    public static void resetCurrentThread() {
        THREAD_LOCAL.get().clear();
    }

    public int cursor() {
        return cursor;
    }

    void setCursorForTesting(int cursor) {
        this.cursor = cursor;
    }

    public int capacity() {
        return capacity;
    }

    public int scopeDepth() {
        return scopeDepth;
    }

    public int activeAllocations() {
        return scopeDepth == 0 ? 0 : (cursor - scopePool[0].mark);
    }

    public int availableSlots() {
        return Math.max(0, capacity - activeAllocations());
    }

    public boolean isOverflowCheckEnabled() {
        return overflowCheckEnabled;
    }

    public void setOverflowCheckEnabled(boolean enabled) {
        this.overflowCheckEnabled = enabled;
    }

    public boolean isSafeDegradationEnabled() {
        return safeDegradationEnabled;
    }

    public void setSafeDegradationEnabled(boolean enabled) {
        this.safeDegradationEnabled = enabled;
    }

    private Slot advanceSlot() {
        if (overflowCheckEnabled && scopeDepth > 0) {
            int active = cursor - scopePool[0].mark;
            if (active >= capacity) {
                if (safeDegradationEnabled) {
                    if (!WARNED_OVERFLOW) {
                        System.err.println("[Onomixel] WARN: GeometrySlotRing capacity (" + capacity + ") exceeded by active scopes. Safely wrapping around.");
                        WARNED_OVERFLOW = true;
                    }
                    int index = (cursor++) & mask;
                    return slots[index];
                }
                throw new SlotRingOverflowException(
                        "GeometrySlotRing capacity (" + capacity + ") exceeded by active scopes. " +
                        "Active allocations=" + active + ", rootMark=" + scopePool[0].mark + ", cursor=" + cursor
                );
            }
        }
        int index = (cursor++) & mask;
        return slots[index];
    }

    /**
     * Advances to and returns the next slot in the ring.
     */
    public Slot nextSlot() {
        return advanceSlot();
    }

    /**
     * Inspects a slot at a given ring index without advancing the cursor.
     */
    public Slot slotAt(int index) {
        return slots[index & mask];
    }

    // ==========================================
    // Vector3f Allocations & Math
    // ==========================================

    public Vector3f vec3() {
        return advanceSlot().v3.zero();
    }

    public Vector3f vec3(float x, float y, float z) {
        return advanceSlot().v3.set(x, y, z);
    }

    public Vector3f vec3(Vector3fc other) {
        Objects.requireNonNull(other, "other vector cannot be null");
        return advanceSlot().v3.set(other);
    }

    public Vector3f rotate(Vector3fc v, Quaternionfc q) {
        Objects.requireNonNull(v, "vector cannot be null");
        Objects.requireNonNull(q, "quaternion cannot be null");
        return vec3(v).rotate(q);
    }

    public Vector3f rotate(Vector3fc v, Quaternionfc q, Vector3f dest) {
        Objects.requireNonNull(v, "vector cannot be null");
        Objects.requireNonNull(q, "quaternion cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(v).rotate(q);
    }

    public Vector3f scale(Vector3fc v, float s) {
        Objects.requireNonNull(v, "vector cannot be null");
        return vec3(v.x() * s, v.y() * s, v.z() * s);
    }

    public Vector3f scale(Vector3fc v, float sx, float sy, float sz) {
        Objects.requireNonNull(v, "vector cannot be null");
        return vec3(v.x() * sx, v.y() * sy, v.z() * sz);
    }

    public Vector3f scale(Vector3fc v, float sx, float sy, float sz, Vector3f dest) {
        Objects.requireNonNull(v, "vector cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(v.x() * sx, v.y() * sy, v.z() * sz);
    }

    public Vector3f add(Vector3fc a, Vector3fc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return vec3(a).add(b);
    }

    public Vector3f add(Vector3fc a, Vector3fc b, Vector3f dest) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(a).add(b);
    }

    public Vector3f sub(Vector3fc a, Vector3fc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return vec3(a).sub(b);
    }

    public Vector3f sub(Vector3fc a, Vector3fc b, Vector3f dest) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(a).sub(b);
    }

    public Vector3f cross(Vector3fc a, Vector3fc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return vec3(a).cross(b);
    }

    public Vector3f cross(Vector3fc a, Vector3fc b, Vector3f dest) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(a).cross(b);
    }

    public float dot(Vector3fc a, Vector3fc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return a.x() * b.x() + a.y() * b.y() + a.z() * b.z();
    }

    public Vector3f normalize(Vector3fc v) {
        Objects.requireNonNull(v, "vector cannot be null");
        return vec3(v).normalize();
    }

    public Vector3f normalize(Vector3fc v, Vector3f dest) {
        Objects.requireNonNull(v, "vector cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(v).normalize();
    }

    public Vector3f lerp(Vector3fc a, Vector3fc b, float t) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return vec3(a).lerp(b, t);
    }

    public Vector3f lerp(Vector3fc a, Vector3fc b, float t, Vector3f dest) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(a).lerp(b, t);
    }

    public float distance(Vector3fc a, Vector3fc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return a.distance(b);
    }

    public float distanceSquared(Vector3fc a, Vector3fc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return a.distanceSquared(b);
    }

    public float length(Vector3fc v) {
        Objects.requireNonNull(v, "vector cannot be null");
        return v.length();
    }

    public float lengthSquared(Vector3fc v) {
        Objects.requireNonNull(v, "vector cannot be null");
        return v.lengthSquared();
    }

    // ==========================================
    // Vector4f Allocations & Math
    // ==========================================

    public Vector4f vec4() {
        return advanceSlot().v4.zero();
    }

    public Vector4f vec4(float x, float y, float z, float w) {
        return advanceSlot().v4.set(x, y, z, w);
    }

    public Vector4f vec4(Vector3fc v, float w) {
        Objects.requireNonNull(v, "vector cannot be null");
        return advanceSlot().v4.set(v.x(), v.y(), v.z(), w);
    }

    public Vector4f vec4(Vector4fc other) {
        Objects.requireNonNull(other, "other vector cannot be null");
        return advanceSlot().v4.set(other);
    }

    public Vector4f transform(Matrix4fc mat, Vector4fc v) {
        Objects.requireNonNull(mat, "matrix cannot be null");
        Objects.requireNonNull(v, "vector cannot be null");
        return mat.transform(vec4(v));
    }

    public Vector4f transform(Matrix4fc mat, Vector4fc v, Vector4f dest) {
        Objects.requireNonNull(mat, "matrix cannot be null");
        Objects.requireNonNull(v, "vector cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return mat.transform(v, dest);
    }

    public Vector4f transformPoint(Matrix4fc mat, float x, float y, float z) {
        Objects.requireNonNull(mat, "matrix cannot be null");
        return mat.transform(vec4(x, y, z, 1.0F));
    }

    public Vector4f transformPoint(Matrix4fc mat, float x, float y, float z, Vector4f dest) {
        Objects.requireNonNull(mat, "matrix cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return mat.transform(dest.set(x, y, z, 1.0F));
    }

    public Vector4f lerp(Vector4fc a, Vector4fc b, float t) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return vec4(a).lerp(b, t);
    }

    public Vector4f lerp(Vector4fc a, Vector4fc b, float t, Vector4f dest) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(a).lerp(b, t);
    }

    // ==========================================
    // Matrix4f Allocations & Math
    // ==========================================

    public Matrix4f mat4() {
        return advanceSlot().m4.identity();
    }

    public Matrix4f mat4(Matrix4fc other) {
        Objects.requireNonNull(other, "matrix cannot be null");
        return advanceSlot().m4.set(other);
    }

    public Matrix4f mul(Matrix4fc a, Matrix4fc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return mat4(a).mul(b);
    }

    public Matrix4f mul(Matrix4fc a, Matrix4fc b, Matrix4f dest) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(a).mul(b);
    }

    public Matrix4f translate(Matrix4fc m, float x, float y, float z) {
        Objects.requireNonNull(m, "matrix cannot be null");
        return mat4(m).translate(x, y, z);
    }

    public Matrix4f rotate(Matrix4fc m, Quaternionfc q) {
        Objects.requireNonNull(m, "matrix cannot be null");
        Objects.requireNonNull(q, "quaternion cannot be null");
        return mat4(m).rotate(q);
    }

    public Matrix4f scale(Matrix4fc m, float sx, float sy, float sz) {
        Objects.requireNonNull(m, "matrix cannot be null");
        return mat4(m).scale(sx, sy, sz);
    }

    // ==========================================
    // Quaternionf Allocations & Math
    // ==========================================

    public Quaternionf quat() {
        return advanceSlot().quat.identity();
    }

    public Quaternionf quat(Quaternionfc other) {
        Objects.requireNonNull(other, "quaternion cannot be null");
        return advanceSlot().quat.set(other);
    }

    public Quaternionf quat(float x, float y, float z, float w) {
        return advanceSlot().quat.set(x, y, z, w);
    }

    public Quaternionf rotateZ(Quaternionfc q, float angleRadians) {
        Objects.requireNonNull(q, "quaternion cannot be null");
        return quat(q).rotateZ(angleRadians);
    }

    public Quaternionf rotateZ(Quaternionfc q, float angleRadians, Quaternionf dest) {
        Objects.requireNonNull(q, "quaternion cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(q).rotateZ(angleRadians);
    }

    public Quaternionf mul(Quaternionfc a, Quaternionfc b) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        return quat(a).mul(b);
    }

    public Quaternionf mul(Quaternionfc a, Quaternionfc b, Quaternionf dest) {
        Objects.requireNonNull(a, "a cannot be null");
        Objects.requireNonNull(b, "b cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        return dest.set(a).mul(b);
    }

    // ==========================================
    // Bounds & Point Allocations
    // ==========================================

    public MutableBounds bounds() {
        return advanceSlot().bounds.reset();
    }

    public MutablePoint point() {
        return advanceSlot().point.set(0.0, 0.0, 0.0);
    }

    public MutablePoint point(double x, double y, double z) {
        return advanceSlot().point.set(x, y, z);
    }

    public MutablePoint point(Point p) {
        Objects.requireNonNull(p, "point cannot be null");
        return advanceSlot().point.set(p);
    }

    // ==========================================
    // Billboard & Projection Helpers
    // ==========================================

    /**
     * Calculates the 3D corner offset of a billboard using the ring's pre-allocated slots.
     */
    public Vector3f cornerOffset(int corner, float halfWidth, float halfHeight,
                                float scaleX, float scaleY, Quaternionfc orientation) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("corner must be between 0 and 3");
        Objects.requireNonNull(orientation, "orientation cannot be null");
        float x = (corner % 2 == 0 ? -1.0F : 1.0F) * halfWidth * scaleX;
        float y = (corner < 2 ? -1.0F : 1.0F) * halfHeight * scaleY;
        return vec3(x, y, 0.0F).rotate(orientation);
    }

    /**
     * Calculates the 3D corner offset into the specified destination vector without consuming a slot.
     */
    public Vector3f cornerOffset(int corner, float halfWidth, float halfHeight,
                                float scaleX, float scaleY, Quaternionfc orientation, Vector3f dest) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("corner must be between 0 and 3");
        Objects.requireNonNull(orientation, "orientation cannot be null");
        Objects.requireNonNull(dest, "dest cannot be null");
        float x = (corner % 2 == 0 ? -1.0F : 1.0F) * halfWidth * scaleX;
        float y = (corner < 2 ? -1.0F : 1.0F) * halfHeight * scaleY;
        return dest.set(x, y, 0.0F).rotate(orientation);
    }

    /**
     * Projects a billboard rectangle into screen clip coordinates with zero heap allocation.
     * Uses internal scratch vectors to avoid consuming slot ring capacity.
     *
     * @return true if all 4 corners were successfully projected in front of the camera near clip
     */
    public boolean projectBillboard(double relativeX, double relativeY, double relativeZ,
                                    float halfWidth, float halfHeight, float scaleX, float scaleY,
                                    Quaternionfc orientation, Matrix4fc worldToClip,
                                    MutableBounds outBounds) {
        Objects.requireNonNull(orientation, "orientation cannot be null");
        Objects.requireNonNull(worldToClip, "worldToClip cannot be null");
        Objects.requireNonNull(outBounds, "outBounds cannot be null");

        if (!Double.isFinite(relativeX) || !Double.isFinite(relativeY) || !Double.isFinite(relativeZ)
                || !Float.isFinite(halfWidth) || !Float.isFinite(halfHeight)
                || !Float.isFinite(scaleX) || !Float.isFinite(scaleY)) {
            outBounds.reset();
            return false;
        }

        outBounds.reset();
        for (int corner = 0; corner < 4; corner++) {
            cornerOffset(corner, halfWidth, halfHeight, scaleX, scaleY, orientation, scratchV3);
            scratchV4.set((float) relativeX + scratchV3.x,
                    (float) relativeY + scratchV3.y,
                    (float) relativeZ + scratchV3.z, 1.0F);
            worldToClip.transform(scratchV4);
            if (scratchV4.w < NEAR_CLIP || !Float.isFinite(scratchV4.w)) {
                outBounds.reset();
                return false;
            }
            float x = scratchV4.x / scratchV4.w;
            float y = scratchV4.y / scratchV4.w;
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                outBounds.reset();
                return false;
            }
            outBounds.include(x, y);
        }
        return true;
    }

    /**
     * Uniform scale overload of {@link #projectBillboard}.
     */
    public boolean projectBillboard(double relativeX, double relativeY, double relativeZ,
                                    float halfWidth, float halfHeight, float scale,
                                    Quaternionfc orientation, Matrix4fc worldToClip,
                                    MutableBounds outBounds) {
        return projectBillboard(relativeX, relativeY, relativeZ, halfWidth, halfHeight, scale, scale,
                orientation, worldToClip, outBounds);
    }

    // ==========================================
    // Inner Classes
    // ==========================================

    /**
     * A pre-allocated geometric storage slot containing one instance of each common primitive.
     */
    public static final class Slot {
        private final int index;
        public final Vector3f v3 = new Vector3f();
        public final Vector4f v4 = new Vector4f();
        public final Matrix4f m4 = new Matrix4f();
        public final Quaternionf quat = new Quaternionf();
        public final MutableBounds bounds = new MutableBounds();
        public final MutablePoint point = new MutablePoint();

        Slot(int index) {
            this.index = index;
        }

        public int index() {
            return index;
        }

        public Slot reset() {
            v3.zero();
            v4.zero();
            m4.identity();
            quat.identity();
            bounds.reset();
            point.set(0.0, 0.0, 0.0);
            return this;
        }
    }

    /**
     * Mutable, reusable 2D screen coordinate bounding rectangle.
     */
    public static final class MutableBounds {
        public float minX = Float.POSITIVE_INFINITY;
        public float minY = Float.POSITIVE_INFINITY;
        public float maxX = Float.NEGATIVE_INFINITY;
        public float maxY = Float.NEGATIVE_INFINITY;
        public boolean valid = false;

        public MutableBounds reset() {
            this.minX = Float.POSITIVE_INFINITY;
            this.minY = Float.POSITIVE_INFINITY;
            this.maxX = Float.NEGATIVE_INFINITY;
            this.maxY = Float.NEGATIVE_INFINITY;
            this.valid = false;
            return this;
        }

        public MutableBounds include(float x, float y) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) return this;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            valid = true;
            return this;
        }

        public MutableBounds set(float minX, float minY, float maxX, float maxY) {
            if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(maxX) || !Float.isFinite(maxY)) {
                return reset();
            }
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
            this.valid = true;
            return this;
        }

        public MutableBounds set(BillboardProjection.Bounds other) {
            if (other == null) {
                return reset();
            }
            return set(other.minX(), other.minY(), other.maxX(), other.maxY());
        }

        public MutableBounds set(MutableBounds other) {
            Objects.requireNonNull(other, "other bounds cannot be null");
            this.minX = other.minX;
            this.minY = other.minY;
            this.maxX = other.maxX;
            this.maxY = other.maxY;
            this.valid = other.valid;
            return this;
        }

        public boolean intersectsScreen() {
            return valid && maxX >= -1.0F && minX <= 1.0F && maxY >= -1.0F && minY <= 1.0F;
        }

        public boolean contains(float x, float y) {
            return valid && x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        public double area() {
            if (!valid) return 0.0;
            return Math.max(0.0, (double) maxX - minX) * Math.max(0.0, (double) maxY - minY);
        }

        public double overlap(MutableBounds other) {
            if (!valid || other == null || !other.valid) return 0.0;
            return Math.max(0.0, Math.min(maxX, other.maxX) - Math.max(minX, other.minX))
                    * Math.max(0.0, Math.min(maxY, other.maxY) - Math.max(minY, other.minY));
        }

        public double overlap(BillboardProjection.Bounds other) {
            if (!valid || other == null) return 0.0;
            return Math.max(0.0, Math.min(maxX, other.maxX()) - Math.max(minX, other.minX()))
                    * Math.max(0.0, Math.min(maxY, other.maxY()) - Math.max(minY, other.minY()));
        }

        public BillboardProjection.Bounds toImmutable() {
            return valid ? new BillboardProjection.Bounds(minX, minY, maxX, maxY) : null;
        }

        public boolean isValid() {
            return valid;
        }
    }

    /**
     * Mutable 3D position holder avoiding heap allocation in coordinate math.
     */
    public static final class MutablePoint {
        public double x;
        public double y;
        public double z;

        public MutablePoint set(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        public MutablePoint set(Point p) {
            Objects.requireNonNull(p, "point cannot be null");
            this.x = p.x();
            this.y = p.y();
            this.z = p.z();
            return this;
        }

        public MutablePoint set(MutablePoint p) {
            Objects.requireNonNull(p, "point cannot be null");
            this.x = p.x;
            this.y = p.y;
            this.z = p.z;
            return this;
        }

        public MutablePoint add(double dx, double dy, double dz) {
            this.x += dx;
            this.y += dy;
            this.z += dz;
            return this;
        }

        public Point toImmutable() {
            return new Point(x, y, z);
        }
    }

    /**
     * AutoCloseable scope that automatically restores the ring buffer cursor upon exit.
     * All Scope instances are pre-allocated per ring to achieve zero heap allocation.
     */
    public final class Scope implements AutoCloseable {
        private final int depth;
        private int mark;
        private boolean active;

        Scope(int depth) {
            this.depth = depth;
        }

        public int depth() {
            return depth;
        }

        public int mark() {
            return mark;
        }

        public boolean isActive() {
            return active;
        }

        public GeometrySlotRing ring() {
            return GeometrySlotRing.this;
        }

        private void ensureActive() {
            if (!active) {
                throw new IllegalStateException("Scope at depth " + depth + " is already closed");
            }
        }

        public Slot nextSlot() {
            ensureActive();
            return GeometrySlotRing.this.nextSlot();
        }

        public Vector3f vec3() {
            ensureActive();
            return GeometrySlotRing.this.vec3();
        }

        public Vector3f vec3(float x, float y, float z) {
            ensureActive();
            return GeometrySlotRing.this.vec3(x, y, z);
        }

        public Vector3f vec3(Vector3fc other) {
            ensureActive();
            return GeometrySlotRing.this.vec3(other);
        }

        public Vector4f vec4() {
            ensureActive();
            return GeometrySlotRing.this.vec4();
        }

        public Vector4f vec4(float x, float y, float z, float w) {
            ensureActive();
            return GeometrySlotRing.this.vec4(x, y, z, w);
        }

        public Vector4f vec4(Vector3fc v, float w) {
            ensureActive();
            return GeometrySlotRing.this.vec4(v, w);
        }

        public Vector4f vec4(Vector4fc other) {
            ensureActive();
            return GeometrySlotRing.this.vec4(other);
        }

        public Matrix4f mat4() {
            ensureActive();
            return GeometrySlotRing.this.mat4();
        }

        public Matrix4f mat4(Matrix4fc other) {
            ensureActive();
            return GeometrySlotRing.this.mat4(other);
        }

        public Quaternionf quat() {
            ensureActive();
            return GeometrySlotRing.this.quat();
        }

        public Quaternionf quat(Quaternionfc other) {
            ensureActive();
            return GeometrySlotRing.this.quat(other);
        }

        public Quaternionf quat(float x, float y, float z, float w) {
            ensureActive();
            return GeometrySlotRing.this.quat(x, y, z, w);
        }

        public MutableBounds bounds() {
            ensureActive();
            return GeometrySlotRing.this.bounds();
        }

        public MutablePoint point() {
            ensureActive();
            return GeometrySlotRing.this.point();
        }

        public MutablePoint point(double x, double y, double z) {
            ensureActive();
            return GeometrySlotRing.this.point(x, y, z);
        }

        public MutablePoint point(Point p) {
            ensureActive();
            return GeometrySlotRing.this.point(p);
        }

        public Vector3f rotate(Vector3fc v, Quaternionfc q) {
            ensureActive();
            return GeometrySlotRing.this.rotate(v, q);
        }

        public Vector3f add(Vector3fc a, Vector3fc b) {
            ensureActive();
            return GeometrySlotRing.this.add(a, b);
        }

        public Vector3f sub(Vector3fc a, Vector3fc b) {
            ensureActive();
            return GeometrySlotRing.this.sub(a, b);
        }

        public Vector3f cross(Vector3fc a, Vector3fc b) {
            ensureActive();
            return GeometrySlotRing.this.cross(a, b);
        }

        public float dot(Vector3fc a, Vector3fc b) {
            ensureActive();
            return GeometrySlotRing.this.dot(a, b);
        }

        public Vector3f scale(Vector3fc v, float s) {
            ensureActive();
            return GeometrySlotRing.this.scale(v, s);
        }

        public Vector3f scale(Vector3fc v, float sx, float sy, float sz) {
            ensureActive();
            return GeometrySlotRing.this.scale(v, sx, sy, sz);
        }

        public Vector3f normalize(Vector3fc v) {
            ensureActive();
            return GeometrySlotRing.this.normalize(v);
        }

        public Vector3f lerp(Vector3fc a, Vector3fc b, float t) {
            ensureActive();
            return GeometrySlotRing.this.lerp(a, b, t);
        }

        public Vector4f transform(Matrix4fc mat, Vector4fc v) {
            ensureActive();
            return GeometrySlotRing.this.transform(mat, v);
        }

        public Vector4f transformPoint(Matrix4fc mat, float x, float y, float z) {
            ensureActive();
            return GeometrySlotRing.this.transformPoint(mat, x, y, z);
        }

        public Matrix4f mul(Matrix4fc a, Matrix4fc b) {
            ensureActive();
            return GeometrySlotRing.this.mul(a, b);
        }

        public Quaternionf rotateZ(Quaternionfc q, float angleRadians) {
            ensureActive();
            return GeometrySlotRing.this.rotateZ(q, angleRadians);
        }

        public Quaternionf mul(Quaternionfc a, Quaternionfc b) {
            ensureActive();
            return GeometrySlotRing.this.mul(a, b);
        }

        public boolean projectBillboard(double relativeX, double relativeY, double relativeZ,
                                        float halfWidth, float halfHeight, float scaleX, float scaleY,
                                        Quaternionfc orientation, Matrix4fc worldToClip,
                                        MutableBounds outBounds) {
            ensureActive();
            return GeometrySlotRing.this.projectBillboard(relativeX, relativeY, relativeZ,
                    halfWidth, halfHeight, scaleX, scaleY, orientation, worldToClip, outBounds);
        }

        @Override
        public void close() {
            if (!active) return;
            if (scopeDepth == 0 || scopePool[scopeDepth - 1] != this) {
                throw new IllegalStateException("Scope closed out of order at depth " + depth + ", current depth: " + scopeDepth);
            }
            active = false;
            scopeDepth--;
            GeometrySlotRing.this.cursor = this.mark;
        }
    }

    /**
     * Thrown when allocations within an active scope exceed the ring buffer's capacity.
     */
    public static class SlotRingOverflowException extends IllegalStateException {
        public SlotRingOverflowException(String message) {
            super(message);
        }
    }
}
