package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundSemantics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Stateful frame-to-frame tracker for active dense subtitle clusters ({@code <word> xN}).
 * <p>
 * Keeps promoted {@code xN} counts monotonically accumulating while the cluster remains active
 * on screen (instead of cancelling or dropping back down as early raw entries expire from
 * {@link SubtitlePool}), and absorbs any nearby matching solo subtitles so duplicate raw words
 * never leak beside an already-active cluster.
 * <p>
 * Zero Minecraft runtime dependencies for pure JVM testability.
 */
public final class BattlefieldClusterTracker {

    public static final long DEFAULT_CLUSTER_HOLD_NANOS = 1_800_000_000L;
    public static final double ACTIVE_ABSORPTION_RADIUS = BattlefieldClusterLOD.CLUSTER_ABSORPTION_RADIUS;
    public static final double ACTIVE_ABSORPTION_RADIUS_SQUARED =
            BattlefieldClusterLOD.CLUSTER_ABSORPTION_RADIUS_SQUARED;

    private static final int MAX_ACCOUNTED_REPEATS = 256;

    private static final class TrackedCluster {
        final long clusterId;
        final String groupKey;
        final BattlefieldClusterLOD.ChorusCategory category;
        final long createdNanos;
        long expiresNanos;
        long triggeredNanos;
        long radarExpiresNanos;
        String soundId;
        String translationKey;
        SoundSemantics semantics;
        int priority;
        double anchorX;
        double anchorY;
        double anchorZ;
        int count;
        final Map<Long, Integer> accountedRepeats = new HashMap<>(16);

        TrackedCluster(
                long clusterId,
                String groupKey,
                BattlefieldClusterLOD.ChorusCategory category,
                long createdNanos,
                long expiresNanos,
                long triggeredNanos,
                long radarExpiresNanos,
                String soundId,
                String translationKey,
                SoundSemantics semantics,
                int priority,
                double anchorX,
                double anchorY,
                double anchorZ,
                int count
        ) {
            this.clusterId = clusterId;
            this.groupKey = groupKey;
            this.category = category;
            this.createdNanos = createdNanos;
            this.expiresNanos = expiresNanos;
            this.triggeredNanos = triggeredNanos;
            this.radarExpiresNanos = radarExpiresNanos;
            this.soundId = soundId;
            this.translationKey = translationKey;
            this.semantics = semantics;
            this.priority = priority;
            this.anchorX = anchorX;
            this.anchorY = anchorY;
            this.anchorZ = anchorZ;
            this.count = count;
        }
    }

    private final long holdNanos;
    private final List<TrackedCluster> activeClusters = new ArrayList<>(8);
    private final Map<Long, String> resolvedGroupKeys = new HashMap<>(16);
    private final Map<Long, SubtitlePool.Subtitle> eligibleById = new HashMap<>(16);
    private final Set<Long> consumedIds = new HashSet<>(16);
    private final List<SubtitlePool.Subtitle> unconsumed = new ArrayList<>(16);

    public BattlefieldClusterTracker() {
        this(DEFAULT_CLUSTER_HOLD_NANOS);
    }

    public BattlefieldClusterTracker(long holdNanos) {
        if (holdNanos <= 0L) {
            throw new IllegalArgumentException("holdNanos must be positive: " + holdNanos);
        }
        this.holdNanos = holdNanos;
    }

    public void clear() {
        activeClusters.clear();
        resolvedGroupKeys.clear();
        eligibleById.clear();
        consumedIds.clear();
        unconsumed.clear();
    }

    /**
     * Prunes expired clusters and reports whether any active {@code xN} cluster remains alive at {@code now}.
     */
    public boolean hasActiveClusters(long now) {
        pruneExpired(now);
        return !activeClusters.isEmpty();
    }

    public List<SubtitlePool.Subtitle> resolve(List<SubtitlePool.Subtitle> eligible, long now) {
        return resolve(eligible, now, BattlefieldClusterLOD.DEFAULT_PROMOTION_THRESHOLD, null, new ArrayList<>());
    }

