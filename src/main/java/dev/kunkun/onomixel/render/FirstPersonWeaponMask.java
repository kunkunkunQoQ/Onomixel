package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.render.CrosshairComfortZone.Vector2;

/**
 * Geometric protection zone covering the first-person hand/weapon viewport region.
 * <p>
 * Implements Chapter 10, Section 1 of the Onomixel design specification:
 * <ul>
 *   <li>When the player is in first-person view, the weapon model (sword, bow, shield, tool)
 *       occupies the lower viewport (typically lower-right for right-handed players).</li>
 *   <li>If 3D floating text projects onto this region, it causes visual clipping and distraction.</li>
 *   <li>This class detects text points and bounding boxes inside the weapon silhouette mask
 *       and computes a smooth avoidance displacement (towards top-left for right-handed players,
 *       or towards top-right for left-handed players).</li>
 *   <li>When the player is not in first-person view, the mask is deactivated (zero displacement).</li>
 * </ul>
 */
public final class FirstPersonWeaponMask {
    public static final double DEFAULT_TAU = 0.115; // 115ms time constant

    public static final double DEFAULT_MAX_DISPLACEMENT_NDC = 0.35;
    public static final double DEFAULT_BASE_DISPLACEMENT_NDC = 0.08;
    public static final double DEFAULT_BUFFER_MARGIN_NDC = 0.04;

    public static final double DEFAULT_MAX_DISPLACEMENT_PIXELS = 120.0;
    public static final double DEFAULT_BASE_DISPLACEMENT_PIXELS = 30.0;
    public static final double DEFAULT_BUFFER_MARGIN_PIXELS = 15.0;

    public static final double DEFAULT_MAX_DISPLACEMENT_NORM = 0.20;
    public static final double DEFAULT_BASE_DISPLACEMENT_NORM = 0.05;
    public static final double DEFAULT_BUFFER_MARGIN_NORM = 0.02;

    public enum CoordinateSpace {
        /**
         * Normalized Device Coordinates: X in [-1, 1] (left to right),
         * Y in [-1, 1] (bottom to top). Screen center is (0, 0).
         */
        NDC,
        /** Screen pixel coordinates: X in [0, width], Y in [0, height] (top to bottom). */
        SCREEN_PIXELS,
        /** Normalized screen coordinates: X in [0, 1], Y in [0, 1] (top to bottom). */
        NORMALIZED_SCREEN
    }

    public enum HandSide {
        RIGHT,
        LEFT
    }

    public enum WeaponState {
        DEFAULT,
        BOW_AIMING,
        SHIELD_BLOCKING
    }

    /**
     * Bounding box defining the first-person hand/weapon occlusion zone.
     * <p>
     * <b>Coordinate System:</b> Depends on the configured {@link CoordinateSpace}:
     * <ul>
     *   <li>{@link CoordinateSpace#NDC}: Coordinates in Normalized Device Coordinates [-1.0, 1.0],
     *       where (-1, -1) is bottom-left, (1, 1) is top-right, and (0, 0) is screen center.</li>
     *   <li>{@link CoordinateSpace#SCREEN_PIXELS}: Coordinates in Minecraft UI scaled pixels [0, width] x [0, height],
     *       where (0, 0) is top-left and (width, height) is bottom-right.</li>
     *   <li>{@link CoordinateSpace#NORMALIZED_SCREEN}: Coordinates in normalized screen space [0.0, 1.0],
     *       where (0, 0) is top-left and (1, 1) is bottom-right.</li>
     * </ul>
     *
     * @param minX Minimum horizontal coordinate along the X-axis (left boundary)
     * @param minY Minimum vertical coordinate along the Y-axis (top boundary in pixels, bottom in NDC)
     * @param maxX Maximum horizontal coordinate along the X-axis (right boundary)
     * @param maxY Maximum vertical coordinate along the Y-axis (bottom boundary in pixels, top in NDC)
     */
    public record MaskBounds(double minX, double minY, double maxX, double maxY) {
        public MaskBounds {
            if (!Double.isFinite(minX) || !Double.isFinite(minY)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY)) {
                throw new IllegalArgumentException("MaskBounds coordinates must be finite: ["
                        + minX + ", " + minY + ", " + maxX + ", " + maxY + "]");
            }
            if (minX > maxX || minY > maxY) {
                throw new IllegalArgumentException("MaskBounds min must be <= max: ["
                        + minX + ", " + minY + "] to [" + maxX + ", " + maxY + "]");
            }
        }

