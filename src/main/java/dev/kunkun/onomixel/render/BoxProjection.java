package dev.kunkun.onomixel.render;

import org.joml.Matrix4fc;
import org.joml.Vector4f;


/** Projects a source AABB, clipping its edges when the camera is close to or inside its silhouette. */
public final class BoxProjection {
    private static final float NEAR = 0.05F; // Locked Camera.setupPerspective near distance.

    private static final class Scratch {
        final Vector4f[] corners = new Vector4f[8];
        final Vector4f[] clipped = new Vector4f[20];
        int clippedCount = 0;

        Scratch() {
            for (int i = 0; i < 8; i++) corners[i] = new Vector4f();
            for (int i = 0; i < 20; i++) clipped[i] = new Vector4f();
        }
    }
    private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

    private BoxProjection() {}

    public static BillboardProjection.Bounds project(double minX, double minY, double minZ,
                                                       double maxX, double maxY, double maxZ,
                                                       Matrix4fc worldToClip) {
        Scratch s = SCRATCH.get();
        s.clippedCount = 0;
        for (int i = 0; i < 8; i++) {
            Vector4f clip = s.corners[i];
            clip.set((float) ((i & 1) == 0 ? minX : maxX),
                    (float) ((i & 2) == 0 ? minY : maxY),
                    (float) ((i & 4) == 0 ? minZ : maxZ), 1.0F);
            worldToClip.transform(clip);
            if (!clip.isFinite()) return null;
            if (clip.w >= NEAR) {
                s.clipped[s.clippedCount++].set(clip);
            }
        }
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                int other = i ^ bit;
                if (other <= i) continue;
                Vector4f a = s.corners[i], b = s.corners[other];
                if ((a.w >= NEAR) == (b.w >= NEAR)) continue;
                float fraction = (NEAR - a.w) / (b.w - a.w);
                if (s.clippedCount < s.clipped.length) {
                    s.clipped[s.clippedCount++].set(a).lerp(b, fraction);
                }
            }
        }
        if (s.clippedCount == 0) return null;
        float left = Float.POSITIVE_INFINITY, bottom = Float.POSITIVE_INFINITY;
        float right = Float.NEGATIVE_INFINITY, top = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < s.clippedCount; i++) {
            Vector4f clip = s.clipped[i];
            float x = clip.x / clip.w, y = clip.y / clip.w;
            left = Math.min(left, x); right = Math.max(right, x);
            bottom = Math.min(bottom, y); top = Math.max(top, y);
        }
        return new BillboardProjection.Bounds(left, bottom, right, top);
    }
}
