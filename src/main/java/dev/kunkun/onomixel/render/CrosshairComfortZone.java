package dev.kunkun.onomixel.render;

/**
 * Geometric protection zone centered at the screen crosshair (reticle).
 * <p>
 * Implements Chapter 10, Section 2 of the Onomixel design specification:
 * <ul>
 *   <li>Core Exclusion Zone (e.g. radius 40px): text centers must not enter this zone to prevent
 *       obstructing player aiming during archery or combat. Any point inside is completely repelled outward.</li>
 *   <li>Transition Buffer Ring (e.g. radius 40px ~ 80px): text experiences a smooth outward radial
 *       centrifugal force {@code F_crosshair = k_repel * ((80 - r) / 40) * r_hat}, easing out to zero
 *       at the outer boundary.</li>
 *   <li>Outside Zone (r &gt;= 80px): zero force or displacement.</li>
 *   <li>Singularity Handling (r == 0): deterministic displacement along a designated unit vector,
 *       preventing NaN or zero-division while ensuring complete clearance of the core zone.</li>
 * </ul>
 */
public final class CrosshairComfortZone {
    public static final double DEFAULT_INNER_RADIUS = 40.0;
    public static final double DEFAULT_OUTER_RADIUS = 80.0;
    public static final double DEFAULT_REPEL_STRENGTH = 20.0;
    public static final double DEFAULT_TAU = 0.115; // 115ms time constant, consistent with CaptionRepulsion
    public static final Vector2 DEFAULT_SINGULARITY_DIRECTION = new Vector2(0.0, 1.0);

    /** Immutable 2D vector and offset representation. */
    public record Vector2(double x, double y) {
        public static final Vector2 ZERO = new Vector2(0.0, 0.0);

        public Vector2 {
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                throw new IllegalArgumentException("Vector2 coordinates must be finite: (" + x + ", " + y + ")");
            }
        }

        public double lengthSquared() {
            return x * x + y * y;
        }

        public double length() {
            return Math.sqrt(x * x + y * y);
        }

        public boolean isZero() {
            return x == 0.0 && y == 0.0;
        }

        public Vector2 plus(Vector2 other) {
            java.util.Objects.requireNonNull(other, "other vector cannot be null");
            return new Vector2(x + other.x, y + other.y);
        }

        public Vector2 plus(double dx, double dy) {
            return new Vector2(x + dx, y + dy);
        }

        public Vector2 minus(Vector2 other) {
            java.util.Objects.requireNonNull(other, "other vector cannot be null");
            return new Vector2(x - other.x, y - other.y);
        }

        public Vector2 multiply(double scalar) {
            if (!Double.isFinite(scalar)) {
                throw new IllegalArgumentException("Scalar must be finite: " + scalar);
            }
            return new Vector2(x * scalar, y * scalar);
        }

        public Vector2 normalize() {
            double len = length();
            if (len < 1e-12) {
                return ZERO;
            }
            return new Vector2(x / len, y / len);
        }

        public double distance(Vector2 other) {
            java.util.Objects.requireNonNull(other, "other vector cannot be null");
            return minus(other).length();
        }

        public double distanceSquared(Vector2 other) {
            java.util.Objects.requireNonNull(other, "other vector cannot be null");
            return minus(other).lengthSquared();
        }

