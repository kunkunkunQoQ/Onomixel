package dev.kunkun.onomixel.sound;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.Optional;

/** Short-lived, bounded client prediction evidence for attributing shared projectile sounds. */
final class ProjectileOwnershipEvidence {
    enum Family { ARROW, TRIDENT }
    enum Owner { LOCAL, EXTERNAL, UNKNOWN }

    private static final int CAPACITY = 128;
    private static final long TTL_NANOS = 1_000_000_000L;
    private static final double MATCH_DISTANCE_SQUARED = 0.35 * 0.35;

    private record Evidence(String soundId, Family family, Owner owner,
                            double x, double y, double z, long recordedAt) {}

    private final ArrayDeque<Evidence> entries = new ArrayDeque<>(CAPACITY);

    void recordImpact(Family family, Owner owner, double x, double y, double z, long nowNanos) {
        record(new Evidence(null, family, owner, x, y, z, nowNanos), nowNanos);
    }

    void recordEntitySound(String soundId, Owner owner, double x, double y, double z, long nowNanos) {
        if (!"minecraft:item.trident.throw".equals(soundId)) return;
        record(new Evidence(soundId, Family.TRIDENT, owner, x, y, z, nowNanos), nowNanos);
    }

    /** Empty means no reliable unique match; callers must preserve the radar indication. */
    Optional<Boolean> resolveImpact(String soundId, double x, double y, double z, long nowNanos) {
        Family family = impactFamily(soundId);
        if (family == null) return Optional.empty();
        return resolve(soundId, family, x, y, z, nowNanos, true);
    }

    /** Present false is explicit external/unknown owner and takes precedence over proximity heuristics. */
    Optional<Boolean> resolveEntitySound(String soundId, double x, double y, double z, long nowNanos) {
        if (!"minecraft:item.trident.throw".equals(soundId)) return Optional.empty();
        return resolve(soundId, Family.TRIDENT, x, y, z, nowNanos, false);
    }

    private Optional<Boolean> resolve(String soundId, Family family, double x, double y, double z,
                                      long nowNanos, boolean impactsOnly) {
        prune(nowNanos);
        Evidence match = null;
        int count = 0;
        for (Evidence entry : entries) {
            boolean isImpact = entry.soundId() == null;
            if (entry.family() != family || (impactsOnly != isImpact)
                    || (!isImpact && !soundId.equals(entry.soundId()))
                    || distanceSquared(entry.x(), entry.y(), entry.z(), x, y, z) > MATCH_DISTANCE_SQUARED) continue;
            match = entry;
            count++;
        }
        if (count == 0) return Optional.empty();
        // Consume every co-located candidate; ambiguity must never become a later false positive.
        Evidence selected = match;
        entries.removeIf(entry -> entry.family() == family
                && (impactsOnly ? entry.soundId() == null : soundId.equals(entry.soundId()))
                && distanceSquared(entry.x(), entry.y(), entry.z(), x, y, z) <= MATCH_DISTANCE_SQUARED);
        if (count == 1) return Optional.of(selected.owner() == Owner.LOCAL);
        // An ambiguous entity-bound launch must not fall back to the near-player heuristic.
        return impactsOnly ? Optional.empty() : Optional.of(false);
    }

    private void record(Evidence evidence, long nowNanos) {
        prune(nowNanos);
        while (entries.size() >= CAPACITY) entries.removeFirst();
        entries.addLast(evidence);
    }

    private void prune(long nowNanos) {
        for (Iterator<Evidence> iterator = entries.iterator(); iterator.hasNext();) {
            Evidence entry = iterator.next();
            if (nowNanos - entry.recordedAt() > TTL_NANOS || nowNanos < entry.recordedAt()) iterator.remove();
        }
    }

    void clear() { entries.clear(); }

    int size() { return entries.size(); }

    private static Family impactFamily(String soundId) {
        return switch (soundId) {
            case "minecraft:entity.arrow.hit" -> Family.ARROW;
            case "minecraft:item.trident.hit", "minecraft:item.trident.hit_ground" -> Family.TRIDENT;
            default -> null;
        };
    }

    private static double distanceSquared(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx, dy = ay - by, dz = az - bz;
        return dx * dx + dy * dy + dz * dz;
    }
}
