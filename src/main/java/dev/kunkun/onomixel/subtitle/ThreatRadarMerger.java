package dev.kunkun.onomixel.subtitle;

/**
 * Zero-allocation radar threat deduplication, nearby/same-direction clustering,
 * and escalation-override engine.
 * <p>
 * Pure JVM (zero Minecraft dependencies) for deterministic unit testing and
 * 60-144 FPS HUD rendering reuse.
 */
public final class ThreatRadarMerger {

    /** Cap for allowing a newer same-level phase sound to supersede a slightly closer stale position. */
    private static final double PHASE_OVERRIDE_MAX_DIST_DIFF = 3.0;

    private ThreatRadarMerger() {}

    /** Reusable mutable candidate container allocated once per renderer instance. */
    public static final class Candidate {
        public String soundId;
        public double soundX;
        public double soundY;
        public double soundZ;
        public ThreatRegistry.ThreatLevel level = ThreatRegistry.ThreatLevel.NONE;
        public boolean isCritical;
        public double distance;
        public double maxDistance;
        public long triggeredNanos;

        public void set(String soundId, double soundX, double soundY, double soundZ,
                        ThreatRegistry.ThreatLevel level, double distance, double maxDistance,
                        long triggeredNanos) {
            this.soundId = soundId;
            this.soundX = soundX;
            this.soundY = soundY;
            this.soundZ = soundZ;
            this.level = level != null ? level : ThreatRegistry.ThreatLevel.NONE;
            this.isCritical = this.level == ThreatRegistry.ThreatLevel.CRITICAL;
            this.distance = distance;
            this.maxDistance = maxDistance;
            this.triggeredNanos = triggeredNanos;
        }
    }

    /**
     * Merges nearby or same-direction threat candidates (keeping escalated/higher threats)
     * and sorts the surviving representatives in-place by display priority.
     *
     * @param pool             pre-allocated candidate slots [0 .. count)
     * @param count            number of populated candidates in {@code pool}
     * @param camX             camera X
     * @param camY             camera Y
     * @param camZ             camera Z
     * @param mergeRangeBlocks merge radius in blocks; {@code <= 0} disables merging
     * @return number of surviving candidates after merging (at indexes {@code [0, returnValue)})
     */
    public static int mergeAndSort(Candidate[] pool, int count,
                                   double camX, double camY, double camZ,
                                   double mergeRangeBlocks) {
        if (pool == null || count <= 0) {
            return 0;
        }
        int boundedCount = Math.min(count, pool.length);

        sortByDisplayPriority(pool, boundedCount);
        if (!Double.isFinite(mergeRangeBlocks) || mergeRangeBlocks <= 0.0 || boundedCount == 1) {
            return boundedCount;
        }

        double rangeSq = mergeRangeBlocks * mergeRangeBlocks;
        double maxAngleRad = Math.min(0.45, mergeRangeBlocks * 0.035);
        double minCos = Math.cos(maxAngleRad);
        double maxRadialDiff = mergeRangeBlocks * 1.5;
        double phaseOverrideMaxDiff = Math.min(PHASE_OVERRIDE_MAX_DIST_DIFF, mergeRangeBlocks);

        int mergedCount = 0;
        for (int i = 0; i < boundedCount; i++) {
            Candidate incoming = pool[i];
            int matchIndex = -1;
            for (int m = 0; m < mergedCount; m++) {
                if (canMerge(pool[m], incoming, camX, camY, camZ, rangeSq, minCos, maxRadialDiff)) {
                    matchIndex = m;
                    break;
                }
            }

            if (matchIndex >= 0) {
                if (shouldUpgradeRepresentative(pool[matchIndex], incoming, phaseOverrideMaxDiff)) {
                    swap(pool, matchIndex, i);
                }
            } else {
                if (mergedCount != i) {
                    swap(pool, mergedCount, i);
                }
                mergedCount++;
            }
        }

        if (mergedCount > 1) {
            sortByDisplayPriority(pool, mergedCount);
        }
        return mergedCount;
    }

    private static boolean canMerge(Candidate a, Candidate b,
                                    double camX, double camY, double camZ,
                                    double rangeSq, double minCos, double maxRadialDiff) {
        double dx = a.soundX - b.soundX;
        double dy = a.soundY - b.soundY;
        double dz = a.soundZ - b.soundZ;
        if (dx * dx + dy * dy + dz * dz <= rangeSq) {
            return true;
        }

        // Same-direction angular cone fallback (bounded by radial separation to avoid far occlusion).
        double ax = a.soundX - camX;
        double ay = a.soundY - camY;
        double az = a.soundZ - camZ;
        double bx = b.soundX - camX;
        double by = b.soundY - camY;
        double bz = b.soundZ - camZ;
        double lenASq = ax * ax + ay * ay + az * az;
        double lenBSq = bx * bx + by * by + bz * bz;
        if (lenASq <= 1e-8 || lenBSq <= 1e-8) {
            return false;
        }
        double lenA = Math.sqrt(lenASq);
        double lenB = Math.sqrt(lenBSq);
        double cosTheta = (ax * bx + ay * by + az * bz) / (lenA * lenB);
        return cosTheta >= minCos && Math.abs(lenA - lenB) <= maxRadialDiff;
    }

    private static boolean shouldUpgradeRepresentative(Candidate rep, Candidate incoming,
                                                       double phaseOverrideMaxDiff) {
        // Representatives are processed highest-level-first; equal level with a newer trigger near the
        // same position upgrades stale phase labels (e.g. warden sonic_charge -> sonic_boom, roar -> roar).
        return incoming.level == rep.level
                && incoming.triggeredNanos > rep.triggeredNanos
                && Math.abs(incoming.distance - rep.distance) <= phaseOverrideMaxDiff;
    }

    private static void swap(Candidate[] pool, int i, int j) {
        Candidate tmp = pool[i];
        pool[i] = pool[j];
        pool[j] = tmp;
    }

    private static int threatRank(ThreatRegistry.ThreatLevel level) {
        if (level == null) return 3;
        return switch (level) {
            case CRITICAL -> 0;
            case ELEVATED -> 1;
            case TACTICAL -> 2;
            case NONE -> 3;
        };
    }

    private static int comparePriority(Candidate a, Candidate b) {
        int rankCmp = Integer.compare(threatRank(a.level), threatRank(b.level));
        if (rankCmp != 0) {
            return rankCmp;
        }
        int distCmp = Double.compare(a.distance, b.distance);
        if (distCmp != 0) {
            return distCmp;
        }
        return Long.compare(b.triggeredNanos, a.triggeredNanos);
    }

    private static void sortByDisplayPriority(Candidate[] pool, int count) {
        for (int i = 1; i < count; i++) {
            Candidate key = pool[i];
            int j = i - 1;
            while (j >= 0 && comparePriority(pool[j], key) > 0) {
                pool[j + 1] = pool[j];
                j--;
            }
            pool[j + 1] = key;
        }
    }
}
