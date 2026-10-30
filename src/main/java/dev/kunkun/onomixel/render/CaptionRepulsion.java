package dev.kunkun.onomixel.render;

import java.util.Map;

/** A bounded, smoothed displacement chosen before final visibility and screen checks. */
public final class CaptionRepulsion {
    private static final double MAX_OFFSET = 0.12;

    public record RepulsionOffset(double dx, double dy) {
        public static final RepulsionOffset ZERO = new RepulsionOffset(0.0, 0.0);
    }

    private CaptionRepulsion() {}

    public static double target(long self, BillboardProjection.Bounds base,
                                Map<Long, BillboardProjection.Bounds> occupied) {
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
        return direction * MAX_OFFSET;
    }

    public static RepulsionOffset target(long self, BillboardProjection.Bounds base,
                                         Map<Long, BillboardProjection.Bounds> occupied,
                                         CrosshairComfortZone crosshairZone,
                                         FirstPersonWeaponMask weaponMask) {
        if (base == null) return RepulsionOffset.ZERO;
        double dy = target(self, base, occupied);
        double dx = 0.0;

        if (crosshairZone != null) {
            CrosshairComfortZone.Vector2 ch = crosshairZone.calculateDisplacement(base);
            dx += Math.clamp(ch.x() * 0.5, -MAX_OFFSET, MAX_OFFSET);
            dy += Math.clamp(ch.y() * 0.5, -MAX_OFFSET, MAX_OFFSET);
        }

        if (weaponMask != null && weaponMask.isFirstPerson()) {
            FirstPersonWeaponMask.AvoidanceResult wm = weaponMask.evaluate(base);
            if (wm.insideMask()) {
                dx += Math.clamp(wm.displacement().x() * 0.5, -MAX_OFFSET, MAX_OFFSET);
                dy += Math.clamp(wm.displacement().y() * 0.5, -MAX_OFFSET, MAX_OFFSET);
            }
        }

        return new RepulsionOffset(Math.clamp(dx, -MAX_OFFSET, MAX_OFFSET), Math.clamp(dy, -MAX_OFFSET, MAX_OFFSET));
    }

    public static double advance(double current, double target, double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return current;
        double fraction = 1 - Math.exp(-Math.min(seconds, 0.10) / 0.115);
        return Math.clamp(current + (target - current) * fraction, -MAX_OFFSET, MAX_OFFSET);
    }

    public static RepulsionOffset advance(RepulsionOffset current, RepulsionOffset target, double seconds) {
        if (current == null) current = RepulsionOffset.ZERO;
        if (target == null) target = RepulsionOffset.ZERO;
        return new RepulsionOffset(advance(current.dx(), target.dx(), seconds), advance(current.dy(), target.dy(), seconds));
    }
}
