package dev.kunkun.onomixel.render;

import java.util.Map;

/** A bounded, smoothed displacement chosen before final visibility and screen checks. */
public final class CaptionRepulsion {
    public static final double MAX_OFFSET = 0.12;
    public static final double BASE_MAX_OFFSET = 0.12;
    public static final double STACK_MAX_OFFSET = 0.28; // Adaptive vertical stacking limit (0.25m ~ 0.32m)

    public record RepulsionOffset(double dx, double dy) {
        public static final RepulsionOffset ZERO = new RepulsionOffset(0.0, 0.0);
    }

    private CaptionRepulsion() {}

    public static double target(long self, BillboardProjection.Bounds base,
                                Map<Long, BillboardProjection.Bounds> occupied) {
        return target(self, base, occupied, MAX_OFFSET);
    }

    public static double target(long self, BillboardProjection.Bounds base,
                                Map<Long, BillboardProjection.Bounds> occupied,
                                double maxOffset) {
        if (base == null) return 0;
        double greatestOverlap = 0;
        long neighbor = Long.MAX_VALUE;
        double direction = 0;
        for (var entry : occupied.entrySet()) {
            if (entry.getKey() == self || entry.getValue() == null) continue;
            var other = entry.getValue();
            double overlap = base.overlap(other);
            if (overlap <= 0 || overlap < greatestOverlap
                    || (overlap == greatestOverlap && entry.getKey() > neighbor)) continue;
            greatestOverlap = overlap;
            neighbor = entry.getKey();
            double deltaY = base.minY() + base.maxY() - other.minY() - other.maxY();
            direction = Math.abs(deltaY) > 0.0001 ? Math.signum(deltaY) : self < neighbor ? 1 : -1;
        }
        return direction * maxOffset;
    }

    public static RepulsionOffset target(long self, BillboardProjection.Bounds base,
                                         Map<Long, BillboardProjection.Bounds> occupied,
                                         CrosshairComfortZone crosshairZone,
                                         FirstPersonWeaponMask weaponMask) {
        if (base == null) return RepulsionOffset.ZERO;

        // Adaptive Vertical Stacking: calculate dynamic vertical repulsion limit based on vertical overlap with other captions
        double verticalLimit = BASE_MAX_OFFSET;
        float baseH = base.maxY() - base.minY();
        if (baseH > 0.001F) {
            float maxVOverlap = 0.0F;
            for (var entry : occupied.entrySet()) {
                if (entry.getKey() == self || entry.getValue() == null) continue;
                var other = entry.getValue();
                float vOverlap = Math.max(0, Math.min(base.maxY(), other.maxY()) - Math.max(base.minY(), other.minY()));
                if (vOverlap > maxVOverlap) {
                    maxVOverlap = vOverlap;
                }
            }
            float overlapRatio = maxVOverlap / baseH;
            if (overlapRatio > 0.20F) {
                float factor = Math.clamp((overlapRatio - 0.20F) / 0.60F, 0.0F, 1.0F);
                verticalLimit = BASE_MAX_OFFSET + (STACK_MAX_OFFSET - BASE_MAX_OFFSET) * factor;
            }
        }

        double dy = target(self, base, occupied, verticalLimit);
        double dx = 0.0;

        if (crosshairZone != null) {
            CrosshairComfortZone.Vector2 ch = crosshairZone.calculateDisplacement(base);
            dx += Math.clamp(ch.x() * 0.5, -BASE_MAX_OFFSET, BASE_MAX_OFFSET);
            dy += Math.clamp(ch.y() * 0.5, -verticalLimit, verticalLimit);
        }

        if (weaponMask != null && weaponMask.isFirstPerson()) {
            FirstPersonWeaponMask.AvoidanceResult wm = weaponMask.evaluate(base);
            if (wm.insideMask()) {
                dx += Math.clamp(wm.displacement().x() * 0.5, -BASE_MAX_OFFSET, BASE_MAX_OFFSET);
                dy += Math.clamp(wm.displacement().y() * 0.5, -verticalLimit, verticalLimit);
            }
        }

        return new RepulsionOffset(Math.clamp(dx, -BASE_MAX_OFFSET, BASE_MAX_OFFSET), Math.clamp(dy, -verticalLimit, verticalLimit));
    }

    public static double advance(double current, double target, double seconds) {
        return advance(current, target, seconds, STACK_MAX_OFFSET);
    }

    public static double advance(double current, double target, double seconds, double maxOffset) {
        if (!Double.isFinite(seconds) || seconds <= 0) return current;
        double fraction = 1 - Math.exp(-Math.min(seconds, 0.10) / 0.115);
        return Math.clamp(current + (target - current) * fraction, -maxOffset, maxOffset);
    }

    public static RepulsionOffset advance(RepulsionOffset current, RepulsionOffset target, double seconds) {
        if (current == null) current = RepulsionOffset.ZERO;
        if (target == null) target = RepulsionOffset.ZERO;
        return new RepulsionOffset(
                advance(current.dx(), target.dx(), seconds, BASE_MAX_OFFSET),
                advance(current.dy(), target.dy(), seconds, STACK_MAX_OFFSET)
        );
    }
}