    public List<SubtitlePool.Subtitle> resolve(
            List<SubtitlePool.Subtitle> eligible,
            long now,
            int promotionThreshold,
            Function<SubtitlePool.Subtitle, String> keyExtractor,
            List<SubtitlePool.Subtitle> output
    ) {
        output.clear();
        pruneExpired(now);

        if (eligible == null || eligible.isEmpty()) {
            emitActiveClusters(output);
            return output;
        }

        int threshold = Math.max(1, promotionThreshold);
        resolvedGroupKeys.clear();
        eligibleById.clear();
        consumedIds.clear();
        unconsumed.clear();

        if (!activeClusters.isEmpty()) {
            for (int i = 0, size = eligible.size(); i < size; i++) {
                SubtitlePool.Subtitle sub = eligible.get(i);
                if (sub == null) {
                    continue;
                }
                eligibleById.put(sub.id(), sub);
                String gk = BattlefieldClusterLOD.resolveSubtitleClusterKey(sub, keyExtractor);
                TrackedCluster match = findNearestActiveCluster(gk, sub.x(), sub.y(), sub.z());
                if (match != null) {
                    absorbIntoActiveCluster(match, sub, now, eligible);
                    consumedIds.add(sub.id());
                } else {
                    unconsumed.add(sub);
                    resolvedGroupKeys.put(sub.id(), gk);
                }
            }

            if (!unconsumed.isEmpty()) {
                Function<SubtitlePool.Subtitle, String> cachedExtractor = sub -> {
                    String cached = resolvedGroupKeys.get(sub.id());
                    return cached != null ? cached : BattlefieldClusterLOD.resolveSubtitleClusterKey(sub, keyExtractor);
                };
                if (BattlefieldClusterLOD.hasPotentialCluster(unconsumed, threshold, cachedExtractor)) {
                    var promoted = BattlefieldClusterLOD.processSubtitles(unconsumed, threshold, cachedExtractor);
                    registerNewClusters(promoted, now);
                }
            }
        } else {
            for (int i = 0, size = eligible.size(); i < size; i++) {
                SubtitlePool.Subtitle sub = eligible.get(i);
                if (sub != null) {
                    eligibleById.put(sub.id(), sub);
                }
            }
            var promoted = BattlefieldClusterLOD.processSubtitles(eligible, threshold, keyExtractor);
            registerNewClusters(promoted, now);
        }

        emitActiveClusters(output);
        for (int i = 0, size = eligible.size(); i < size; i++) {
            SubtitlePool.Subtitle sub = eligible.get(i);
            if (sub != null && !consumedIds.contains(sub.id())) {
                output.add(sub);
            }
        }
        return output;
    }

    private void pruneExpired(long now) {
        if (activeClusters.isEmpty()) {
            return;
        }
        for (int i = activeClusters.size() - 1; i >= 0; i--) {
            if (now >= activeClusters.get(i).expiresNanos) {
                activeClusters.remove(i);
            }
        }
    }

    private TrackedCluster findNearestActiveCluster(String groupKey, double x, double y, double z) {
        TrackedCluster best = null;
        double bestDistSq = ACTIVE_ABSORPTION_RADIUS_SQUARED;
        for (int i = 0, size = activeClusters.size(); i < size; i++) {
            TrackedCluster candidate = activeClusters.get(i);
            if (!candidate.groupKey.equals(groupKey)) {
                continue;
            }
            double dx = x - candidate.anchorX;
            double dy = y - candidate.anchorY;
            double dz = z - candidate.anchorZ;
            double distSq = dx * dx + dy * dy + dz * dz;
            if (distSq <= bestDistSq) {
                bestDistSq = distSq;
                best = candidate;
            }
        }
        return best;
    }

    private void absorbIntoActiveCluster(
            TrackedCluster cluster,
            SubtitlePool.Subtitle sub,
            long now,
            List<SubtitlePool.Subtitle> eligible
    ) {
        int currentWeight = BattlefieldClusterLOD.subtitleWeight(sub);
        Integer previousWeight = cluster.accountedRepeats.get(sub.id());
        int delta = previousWeight == null ? currentWeight : Math.max(0, currentWeight - previousWeight);
        if (previousWeight == null || currentWeight > previousWeight) {
            cluster.accountedRepeats.put(sub.id(), currentWeight);
        }
        if (delta > 0) {
            cluster.count = Math.min(cluster.count + delta, 9999);
            cluster.triggeredNanos = now;
            cluster.expiresNanos = Math.max(cluster.expiresNanos, Math.max(sub.expiresNanos(), now + holdNanos));
            long radarDur = ThreatRegistry.radarDurationNanos(sub.soundId(), holdNanos);
            cluster.radarExpiresNanos = Math.max(cluster.radarExpiresNanos,
                    radarDur > 0 ? now + radarDur : now + holdNanos);
            cluster.anchorX = cluster.anchorX * 0.8 + sub.x() * 0.2;
            cluster.anchorY = cluster.anchorY * 0.8 + sub.y() * 0.2;
            cluster.anchorZ = cluster.anchorZ * 0.8 + sub.z() * 0.2;
            if (sub.priority() >= cluster.priority) {
                cluster.priority = sub.priority();
                cluster.soundId = sub.soundId();
                cluster.translationKey = BattlefieldClusterLOD.resolveBaseTranslationKey(
                        BattlefieldClusterLOD.SoundEntry.fromSubtitle(sub));
                if (sub.semantics() != null) {
                    cluster.semantics = sub.semantics();
                }
            }
            if (cluster.accountedRepeats.size() > MAX_ACCOUNTED_REPEATS) {
                pruneAccountedRepeats(cluster, eligible);
            }
        } else {
            cluster.expiresNanos = Math.max(cluster.expiresNanos, sub.expiresNanos());
        }
    }