        public boolean contains(double x, double y) {
            if (!Double.isFinite(x) || !Double.isFinite(y)) return false;
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }

        public boolean intersects(double oMinX, double oMinY, double oMaxX, double oMaxY) {
            if (!Double.isFinite(oMinX) || !Double.isFinite(oMinY) || !Double.isFinite(oMaxX) || !Double.isFinite(oMaxY)) {
                return false;
            }
            return maxX >= oMinX && minX <= oMaxX && maxY >= oMinY && minY <= oMaxY;
        }

        public boolean intersects(BillboardProjection.Bounds bounds) {
            if (bounds == null) return false;
            return intersects(bounds.minX(), bounds.minY(), bounds.maxX(), bounds.maxY());
        }

        public double width() { return maxX - minX; }
        public double height() { return maxY - minY; }
    }

    public record AvoidanceResult(Vector2 displacement, boolean insideMask, double penetrationFactor) {
        public AvoidanceResult {
            if (displacement == null) {
                throw new IllegalArgumentException("displacement must not be null");
            }
            if (!Double.isFinite(penetrationFactor) || penetrationFactor < 0.0 || penetrationFactor > 1.0) {
                throw new IllegalArgumentException("penetrationFactor must be within [0, 1]: " + penetrationFactor);
            }
        }

        public boolean hasDisplacement() {
            return !displacement.isZero();
        }
    }

    private final boolean isFirstPerson;
    private final HandSide handSide;
    private final WeaponState weaponState;
    private final CoordinateSpace coordinateSpace;
    private final MaskBounds bounds;
    private final double maxDisplacement;
    private final double baseDisplacement;
    private final double bufferMargin;
    private final Vector2 avoidanceDirection;

    public FirstPersonWeaponMask(boolean isFirstPerson, HandSide handSide, WeaponState weaponState,
                                 CoordinateSpace coordinateSpace, MaskBounds bounds,
                                 double maxDisplacement, double baseDisplacement, double bufferMargin) {
        if (handSide == null) throw new IllegalArgumentException("handSide must not be null");
        if (weaponState == null) throw new IllegalArgumentException("weaponState must not be null");
        if (coordinateSpace == null) throw new IllegalArgumentException("coordinateSpace must not be null");
        if (bounds == null) throw new IllegalArgumentException("bounds must not be null");
        if (!Double.isFinite(maxDisplacement) || maxDisplacement <= 0.0) {
            throw new IllegalArgumentException("maxDisplacement must be positive: " + maxDisplacement);
        }
        if (!Double.isFinite(baseDisplacement) || baseDisplacement < 0.0 || baseDisplacement > maxDisplacement) {
            throw new IllegalArgumentException("baseDisplacement must be within [0, maxDisplacement]: " + baseDisplacement);
        }
        if (!Double.isFinite(bufferMargin) || bufferMargin < 0.0) {
            throw new IllegalArgumentException("bufferMargin must be non-negative: " + bufferMargin);
        }

        this.isFirstPerson = isFirstPerson;
        this.handSide = handSide;
        this.weaponState = weaponState;
        this.coordinateSpace = coordinateSpace;
        this.bounds = bounds;
        this.maxDisplacement = maxDisplacement;
        this.baseDisplacement = baseDisplacement;
        this.bufferMargin = bufferMargin;
        this.avoidanceDirection = computeAvoidanceDirection(handSide, coordinateSpace);
    }

    private static Vector2 computeAvoidanceDirection(HandSide handSide, CoordinateSpace space) {
        double sqrt2 = Math.sqrt(2.0);
        return switch (space) {
            case NDC -> switch (handSide) {
                // Towards top-left: dx < 0 (left), dy > 0 (top)
                case RIGHT -> new Vector2(-1.0 / sqrt2, 1.0 / sqrt2);
                // Towards top-right: dx > 0 (right), dy > 0 (top)
                case LEFT -> new Vector2(1.0 / sqrt2, 1.0 / sqrt2);
            };
            case SCREEN_PIXELS, NORMALIZED_SCREEN -> switch (handSide) {
                // Towards top-left: dx < 0 (left), dy < 0 (top)
                case RIGHT -> new Vector2(-1.0 / sqrt2, -1.0 / sqrt2);
                // Towards top-right: dx > 0 (right), dy < 0 (top)
                case LEFT -> new Vector2(1.0 / sqrt2, -1.0 / sqrt2);
            };
        };
    }

    public static MaskBounds defaultNdcBounds(HandSide handSide, WeaponState state) {
        return switch (handSide) {
            case RIGHT -> switch (state) {
                case DEFAULT -> new MaskBounds(0.20, -1.05, 1.05, -0.15);
                case BOW_AIMING -> new MaskBounds(0.10, -1.05, 1.05, 0.05);
                case SHIELD_BLOCKING -> new MaskBounds(-0.10, -1.05, 1.05, 0.00);
            };
            case LEFT -> switch (state) {
                case DEFAULT -> new MaskBounds(-1.05, -1.05, -0.20, -0.15);
                case BOW_AIMING -> new MaskBounds(-1.05, -1.05, -0.10, 0.05);
                case SHIELD_BLOCKING -> new MaskBounds(-1.05, -1.05, 0.10, 0.00);
            };
        };
    }

    public static MaskBounds defaultNormalizedScreenBounds(HandSide handSide, WeaponState state) {
        return switch (handSide) {
            case RIGHT -> switch (state) {
                case DEFAULT -> new MaskBounds(0.55, 0.50, 1.05, 1.05);
                case BOW_AIMING -> new MaskBounds(0.45, 0.35, 1.05, 1.05);
                case SHIELD_BLOCKING -> new MaskBounds(0.35, 0.40, 1.05, 1.05);
            };
            case LEFT -> switch (state) {
                case DEFAULT -> new MaskBounds(-0.05, 0.50, 0.45, 1.05);
                case BOW_AIMING -> new MaskBounds(-0.05, 0.35, 0.55, 1.05);
                case SHIELD_BLOCKING -> new MaskBounds(-0.05, 0.40, 0.65, 1.05);
            };
        };
    }

    public static MaskBounds defaultScreenPixelBounds(int width, int height, HandSide handSide, WeaponState state) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Screen dimensions must be positive: " + width + "x" + height);
        }
        MaskBounds norm = defaultNormalizedScreenBounds(handSide, state);
        return new MaskBounds(norm.minX() * width, norm.minY() * height,
                norm.maxX() * width, norm.maxY() * height);
    }

    public static FirstPersonWeaponMask createNdc(boolean isFirstPerson, HandSide handSide, WeaponState state) {
        MaskBounds b = defaultNdcBounds(handSide, state);
        return new FirstPersonWeaponMask(isFirstPerson, handSide, state, CoordinateSpace.NDC, b,
                DEFAULT_MAX_DISPLACEMENT_NDC, DEFAULT_BASE_DISPLACEMENT_NDC, DEFAULT_BUFFER_MARGIN_NDC);
    }

    public static FirstPersonWeaponMask createNormalizedScreen(boolean isFirstPerson, HandSide handSide, WeaponState state) {
        MaskBounds b = defaultNormalizedScreenBounds(handSide, state);
        return new FirstPersonWeaponMask(isFirstPerson, handSide, state, CoordinateSpace.NORMALIZED_SCREEN, b,
                DEFAULT_MAX_DISPLACEMENT_NORM, DEFAULT_BASE_DISPLACEMENT_NORM, DEFAULT_BUFFER_MARGIN_NORM);
    }

    public static FirstPersonWeaponMask createScreenPixels(int width, int height, boolean isFirstPerson,
                                                           HandSide handSide, WeaponState state) {
        MaskBounds b = defaultScreenPixelBounds(width, height, handSide, state);
        return new FirstPersonWeaponMask(isFirstPerson, handSide, state, CoordinateSpace.SCREEN_PIXELS, b,
                DEFAULT_MAX_DISPLACEMENT_PIXELS, DEFAULT_BASE_DISPLACEMENT_PIXELS, DEFAULT_BUFFER_MARGIN_PIXELS);
    }

    public static FirstPersonWeaponMask standard() {
        return createNdc(true, HandSide.RIGHT, WeaponState.DEFAULT);
    }

    public boolean isFirstPerson() { return isFirstPerson; }
    public HandSide handSide() { return handSide; }
    public WeaponState weaponState() { return weaponState; }
    public CoordinateSpace coordinateSpace() { return coordinateSpace; }
    public MaskBounds bounds() { return bounds; }
    public double maxDisplacement() { return maxDisplacement; }
    public double baseDisplacement() { return baseDisplacement; }
    public double bufferMargin() { return bufferMargin; }
    public Vector2 avoidanceDirection() { return avoidanceDirection; }

    public boolean contains(double x, double y) {
        return isFirstPerson && bounds.contains(x, y);
    }

    public boolean intersects(BillboardProjection.Bounds otherBounds) {
        return isFirstPerson && bounds.intersects(otherBounds);
    }

    public boolean intersects(double minX, double minY, double maxX, double maxY) {
        return isFirstPerson && bounds.intersects(minX, minY, maxX, maxY);
    }

    /**
     * Evaluates avoidance for a single point (e.g. text center).
     */
    public AvoidanceResult evaluate(double x, double y) {
        if (!isFirstPerson || !Double.isFinite(x) || !Double.isFinite(y) || !bounds.contains(x, y)) {
            return new AvoidanceResult(Vector2.ZERO, false, 0.0);
        }

        double dx = 0, dy = 0;
        switch (coordinateSpace) {
            case NDC -> {
                if (handSide == HandSide.RIGHT) {
                    dx = x - bounds.minX();
                    dy = bounds.maxY() - y;
                } else {
                    dx = bounds.maxX() - x;
                    dy = bounds.maxY() - y;
                }
            }
            case SCREEN_PIXELS, NORMALIZED_SCREEN -> {
                if (handSide == HandSide.RIGHT) {
                    dx = x - bounds.minX();
                    dy = y - bounds.minY();
                } else {
                    dx = bounds.maxX() - x;
                    dy = y - bounds.minY();
                }
            }
        }

        double width = Math.max(1e-6, bounds.width());
        double height = Math.max(1e-6, bounds.height());
        double px = Math.clamp(dx / width, 0.0, 1.0);
        double py = Math.clamp(dy / height, 0.0, 1.0);
        double penetrationFactor = Math.max(px, py);

        // Required distance along avoidance vector to clear the mask boundary
        double ux = Math.abs(avoidanceDirection.x());
        double uy = Math.abs(avoidanceDirection.y());
        double sx = (ux > 1e-6) ? (dx / ux) : 0.0;
        double sy = (uy > 1e-6) ? (dy / uy) : 0.0;
        double requiredClearance = Math.max(sx, sy) + bufferMargin;

        double magnitude = Math.min(maxDisplacement, Math.max(baseDisplacement, requiredClearance));
        Vector2 displacement = avoidanceDirection.multiply(magnitude);
        return new AvoidanceResult(displacement, true, penetrationFactor);
    }

    public Vector2 calculateDisplacement(double x, double y) {
        return evaluate(x, y).displacement();
    }

    public Vector2 calculateDisplacement(Vector2 point) {
        if (point == null) return Vector2.ZERO;
        return calculateDisplacement(point.x(), point.y());
    }

    /**
     * Evaluates avoidance for a projected bounding box.
     */
    public AvoidanceResult evaluate(BillboardProjection.Bounds other) {
        if (!isFirstPerson || other == null
                || !Float.isFinite(other.minX()) || !Float.isFinite(other.minY())
                || !Float.isFinite(other.maxX()) || !Float.isFinite(other.maxY())
                || !bounds.intersects(other)) {
            return new AvoidanceResult(Vector2.ZERO, false, 0.0);
        }

        double dx = 0, dy = 0;
        switch (coordinateSpace) {
            case NDC -> {
                if (handSide == HandSide.RIGHT) {
                    dx = other.maxX() - bounds.minX();
                    dy = bounds.maxY() - other.minY();
                } else {
                    dx = bounds.maxX() - other.minX();
                    dy = bounds.maxY() - other.minY();
                }
            }
            case SCREEN_PIXELS, NORMALIZED_SCREEN -> {
                if (handSide == HandSide.RIGHT) {
                    dx = other.maxX() - bounds.minX();
                    dy = other.maxY() - bounds.minY();
                } else {
                    dx = bounds.maxX() - other.minX();
                    dy = other.maxY() - bounds.minY();
                }
            }
        }

        double width = Math.max(1e-6, bounds.width());
        double height = Math.max(1e-6, bounds.height());
        double px = Math.clamp(Math.max(0.0, dx) / width, 0.0, 1.0);
        double py = Math.clamp(Math.max(0.0, dy) / height, 0.0, 1.0);
        double penetrationFactor = Math.max(px, py);

        double ux = Math.abs(avoidanceDirection.x());
        double uy = Math.abs(avoidanceDirection.y());
        double sx = (ux > 1e-6) ? (Math.max(0.0, dx) / ux) : 0.0;
        double sy = (uy > 1e-6) ? (Math.max(0.0, dy) / uy) : 0.0;
        double requiredClearance = Math.max(sx, sy) + bufferMargin;

        double magnitude = Math.min(maxDisplacement, Math.max(baseDisplacement, requiredClearance));
        Vector2 displacement = avoidanceDirection.multiply(magnitude);
        return new AvoidanceResult(displacement, true, penetrationFactor);
    }

    public Vector2 calculateDisplacement(BillboardProjection.Bounds other) {
        return evaluate(other).displacement();
    }

    public BillboardProjection.Bounds displace(BillboardProjection.Bounds other) {
        if (other == null) return null;
        Vector2 offset = calculateDisplacement(other);
        if (offset.isZero()) return other;
        return new BillboardProjection.Bounds(
                (float) (other.minX() + offset.x()),
                (float) (other.minY() + offset.y()),
                (float) (other.maxX() + offset.x()),
                (float) (other.maxY() + offset.y())
        );
    }

    /** Smooth exponential interpolation towards target offset over time. */
    public static Vector2 advance(Vector2 current, Vector2 target, double seconds) {
        return advance(current, target, seconds, DEFAULT_TAU);
    }

    /** Smooth exponential interpolation towards target offset with custom time constant tau. */
    public static Vector2 advance(Vector2 current, Vector2 target, double seconds, double tau) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) return current;
        if (current == null) return target != null ? target : Vector2.ZERO;
        if (target == null) return current;
        double dt = Math.min(seconds, 0.10);
        double safeTau = (Double.isFinite(tau) && tau > 1e-6) ? tau : DEFAULT_TAU;
        double fraction = 1.0 - Math.exp(-dt / safeTau);
        return current.plus(target.minus(current).multiply(fraction));
    }
}
