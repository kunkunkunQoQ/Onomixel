package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundSemantics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Client-thread event state: immutable source/emission points and bounded repeat extension. */
public final class SubtitlePool {
    public static final int CAPACITY = 256;
    private static final double MERGE_DISTANCE_SQUARED = 0.75 * 0.75;
    private final List<Subtitle> active = new ArrayList<>();
    private long sequence;

    public record Subtitle(long id, String soundId, String translationKey, String worldId, String category,
                           double sourceX, double sourceY, double sourceZ,
                           double x, double y, double z, long createdNanos, long expiresNanos,
                           long hardExpiresNanos, long triggeredNanos, int priority,
                           SoundSemantics semantics, String mergeGroup, boolean localPlayerShot,
                           long radarExpiresNanos) {
        public Subtitle(long id, String soundId, String translationKey, String worldId, String category,
                        double sourceX, double sourceY, double sourceZ,
                        double x, double y, double z, long createdNanos, long expiresNanos,
                        long hardExpiresNanos, long triggeredNanos, int priority,
                        SoundSemantics semantics, String mergeGroup, boolean localPlayerShot) {
            this(id, soundId, translationKey, worldId, category, sourceX, sourceY, sourceZ, x, y, z,
                    createdNanos, expiresNanos, hardExpiresNanos, triggeredNanos, priority,
                    semantics, mergeGroup, localPlayerShot,
                    calcDefaultRadarExpires(soundId, expiresNanos, triggeredNanos));
        }

        public Subtitle(long id, String soundId, String translationKey, String worldId, String category,
                        double sourceX, double sourceY, double sourceZ,
                        double x, double y, double z, long createdNanos, long expiresNanos,
                        long hardExpiresNanos, long triggeredNanos, int priority,
                        SoundSemantics semantics, String mergeGroup) {
            this(id, soundId, translationKey, worldId, category, sourceX, sourceY, sourceZ, x, y, z,
                    createdNanos, expiresNanos, hardExpiresNanos, triggeredNanos, priority,
                    semantics, mergeGroup, false);
        }

        public Subtitle(long id, String soundId, String translationKey, String worldId, String category,
                        double sourceX, double sourceY, double sourceZ,
                        double x, double y, double z, long createdNanos, long expiresNanos,
                        long hardExpiresNanos, long triggeredNanos, int priority) {
            this(id, soundId, translationKey, worldId, category, sourceX, sourceY, sourceZ, x, y, z,
                    createdNanos, expiresNanos, hardExpiresNanos, triggeredNanos, priority,
                    SoundSemantics.classify(soundId), SoundSemantics.classify(soundId).mergeGroup(soundId), false);
        }

        private static long calcDefaultRadarExpires(String soundId, long expiresNanos, long triggeredNanos) {
            long fallback = Math.max(0L, expiresNanos - triggeredNanos);
            long rDuration = ThreatRegistry.radarDurationNanos(soundId, fallback);
            return rDuration > 0 ? triggeredNanos + rDuration : expiresNanos;
        }

        public double distanceSquared(double cameraX, double cameraY, double cameraZ) {
            double dx = x - cameraX;
            double dy = y - cameraY;
            double dz = z - cameraZ;
            return dx * dx + dy * dy + dz * dz;
        }

        public double sourceDistanceSquared(double cameraX, double cameraY, double cameraZ) {
            double dx = sourceX - cameraX, dy = sourceY - cameraY, dz = sourceZ - cameraZ;
            return dx * dx + dy * dy + dz * dz;
        }

        public double alpha(long now, boolean fade) {
            if (now >= expiresNanos) return 0;
            if (!fade) return 1;
            double fadeIn = (double) (now - createdNanos) / 100_000_000L;
            double fadeOut = (double) (expiresNanos - now) / 250_000_000L;
            return Math.clamp(Math.min(fadeIn, fadeOut), 0.0, 1.0);
        }
    }

    public void add(String soundId, String key, String worldId, String category,
                    double x, double y, double z, long now, long duration, int priority) {
        addAt(soundId, key, worldId, category, x, y, z, x, y, z, now, duration, priority);
    }

    public void addAt(String soundId, String key, String worldId, String category,
                      double sourceX, double sourceY, double sourceZ, double x, double y, double z,
                      long now, long duration, int priority) {
        SoundSemantics semantics = SoundSemantics.classify(soundId);
        addAt(soundId, key, worldId, category, sourceX, sourceY, sourceZ, x, y, z,
                now, duration, priority, semantics, semantics.mergeGroup(soundId), false);
    }

