package dev.kunkun.onomixel.render;

/**
 * Tick-level bounded reuse policy for expensive world ray/block visibility probes during 144Hz rendering.
 * Reuses cached clearance only within 1 game tick (50ms) and strict camera/target movement tolerances.
 */
public final class RayCachePolicy {
    public static final long RAY_CACHE_TTL_NANOS = 50_000_000L;
    public static final double RAY_EYE_MOVE_SQ = 0.04 * 0.04;
    public static final double RAY_TARGET_MOVE_SQ = 0.03 * 0.03;
    public static final double RAY_LOOK_COS_THRESHOLD = 0.996;

    private RayCachePolicy() {}

    public static boolean shouldReuse(
            long now, long cachedNanos,
            double eyeX, double eyeY, double eyeZ,
            double cachedEyeX, double cachedEyeY, double cachedEyeZ,
            double targetX, double targetY, double targetZ,
            double cachedTargetX, double cachedTargetY, double cachedTargetZ,
            double lookX, double lookY, double lookZ,
            double cachedLookX, double cachedLookY, double cachedLookZ,
            boolean checkLook
    ) {
        if (cachedNanos == Long.MIN_VALUE / 2 || now < cachedNanos || now - cachedNanos >= RAY_CACHE_TTL_NANOS) {
            return false;
        }
        double dex = eyeX - cachedEyeX;
        double dey = eyeY - cachedEyeY;
        double dez = eyeZ - cachedEyeZ;
        if (dex * dex + dey * dey + dez * dez > RAY_EYE_MOVE_SQ) {
            return false;
        }
        double dtx = targetX - cachedTargetX;
        double dty = targetY - cachedTargetY;
        double dtz = targetZ - cachedTargetZ;
        if (dtx * dtx + dty * dty + dtz * dtz > RAY_TARGET_MOVE_SQ) {
            return false;
        }
        if (checkLook) {
            double dot = lookX * cachedLookX + lookY * cachedLookY + lookZ * cachedLookZ;
            if (dot < RAY_LOOK_COS_THRESHOLD) {
                return false;
            }
        }
        return true;
    }
}