    private static void pruneAccountedRepeats(TrackedCluster cluster, List<SubtitlePool.Subtitle> eligible) {
        Set<Long> liveIds = new HashSet<>(eligible.size() * 2 + 1);
        for (int i = 0, size = eligible.size(); i < size; i++) {
            SubtitlePool.Subtitle sub = eligible.get(i);
            if (sub != null) {
                liveIds.add(sub.id());
            }
        }
        cluster.accountedRepeats.keySet().retainAll(liveIds);
    }

    private void registerNewClusters(BattlefieldClusterLOD.ProcessedBattlefield promoted, long now) {
        if (!promoted.hasClusters()) {
            return;
        }
        var clusters = promoted.clusters();
        for (int i = 0, size = clusters.size(); i < size; i++) {
            var cluster = clusters.get(i);
            var rep = cluster.representative();
            String soundId = rep.soundId();
            String baseKey = (cluster.translationKey() != null && !cluster.translationKey().isEmpty())
                    ? cluster.translationKey()
                    : BattlefieldClusterLOD.resolveBaseTranslationKey(rep);
            SoundSemantics semantics = rep.semantics() != null ? rep.semantics() : SoundSemantics.AMBIENT;
            long clusterId = BattlefieldClusterLOD.computeStableClusterId(
                    cluster.clusterKey(), cluster.x(), cluster.y(), cluster.z());
            long maxExpires = now + holdNanos;
            var members = cluster.members();
            TrackedCluster tracked = new TrackedCluster(
                    clusterId,
                    cluster.clusterKey(),
                    cluster.category(),
                    Math.max(0L, now - 100_000_000L),
                    maxExpires,
                    now,
                    now + Math.max(holdNanos, ThreatRegistry.radarDurationNanos(soundId, holdNanos)),
                    soundId,
                    baseKey,
                    semantics,
                    cluster.priority(),
                    cluster.x(),
                    cluster.y(),
                    cluster.z(),
                    cluster.count()
            );
            for (int m = 0, msize = members.size(); m < msize; m++) {
                long memberId = members.get(m).id();
                consumedIds.add(memberId);
                SubtitlePool.Subtitle rawSub = eligibleById.get(memberId);
                int weight = rawSub != null ? BattlefieldClusterLOD.subtitleWeight(rawSub) : 1;
                tracked.accountedRepeats.merge(memberId, weight, Math::max);
                if (rawSub != null) {
                    tracked.expiresNanos = Math.max(tracked.expiresNanos, rawSub.expiresNanos());
                    tracked.radarExpiresNanos = Math.max(tracked.radarExpiresNanos, rawSub.radarExpiresNanos());
                }
            }
            activeClusters.add(tracked);
        }
    }

    private void emitActiveClusters(List<SubtitlePool.Subtitle> output) {
        for (int i = 0, size = activeClusters.size(); i < size; i++) {
            TrackedCluster tc = activeClusters.get(i);
            output.add(new SubtitlePool.Subtitle(
                    tc.clusterId,
                    tc.soundId,
                    tc.translationKey,
                    "cluster",
                    "chorus",
                    tc.anchorX, tc.anchorY, tc.anchorZ,
                    tc.anchorX, tc.anchorY, tc.anchorZ,
                    tc.createdNanos,
                    tc.expiresNanos,
                    tc.expiresNanos + holdNanos,
                    tc.triggeredNanos,
                    tc.priority,
                    tc.semantics,
                    tc.semantics.mergeGroup(tc.soundId),
                    false,
                    tc.radarExpiresNanos,
                    tc.count
            ));
        }
    }
}
