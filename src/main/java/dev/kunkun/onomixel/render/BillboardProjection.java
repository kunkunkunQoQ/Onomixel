package dev.kunkun.onomixel.render;

import org.joml.Matrix4fc;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.joml.Vector4f;

/** Projects an entire billboard rectangle, retaining text that is only partly inside the viewport. */
public final class BillboardProjection {
    private static final class Scratch {
        final Vector3f v3 = new Vector3f();
        final Vector4f v4 = new Vector4f();
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private BillboardProjection() {}

    /** Returns a billboard corner using the same scaled local geometry used by projection. */
    public static Vector3f cornerOffset(int corner, float halfWidth, float halfHeight,
                                        float scaleX, float scaleY, Quaternionfc orientation) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("corner must be between 0 and 3");
        if (orientation == null) throw new IllegalArgumentException("orientation cannot be null");
        return new Vector3f((corner % 2 == 0 ? -1 : 1) * halfWidth * scaleX,
                (corner < 2 ? -1 : 1) * halfHeight * scaleY, 0).rotate(orientation);
    }

    /** Calculates a billboard corner into the destination vector with zero heap allocation. */
    public static Vector3f cornerOffset(int corner, float halfWidth, float halfHeight,
                                        float scaleX, float scaleY, Quaternionfc orientation, Vector3f dest) {
        if (corner < 0 || corner > 3) throw new IllegalArgumentException("corner must be between 0 and 3");
        if (orientation == null) throw new IllegalArgumentException("orientation cannot be null");
        if (dest == null) throw new IllegalArgumentException("dest cannot be null");
        return dest.set((corner % 2 == 0 ? -1 : 1) * halfWidth * scaleX,
                (corner < 2 ? -1 : 1) * halfHeight * scaleY, 0).rotate(orientation);
    }

    public record Bounds(float minX, float minY, float maxX, float maxY) {
        public boolean intersectsScreen() { return maxX >= -1 && minX <= 1 && maxY >= -1 && minY <= 1; }
        public double area() { return Math.max(0, maxX - minX) * Math.max(0, maxY - minY); }
        public double overlap(Bounds other) {
            return Math.max(0, Math.min(maxX, other.maxX) - Math.max(minX, other.minX))
                    * Math.max(0, Math.min(maxY, other.maxY) - Math.max(minY, other.minY));
        }
        public boolean contains(float x, float y) { return x >= minX && x <= maxX && y >= minY && y <= maxY; }
    }

    public static boolean intersectsScreen(double relativeX, double relativeY, double relativeZ,
                                           float halfWidth, float halfHeight, float scale,
                                           Quaternionfc orientation, Matrix4fc worldToClip) {
        return intersectsScreen(relativeX, relativeY, relativeZ, halfWidth, halfHeight, scale, scale,
                orientation, worldToClip);
    }

    public static boolean intersectsScreen(double relativeX, double relativeY, double relativeZ,
                                           float halfWidth, float halfHeight, float scaleX, float scaleY,
                                           Quaternionfc orientation, Matrix4fc worldToClip) {
        Bounds bounds = project(relativeX, relativeY, relativeZ, halfWidth, halfHeight, scaleX, scaleY,
                orientation, worldToClip);
        return bounds != null && bounds.intersectsScreen();
    }

    public static Bounds project(double relativeX, double relativeY, double relativeZ,
                                 float halfWidth, float halfHeight, float scale,
                                 Quaternionfc orientation, Matrix4fc worldToClip) {
        return project(relativeX, relativeY, relativeZ, halfWidth, halfHeight, scale, scale,
                orientation, worldToClip);
    }

    public static Bounds project(double relativeX, double relativeY, double relativeZ,
                                 float halfWidth, float halfHeight, float scaleX, float scaleY,
                                 Quaternionfc orientation, Matrix4fc worldToClip) {
        Scratch s = SCRATCH.get();
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (int corner = 0; corner < 4; corner++) {
            cornerOffset(corner, halfWidth, halfHeight, scaleX, scaleY, orientation, s.v3);
            s.v4.set((float) relativeX + s.v3.x,
                    (float) relativeY + s.v3.y, (float) relativeZ + s.v3.z, 1.0F);
            worldToClip.transform(s.v4);
            if (s.v4.w < 0.05F || !Float.isFinite(s.v4.w)) return null;
            float x = s.v4.x / s.v4.w;
            float y = s.v4.y / s.v4.w;
            if (!Float.isFinite(x) || !Float.isFinite(y)) return null;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        return new Bounds(minX, minY, maxX, maxY);
    }
}
