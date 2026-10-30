package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.subtitle.VisualType;

/** Conservative half extents for caption rectangles under a declared motion envelope. */
public final class CaptionGeometry {
    private CaptionGeometry() {}

    public record HalfSize(float halfWidth, float halfHeight) {
        public HalfSize {
            if (!Float.isFinite(halfWidth) || halfWidth < 0.0F
                    || !Float.isFinite(halfHeight) || halfHeight < 0.0F) {
                throw new IllegalArgumentException("Caption half extents must be finite and non-negative");
            }
        }
    }

    /** Reserves anisotropic scale and the full signed tilt range using a conservative rotation bound. */
    public static HalfSize reserve(float halfWidth, float halfHeight, VisualType.MotionEnvelope envelope) {
        if (!Float.isFinite(halfWidth) || halfWidth < 0.0F
                || !Float.isFinite(halfHeight) || halfHeight < 0.0F) {
            throw new IllegalArgumentException("Caption half extents must be finite and non-negative");
        }
        if (envelope == null) envelope = VisualType.DEFAULT_MOTION_ENVELOPE;

        double scaledHalfWidth = (double) halfWidth * envelope.maxScaleX();
        double scaledHalfHeight = (double) halfHeight * envelope.maxScaleY();
        double sinTilt = Math.sin(Math.toRadians(envelope.maxTiltDegrees()));
        float reservedHalfWidth = (float) (scaledHalfWidth + scaledHalfHeight * sinTilt);
        float reservedHalfHeight = (float) (scaledHalfHeight + scaledHalfWidth * sinTilt);
        return new HalfSize(reservedHalfWidth, reservedHalfHeight);
    }
}