    public void addAt(String soundId, String key, String worldId, String category,
                      double sourceX, double sourceY, double sourceZ, double x, double y, double z,
                      long now, long duration, int priority, SoundSemantics semantics, String mergeGroup) {
        addAt(soundId, key, worldId, category, sourceX, sourceY, sourceZ, x, y, z,
                now, duration, priority, semantics, mergeGroup, false);
    }

    public void addAt(String soundId, String key, String worldId, String category,
                      double sourceX, double sourceY, double sourceZ, double x, double y, double z,
                      long now, long duration, int priority, SoundSemantics semantics, String mergeGroup,
                      boolean localPlayerShot) {
        if (!Double.isFinite(sourceX) || !Double.isFinite(sourceY) || !Double.isFinite(sourceZ)
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z) || duration <= 0
                || semantics == null || mergeGroup == null) return;
        expire(now);

        // A new high-signal event clears nearby low-signal clutter before it competes for a slot.
        if (semantics.importance() >= 6) {
            active.removeIf(previous -> sameSpatialSource(previous, worldId, sourceX, sourceY, sourceZ)
                    && isLowSignal(previous.semantics)
                    && semantics.importance() > previous.semantics.importance());
        }

        for (int i = 0; i < active.size(); i++) {
            Subtitle previous = active.get(i);
            if (sameSource(previous, worldId, category, sourceX, sourceY, sourceZ)
                    && previous.semantics == semantics && previous.mergeGroup.equals(mergeGroup)) {
                if (previous.expiresNanos <= now) {
                    active.remove(i);
                    break;
                }
                long refreshedExpires = Math.min(now + duration, previous.hardExpiresNanos);
                long radarDur = ThreatRegistry.radarDurationNanos(soundId, duration);
                long refreshedRadarExpires = radarDur > 0 ? now + radarDur : refreshedExpires;
                active.set(i, new Subtitle(previous.id, soundId, key, worldId, category,
                        previous.sourceX, previous.sourceY, previous.sourceZ,
                        previous.x, previous.y, previous.z, previous.createdNanos,
                        Math.max(previous.expiresNanos, refreshedExpires), previous.hardExpiresNanos, now,
                        Math.max(previous.priority, priority), semantics, mergeGroup,
                        previous.localPlayerShot && localPlayerShot,
                        Math.max(previous.radarExpiresNanos, refreshedRadarExpires)));
                return;
            }
        }
        if (active.size() == CAPACITY) {
            int weakestIndex = weakestIndex();
            Subtitle weakest = active.get(weakestIndex);
            if (effectivePriority(semantics, priority) < effectivePriority(weakest.semantics, weakest.priority)) return;
            active.remove(weakestIndex);
        }
        long radarDur = ThreatRegistry.radarDurationNanos(soundId, duration);
        long radarExpires = radarDur > 0 ? now + radarDur : now + duration;
        active.add(new Subtitle(++sequence, soundId, key, worldId, category, sourceX, sourceY, sourceZ, x, y, z,
                now, now + duration, now + 2 * duration, now, priority, semantics, mergeGroup, localPlayerShot,
                radarExpires));
    }

    public void renewStreaming(String soundId, double x, double y, double z, long newExpiresNanos) {
        if (soundId == null) return;
        for (int i = 0; i < active.size(); i++) {
            Subtitle previous = active.get(i);
            if (previous.soundId().equals(soundId)) {
                long radarDur = ThreatRegistry.radarDurationNanos(soundId, newExpiresNanos - previous.createdNanos());
                long newRadarExpires = radarDur > 0 ? previous.triggeredNanos() + radarDur : newExpiresNanos;
                active.set(i, new Subtitle(previous.id(), previous.soundId(), previous.translationKey(),
                        previous.worldId(), previous.category(), x, y, z, x, y, z,
                        previous.createdNanos(), Math.max(previous.expiresNanos(), newExpiresNanos),
                        previous.hardExpiresNanos(), previous.triggeredNanos(), previous.priority(),
                        previous.semantics(), previous.mergeGroup(), previous.localPlayerShot(),
                        Math.max(previous.radarExpiresNanos(), newRadarExpires)));
                return;
            }
        }
    }

    /** Call after expire: repeat detection uses the source, independent of the chosen display point. */
    public Subtitle existing(String worldId, String category, double x, double y, double z) {
        for (Subtitle subtitle : active) {
            if (sameSource(subtitle, worldId, category, x, y, z)) return subtitle;
        }
        return null;
    }

    public Subtitle existing(String worldId, String category, double x, double y, double z,
                             SoundSemantics semantics, String mergeGroup) {
        if (semantics == null || mergeGroup == null) return null;
        for (Subtitle subtitle : active) {
            if (sameSource(subtitle, worldId, category, x, y, z)
                    && subtitle.semantics == semantics && subtitle.mergeGroup.equals(mergeGroup)) return subtitle;
        }
        return null;
    }

    private static boolean sameSource(Subtitle subtitle, String worldId, String category, double x, double y, double z) {
        return subtitle.category.equals(category) && sameSpatialSource(subtitle, worldId, x, y, z);
    }

    private static boolean sameSpatialSource(Subtitle subtitle, String worldId, double x, double y, double z) {
        double dx = subtitle.sourceX - x, dy = subtitle.sourceY - y, dz = subtitle.sourceZ - z;
        return subtitle.worldId.equals(worldId)
                && dx * dx + dy * dy + dz * dz <= MERGE_DISTANCE_SQUARED;
    }

    /** Source distance uses the rendering camera; moving display text cannot pull distant events into range. */
    public List<Subtitle> candidates(double cameraX, double cameraY, double cameraZ, double maxDistance, long now) {
        expire(now);
        double rangeSquared = maxDistance * maxDistance;
        List<Subtitle> candidates = new ArrayList<>();
        for (Subtitle subtitle : active) {
            if (subtitle.expiresNanos <= now) continue;
            double distance = subtitle.sourceDistanceSquared(cameraX, cameraY, cameraZ);
            if (distance >= 0.75 * 0.75 && distance <= rangeSquared) candidates.add(subtitle);
        }
        Comparator<Subtitle> distanceOrder = Comparator
                .comparingDouble((Subtitle s) -> s.sourceDistanceSquared(cameraX, cameraY, cameraZ))
                .thenComparing(Comparator.comparingLong(Subtitle::createdNanos).reversed());
        Comparator<Subtitle> importanceOrder = Comparator
                .comparingInt((Subtitle s) -> effectivePriority(s.semantics, s.priority)).reversed()
                .thenComparing(distanceOrder);
        List<Subtitle> important = new ArrayList<>();
        List<Subtitle> ordinary = new ArrayList<>();
        for (Subtitle subtitle : candidates) {
            if (effectivePriority(subtitle.semantics, subtitle.priority) >= 6) important.add(subtitle);
            else ordinary.add(subtitle);
        }
        important.sort(importanceOrder);
        ordinary.sort(importanceOrder);

        // Keep one ordinary opportunity for every three important candidates when both exist.
        List<Subtitle> ordered = new ArrayList<>(candidates.size());
        int high = 0, normal = 0;
        while (high < important.size() || normal < ordinary.size()) {
            for (int i = 0; i < 3 && high < important.size(); i++) ordered.add(important.get(high++));
            if (normal < ordinary.size()) ordered.add(ordinary.get(normal++));
        }
        return ordered;
    }

    /** Immediately removes active sounds disallowed by a newly selected display mode. */
    public void filterAllowed(DisplayMode mode) {
        active.removeIf(subtitle -> !subtitle.semantics.allowed(mode));
    }

    private int weakestIndex() {
        int weakest = 0;
        for (int i = 1; i < active.size(); i++) {
            Subtitle candidate = active.get(i), current = active.get(weakest);
            int candidatePriority = effectivePriority(candidate.semantics, candidate.priority);
            int currentPriority = effectivePriority(current.semantics, current.priority);
            if (candidatePriority < currentPriority
                    || (candidatePriority == currentPriority && candidate.createdNanos < current.createdNanos)) weakest = i;
        }
        return weakest;
    }

    private static int effectivePriority(SoundSemantics semantics, int priority) {
        return Math.max(priority, semantics.importance());
    }

    private static boolean isLowSignal(SoundSemantics semantics) {
        return semantics == SoundSemantics.STEP || semantics == SoundSemantics.IDLE_VOICE
                || semantics == SoundSemantics.AMBIENT;
    }

    public void expire(long now) {
        active.removeIf(subtitle -> now >= Math.max(subtitle.expiresNanos, subtitle.radarExpiresNanos));
    }

    public int size() { return active.size(); }

    public List<Subtitle> snapshot() { return List.copyOf(active); }

    public void clear() { active.clear(); }
}