        public double dot(Vector2 other) {
            java.util.Objects.requireNonNull(other, "other vector cannot be null");
            return x * other.x + y * other.y;
        }
    }

    public enum Zone {
        CORE,
        BUFFER,
        OUTSIDE
    }

    public record RepulsionResult(Vector2 displacement, double rawDistance, Zone zone) {
        public RepulsionResult {
            if (displacement == null || zone == null) {
                throw new IllegalArgumentException("displacement and zone must not be null");
            }
            if (!Double.isFinite(rawDistance) || rawDistance < 0.0) {
                throw new IllegalArgumentException("rawDistance must be finite and non-negative: " + rawDistance);
            }
        }

        public boolean hasDisplacement() {
            return !displacement.isZero();
        }
    }

    private final Vector2 center;
    private final double innerRadius;
    private final double outerRadius;
    private final double repelStrength;
    private final Vector2 singularityDirection;

    public CrosshairComfortZone(Vector2 center, double innerRadius, double outerRadius,
                                double repelStrength, Vector2 singularityDirection) {
        if (center == null) {
            throw new IllegalArgumentException("center must not be null");
        }
        if (!Double.isFinite(innerRadius) || innerRadius <= 0.0) {
            throw new IllegalArgumentException("innerRadius must be finite and positive: " + innerRadius);
        }
        if (!Double.isFinite(outerRadius) || outerRadius <= innerRadius) {
            throw new IllegalArgumentException("outerRadius must be greater than innerRadius: inner="
                    + innerRadius + ", outer=" + outerRadius);
        }
        if (!Double.isFinite(repelStrength) || repelStrength < 0.0) {
            throw new IllegalArgumentException("repelStrength must be finite and non-negative: " + repelStrength);
        }
        if (singularityDirection == null || singularityDirection.isZero()) {
            throw new IllegalArgumentException("singularityDirection must be non-null and non-zero");
        }
        this.center = center;
        this.innerRadius = innerRadius;
        this.outerRadius = outerRadius;
        this.repelStrength = repelStrength;
        this.singularityDirection = singularityDirection.normalize();
    }

    public static CrosshairComfortZone standard() {
        return new CrosshairComfortZone(Vector2.ZERO, DEFAULT_INNER_RADIUS, DEFAULT_OUTER_RADIUS,
                DEFAULT_REPEL_STRENGTH, DEFAULT_SINGULARITY_DIRECTION);
    }

    public static CrosshairComfortZone of(double innerRadius, double outerRadius) {
        return new CrosshairComfortZone(Vector2.ZERO, innerRadius, outerRadius,
                DEFAULT_REPEL_STRENGTH, DEFAULT_SINGULARITY_DIRECTION);
    }

    public static CrosshairComfortZone of(double cx, double cy, double innerRadius, double outerRadius, double repelStrength) {
        return new CrosshairComfortZone(new Vector2(cx, cy), innerRadius, outerRadius,
                repelStrength, DEFAULT_SINGULARITY_DIRECTION);
    }

    public static CrosshairComfortZone forViewportNdc(int width, int height, double innerRadiusPx, double outerRadiusPx, double repelStrengthPx) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Viewport dimensions must be positive");
        }
        // In NDC: width spans 2.0 (-1 to 1), height spans 2.0 (-1 to 1).
        // Use isotropic scale based on height.
        double pxToNdc = 2.0 / height;
        return new CrosshairComfortZone(Vector2.ZERO, innerRadiusPx * pxToNdc, outerRadiusPx * pxToNdc,
                repelStrengthPx * pxToNdc, DEFAULT_SINGULARITY_DIRECTION);
    }

    public Vector2 center() { return center; }
    public double innerRadius() { return innerRadius; }
    public double outerRadius() { return outerRadius; }
    public double repelStrength() { return repelStrength; }
    public Vector2 singularityDirection() { return singularityDirection; }

    public Zone zoneOf(double x, double y) {
        double r = center.distance(new Vector2(x, y));
        if (r >= outerRadius) return Zone.OUTSIDE;
        if (r >= innerRadius) return Zone.BUFFER;
        return Zone.CORE;
    }

    public RepulsionResult evaluate(double x, double y) {
        if (!Double.isFinite(x) || !Double.isFinite(y)) {
            return new RepulsionResult(Vector2.ZERO, outerRadius, Zone.OUTSIDE);
        }

        double dx = x - center.x;
        double dy = y - center.y;
        double r = Math.sqrt(dx * dx + dy * dy);

        if (r >= outerRadius) {
            return new RepulsionResult(Vector2.ZERO, r, Zone.OUTSIDE);
        }

        if (r >= innerRadius) {
            // Buffer ring: F_crosshair = k_repel * ((outerRadius - r) / (outerRadius - innerRadius)) * r_hat
            double factor = (outerRadius - r) / (outerRadius - innerRadius);
            double magnitude = repelStrength * factor;
            Vector2 rHat = new Vector2(dx / r, dy / r);
            return new RepulsionResult(rHat.multiply(magnitude), r, Zone.BUFFER);
        }

        // Core exclusion zone: r < innerRadius
        // Must completely push the point out of the core exclusion zone.
        // The outward distance to clear the core boundary is (innerRadius - r),
        // plus the buffer ring strength at the inner perimeter for C0 continuity.
        double deltaR = (innerRadius - r) + repelStrength;
        Vector2 direction;
        if (r < 1e-9) {
            // Singularity at exact center: deterministic displacement
            direction = singularityDirection;
        } else {
            direction = new Vector2(dx / r, dy / r);
        }
        return new RepulsionResult(direction.multiply(deltaR), r, Zone.CORE);
    }

    public Vector2 calculateDisplacement(double x, double y) {
        return evaluate(x, y).displacement();
    }

    public Vector2 calculateDisplacement(Vector2 point) {
        if (point == null) return Vector2.ZERO;
        return calculateDisplacement(point.x(), point.y());
    }

    /**
     * Calculates displacement with optional tangential deflection to allow text to slide along the arc.
     *
     * @param x text x
     * @param y text y
     * @param tangentialBias blending factor for tangent vector (-1.0 to 1.0; 0 for pure radial)
     * @return displacement vector
     */
    public Vector2 calculateDisplacement(double x, double y, double tangentialBias) {
        RepulsionResult eval = evaluate(x, y);
        double bias = Double.isFinite(tangentialBias) ? Math.clamp(tangentialBias, -1.0, 1.0) : 0.0;
        if (eval.zone() == Zone.OUTSIDE || eval.displacement().isZero() || Math.abs(bias) < 1e-9) {
            return eval.displacement();
        }
        double dx = x - center.x;
        double dy = y - center.y;
        double r = Math.sqrt(dx * dx + dy * dy);
        Vector2 rHat = (r < 1e-9) ? singularityDirection : new Vector2(dx / r, dy / r);
        // Tangent vector 90 degrees CCW: (-rHat.y, rHat.x)
        Vector2 tHat = new Vector2(-rHat.y(), rHat.x());
        Vector2 blended = rHat.plus(tHat.multiply(bias)).normalize();
        return blended.multiply(eval.displacement().length());
    }

    public Vector2 displace(double x, double y) {
        Vector2 offset = calculateDisplacement(x, y);
        return new Vector2(x + offset.x(), y + offset.y());
    }

    public Vector2 displace(Vector2 point) {
        if (point == null) return Vector2.ZERO;
        Vector2 offset = calculateDisplacement(point.x(), point.y());
        return point.plus(offset);
    }

    public Vector2 calculateDisplacement(BillboardProjection.Bounds bounds) {
        if (bounds == null) return Vector2.ZERO;
        float centerX = (bounds.minX() + bounds.maxX()) * 0.5f;
        float centerY = (bounds.minY() + bounds.maxY()) * 0.5f;
        if (!Float.isFinite(centerX) || !Float.isFinite(centerY)) {
            return Vector2.ZERO;
        }
        return calculateDisplacement(centerX, centerY);
    }

    public BillboardProjection.Bounds displace(BillboardProjection.Bounds bounds) {
        if (bounds == null) return null;
        Vector2 offset = calculateDisplacement(bounds);
        if (offset.isZero()) return bounds;
        return new BillboardProjection.Bounds(
                (float) (bounds.minX() + offset.x()),
                (float) (bounds.minY() + offset.y()),
                (float) (bounds.maxX() + offset.x()),
                (float) (bounds.maxY() + offset.y())
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
