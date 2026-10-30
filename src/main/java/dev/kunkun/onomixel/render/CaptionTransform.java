package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.subtitle.CaptionPlacement.Point;
import dev.kunkun.onomixel.subtitle.CaptionVisuals;
import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Quaternionfc;
import org.joml.Vector3f;

/** One resolved frame transform shared by projection, visibility rays and submission. */
public record CaptionTransform(Point point, CaptionVisuals.Effect effect, float baseScale,
                               float halfWidth, float halfHeight, float starburstAlpha, boolean hitstop) {

    public CaptionTransform(Point point, CaptionVisuals.Effect effect, float baseScale,
                            float halfWidth, float halfHeight) {
        this(point, effect, baseScale, halfWidth, halfHeight, 0.0F, false);
    }

    public CaptionTransform(Point point, CaptionVisuals.Effect effect, float baseScale,
                            float halfWidth, float halfHeight, float starburstAlpha) {
        this(point, effect, baseScale, halfWidth, halfHeight, starburstAlpha, false);
    }
    public float scaleX() { return baseScale * effect.scaleX(); }
    public float scaleY() { return baseScale * effect.scaleY(); }

    public Quaternionf orientation(Quaternionfc camera) {
        return new Quaternionf(camera).rotateZ((float) Math.toRadians(effect.tiltDegrees()));
    }

    public Quaternionf orientation(Quaternionfc camera, Quaternionf dest) {
        if (dest == null) throw new IllegalArgumentException("dest cannot be null");
        return dest.set(camera).rotateZ((float) Math.toRadians(effect.tiltDegrees()));
    }

    public Vector3f cornerOffset(int corner, Quaternionfc camera) {
        return BillboardProjection.cornerOffset(corner, halfWidth, halfHeight, scaleX(), scaleY(), orientation(camera));
    }

    public Vector3f cornerOffset(int corner, Quaternionfc camera, Vector3f dest) {
        GeometrySlotRing ring = GeometrySlotRing.current();
        Quaternionf q = ring.quat();
        orientation(camera, q);
        return BillboardProjection.cornerOffset(corner, halfWidth, halfHeight, scaleX(), scaleY(), q, dest);
    }

    public BillboardProjection.Bounds project(Point eye, Quaternionfc camera, Matrix4fc projection) {
        return BillboardProjection.project(point.x() - eye.x(), point.y() - eye.y(), point.z() - eye.z(),
                halfWidth, halfHeight, scaleX(), scaleY(), orientation(camera), projection);
    }

    public boolean project(Point eye, Quaternionfc camera, Matrix4fc projection, GeometrySlotRing.MutableBounds dest) {
        GeometrySlotRing ring = GeometrySlotRing.current();
        Quaternionf q = ring.quat();
        orientation(camera, q);
        return ring.projectBillboard(point.x() - eye.x(), point.y() - eye.y(), point.z() - eye.z(),
                halfWidth, halfHeight, scaleX(), scaleY(), q, projection, dest);
    }
}
