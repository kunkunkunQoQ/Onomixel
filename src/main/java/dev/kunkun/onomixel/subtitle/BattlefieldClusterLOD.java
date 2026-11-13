package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.sound.SoundDefinition;
import dev.kunkun.onomixel.sound.SoundDefinitions;
import dev.kunkun.onomixel.sound.SoundSemantics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Battlefield Clustering & Macro-LOD (宏观战场聚类与动态音效 LOD).
 * <p>
 * Handles high-density combat zones, mob farms, raids, and PvP battlefields by:
 * 1. Spatial Binning & Clustering: Groups identical sounds or identical displayed onomatopoeia
 *    within 2.5m grid cells. Promotes dense groups (&gt;= 3) while keeping each sound's original
 *    onomatopoeia text, semantics, and visual styling.
 * 2. Multiplier Badges: Appends concise count suffixes (e.g. "x6", rendered as "&lt;word&gt; x6").
 * 3. Tactical Audio Spotlight: Establishes a 3.5m spotlight radius around critical lethal
 *    threats (creeper fuse, primed TNT, warden sonic boom), suppressing low-threat clutter
 *    alpha down to 20% and applying outward repulsion.
 * <p>
 * Zero Minecraft runtime dependencies for pure JVM testability and high-performance execution.
 */
public final class BattlefieldClusterLOD {

    public static final double CLUSTER_GRID_SIZE = 2.5;
    public static final double CLUSTER_RADIUS = 2.5;
    public static final double CLUSTER_RADIUS_SQUARED = CLUSTER_RADIUS * CLUSTER_RADIUS;
    public static final double CLUSTER_ABSORPTION_RADIUS = 6.0;
    public static final double CLUSTER_ABSORPTION_RADIUS_SQUARED = CLUSTER_ABSORPTION_RADIUS * CLUSTER_ABSORPTION_RADIUS;
    public static final int DEFAULT_PROMOTION_THRESHOLD = 3;

    public static final double SPOTLIGHT_RADIUS = 3.5;
    public static final double SPOTLIGHT_RADIUS_SQUARED = SPOTLIGHT_RADIUS * SPOTLIGHT_RADIUS;
    public static final float SUPPRESSED_ALPHA = 0.20f;
    public static final double MAX_REPULSION_OFFSET = 0.50;

    private BattlefieldClusterLOD() {}

    /**
     * Categories of promoted macro-onomatopoeia.
     */
    public enum ChorusCategory {
        ZOMBIE_CHORUS("群尸哀嚎", "onomixel.chorus.zombie"),
        BEAST_ROAR("百兽狂啸", "onomixel.chorus.beast"),
        ARROW_RAIN("箭雨倾泻", "onomixel.chorus.arrow_rain"),
        ARTHROPOD_SWARM("百足骚动", "onomixel.chorus.arthropod"),
        STRUCTURE_COLLAPSE("轰然崩解", "onomixel.chorus.collapse"),
        GENERIC_CHORUS("轰鸣回荡", "onomixel.chorus.generic");

        private final String defaultTitle;
        private final String translationKey;

        ChorusCategory(String defaultTitle, String translationKey) {
            this.defaultTitle = defaultTitle;
            this.translationKey = translationKey;
        }

        public String defaultTitle() {
            return defaultTitle;
        }

        public String translationKey() {
            return translationKey;
        }
    }

    /**
     * Comic pop-art multiplier badge indicating dense sound count, e.g. "x6".
     */
    public record MultiplierBadge(int count, String text) {
        private static final String[] SUFFIX_CACHE = buildSuffixCache(64);

        public MultiplierBadge {
            if (count < 1) {
                throw new IllegalArgumentException("Badge count must be >= 1, got: " + count);
            }
            Objects.requireNonNull(text, "Badge text cannot be null");
        }

        public static MultiplierBadge of(int count) {
            return new MultiplierBadge(count, "x" + count);
        }

        /**
         * Returns the space-prefixed multiplier suffix (e.g. " x3") with zero allocation for counts &lt;= 64.
         */
        public static String formatSuffix(int count) {
            if (count >= 1 && count < SUFFIX_CACHE.length) {
                return SUFFIX_CACHE[count];
            }
            return " x" + count;
        }

        private static String[] buildSuffixCache(int maxCount) {
            String[] cache = new String[maxCount + 1];
            for (int i = 1; i <= maxCount; i++) {
                cache[i] = " x" + i;
            }
            return cache;
        }
    }

    /**
     * Outward displacement vector exerted by high-threat tactical spotlights.
     */
    public record RepulsionVector(double dx, double dy, double dz) {
        public static final RepulsionVector ZERO = new RepulsionVector(0.0, 0.0, 0.0);

        public RepulsionVector {
            if (!Double.isFinite(dx) || !Double.isFinite(dy) || !Double.isFinite(dz)) {
                throw new IllegalArgumentException("RepulsionVector components must be finite: (" + dx + ", " + dy + ", " + dz + ")");
            }
        }

        public double length() {
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * Spherical spotlight forcefield around high-lethal events.
     */
    public record SpotlightZone(double x, double y, double z, String threatSoundId, double radius) {
        public SpotlightZone {
            Objects.requireNonNull(threatSoundId, "threatSoundId cannot be null");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("Spotlight coordinates must be finite: (" + x + ", " + y + ", " + z + ")");
            }
            if (!Double.isFinite(radius) || radius <= 0.0) {
                throw new IllegalArgumentException("radius must be positive and finite: " + radius);
            }
        }

        public SpotlightZone(double x, double y, double z, String threatSoundId) {
            this(x, y, z, threatSoundId, SPOTLIGHT_RADIUS);
        }

        public boolean contains(double px, double py, double pz) {
            if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
                return false;
            }
            double ddx = px - x;
            double ddy = py - y;
            double ddz = pz - z;
            return (ddx * ddx + ddy * ddy + ddz * ddz) <= (radius * radius);
        }

        public RepulsionVector repulsionFor(double px, double py, double pz) {
            if (!Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
                return RepulsionVector.ZERO;
            }
            double ddx = px - x;
            double ddy = py - y;
            double ddz = pz - z;
            double distSq = ddx * ddx + ddy * ddy + ddz * ddz;
            if (distSq >= radius * radius) {
                return RepulsionVector.ZERO;
            }
            if (distSq < 1e-6) {
                return new RepulsionVector(0.0, MAX_REPULSION_OFFSET, 0.0);
            }
            double dist = Math.sqrt(distSq);
            double factor = (1.0 - (dist / radius)) * MAX_REPULSION_OFFSET;
            return new RepulsionVector((ddx / dist) * factor, (ddy / dist) * factor, (ddz / dist) * factor);
        }
    }

    /**
     * Sound entry representation for cluster calculations.
     */
    public record SoundEntry(
            long id,
            String soundId,
            String text,
            double x,
            double y,
            double z,
            SoundSemantics semantics,
            int priority,
            float alpha
    ) {
        public SoundEntry {
            Objects.requireNonNull(soundId, "soundId cannot be null");
            text = (text != null) ? text : soundId;
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("SoundEntry coordinates must be finite: (" + x + ", " + y + ", " + z + ")");
            }
            if (!Float.isFinite(alpha)) {
                throw new IllegalArgumentException("SoundEntry alpha must be finite: " + alpha);
            }
        }

        public SoundEntry(long id, String soundId, String text, double x, double y, double z, SoundSemantics semantics) {
            this(id, soundId, text != null ? text : soundId, x, y, z, semantics,
                    semantics != null ? semantics.importance() : 1, 1.0f);
        }

        public static SoundEntry fromSubtitle(SubtitlePool.Subtitle sub) {
            if (sub == null) return null;
            return new SoundEntry(
                    sub.id(),
                    sub.soundId(),
                    sub.translationKey(),
                    sub.x(),
                    sub.y(),
                    sub.z(),
                    sub.semantics(),
                    sub.priority(),
                    1.0f
            );
        }

        public SoundEntry withAlpha(float newAlpha) {
            return new SoundEntry(id, soundId, text, x, y, z, semantics, priority, newAlpha);
        }

        public SoundEntry withPosition(double newX, double newY, double newZ) {
            return new SoundEntry(id, soundId, text, newX, newY, newZ, semantics, priority, alpha);
        }

        public SoundEntry withPriority(int newPriority) {
            return new SoundEntry(id, soundId, text, x, y, z, semantics, newPriority, alpha);
        }
    }

    /**
     * A dense same-sound / same-onomatopoeia cluster promoted from multiple individual sounds.
     */
    public record ChorusCluster(
            ChorusCategory category,
            String promotedText,
            String translationKey,
            double x,
            double y,
            double z,
            MultiplierBadge badge,
            List<SoundEntry> members,
            float alpha,
            int priority,
            String clusterKey
    ) {
        public ChorusCluster {
            Objects.requireNonNull(category, "category cannot be null");
            Objects.requireNonNull(promotedText, "promotedText cannot be null");
            Objects.requireNonNull(badge, "badge cannot be null");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("ChorusCluster coordinates must be finite: (" + x + ", " + y + ", " + z + ")");
            }
            if (!Float.isFinite(alpha)) {
                throw new IllegalArgumentException("ChorusCluster alpha must be finite: " + alpha);
            }
            members = members != null ? List.copyOf(members) : List.of();
            if (clusterKey == null || clusterKey.isEmpty()) {
                clusterKey = !members.isEmpty()
                        ? normalizeClusterKey(members.getFirst().soundId())
                        : (translationKey != null ? translationKey : category.translationKey());
            }
        }

        public ChorusCluster(
                ChorusCategory category,
                String promotedText,
                String translationKey,
                double x,
                double y,
                double z,
                MultiplierBadge badge,
                List<SoundEntry> members,
                float alpha,
                int priority
        ) {
            this(category, promotedText, translationKey, x, y, z, badge, members, alpha, priority,
                    members != null && !members.isEmpty()
                            ? normalizeClusterKey(selectRepresentative(members).soundId())
                            : (translationKey != null ? translationKey : category.translationKey()));
        }

        public ChorusCluster(
                ChorusCategory category,
                String promotedText,
                double x,
                double y,
                double z,
                MultiplierBadge badge,
                List<SoundEntry> members,
                float alpha
        ) {
            this(category, promotedText, category.translationKey(), x, y, z, badge, members, alpha,
                    maxMemberPriority(members));
        }

        private static int maxMemberPriority(List<SoundEntry> members) {
            if (members == null || members.isEmpty()) {
                return 5;
            }
            int max = Integer.MIN_VALUE;
            for (int i = 0, size = members.size(); i < size; i++) {
                max = Math.max(max, members.get(i).priority());
            }
            return max;
        }

        public SoundEntry representative() {
            return members.isEmpty() ? null : selectRepresentative(members);
        }

        public String displayTitle() {
            return promotedText + " " + badge.text();
        }

        public int count() {
            return members.size();
        }
    }

    /**
     * Overall result of a battlefield processing pass.
     */
    public record ProcessedBattlefield(
            List<ChorusCluster> clusters,
            List<SoundEntry> unclustered,
            List<SpotlightZone> spotlights
    ) {
        public static final ProcessedBattlefield EMPTY = new ProcessedBattlefield(List.of(), List.of(), List.of());

        public ProcessedBattlefield {
            clusters = clusters != null ? List.copyOf(clusters) : List.of();
            unclustered = unclustered != null ? List.copyOf(unclustered) : List.of();
            spotlights = spotlights != null ? List.copyOf(spotlights) : List.of();
        }

        public boolean hasClusters() {
            return !clusters.isEmpty();
        }

        public int totalSoundCount() {
            int count = unclustered.size();
            for (ChorusCluster c : clusters) {
                count += c.count();
            }
            return count;
        }
    }

    /**
     * 3D discrete grid coordinate key for 2.5m spatial binning.
     */
    public record GridKey(long gx, long gy, long gz, ChorusCategory category) {
        public GridKey {
            Objects.requireNonNull(category, "category cannot be null");
        }
    }

    public static long gridCoord(double coord) {
        return (long) Math.floor(coord / CLUSTER_GRID_SIZE);
    }

    public static GridKey gridKey(double x, double y, double z, ChorusCategory category) {
        return new GridKey(gridCoord(x), gridCoord(y), gridCoord(z), category);
    }

    /**
     * Internal 2.5m grid + sound/onomatopoeia identity bucket key.
     */
    private record ClusterBucketKey(long gx, long gy, long gz, String groupKey) {}

    private static String normalizeClusterKey(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        return hasUppercase(key) ? key.toLowerCase(Locale.ROOT) : key;
    }

    private static String defaultSubtitleClusterKey(SubtitlePool.Subtitle sub) {
        String soundKey = normalizeClusterKey(sub.soundId());
        String transKey = sub.translationKey();
        if (transKey != null && transKey.startsWith("literal:")) {
            return soundKey + '\0' + transKey;
        }
        return soundKey;
    }

    static String resolveSubtitleClusterKey(
            SubtitlePool.Subtitle sub,
            Function<SubtitlePool.Subtitle, String> keyExtractor
    ) {
        if (keyExtractor == null) {
            return defaultSubtitleClusterKey(sub);
        }
        String customKey = keyExtractor.apply(sub);
        return (customKey != null && !customKey.isEmpty()) ? customKey : defaultSubtitleClusterKey(sub);
    }

    static int subtitleWeight(SubtitlePool.Subtitle sub) {
        return Math.clamp(sub.repeatCount(), 1, 64);
    }

    private static SoundEntry selectRepresentative(List<SoundEntry> bucket) {
        SoundEntry best = bucket.getFirst();
        int bestPriority = best.priority();
        for (int i = 1, size = bucket.size(); i < size; i++) {
            SoundEntry candidate = bucket.get(i);
            if (candidate.priority() > bestPriority) {
                best = candidate;
                bestPriority = candidate.priority();
            }
        }
        return best;
    }

    static String resolveBaseTranslationKey(SoundEntry rep) {
        String rawText = rep.text();
        String soundId = rep.soundId();
        if (rawText != null && !rawText.isEmpty() && !rawText.equals(soundId)) {
            return rawText;
        }
        SoundDefinition def = SoundDefinitions.find(soundId);
        if (def != null && def.translationKey() != null && !def.translationKey().isEmpty()) {
            return def.translationKey();
        }
        return rawText != null && !rawText.isEmpty() ? rawText : soundId;
    }

    private static String stripLiteralPrefix(String translationKey) {
        if (translationKey != null && translationKey.startsWith("literal:")) {
            return translationKey.substring(8);
        }
        return translationKey != null ? translationKey : "";
    }

    /**
     * Checks whether a subtitle represents a merged cluster entry created by {@link #createClusterSubtitle}.
     */
    public static boolean isClusterSubtitle(SubtitlePool.Subtitle sub) {
        return sub != null && sub.id() < 0 && "cluster".equals(sub.worldId());
    }

    private static boolean hasUppercase(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'A' && c <= 'Z') return true;
        }
        return false;
    }

    /**
     * Classifies a sound into its corresponding {@link ChorusCategory}.
     */
    public static ChorusCategory categorize(String soundId, SoundSemantics semantics) {
        if (soundId == null || soundId.isEmpty()) {
            if (semantics == null) return ChorusCategory.GENERIC_CHORUS;
            return switch (semantics) {
                case BREAK -> ChorusCategory.STRUCTURE_COLLAPSE;
                case VOICE, IDLE_VOICE -> ChorusCategory.BEAST_ROAR;
                case DAMAGE, ATTACK -> ChorusCategory.ARROW_RAIN;
                default -> ChorusCategory.GENERIC_CHORUS;
            };
        }

        String id = hasUppercase(soundId) ? soundId.toLowerCase(Locale.ROOT) : soundId;

        // 1. Arthropods crawling / stepping / ambient
        if (id.contains("spider") || id.contains("silverfish") || id.contains("endermite")) {
            return ChorusCategory.ARTHROPOD_SWARM;
        }

        // 2. Projectiles / Arrows / Tridents
        if (id.contains("arrow") || id.contains("trident") || id.contains("crossbow")
                || id.contains("shulker_bullet") || id.contains("projectile")
                || (semantics != null && (semantics == SoundSemantics.DAMAGE || semantics == SoundSemantics.ATTACK)
                    && (id.contains("shoot") || id.contains("hit") || id.contains("impact")))) {
            return ChorusCategory.ARROW_RAIN;
        }

        // 3. Block Break / Destruction
        if (semantics == SoundSemantics.BREAK || id.contains(".break") || id.contains("destroy") || id.contains("shatter")) {
            return ChorusCategory.STRUCTURE_COLLAPSE;
        }

        // 4. Zombie groans / voice
        if (id.contains("zombie") || id.contains("husk") || id.contains("drowned") || id.contains("zombified")) {
            return ChorusCategory.ZOMBIE_CHORUS;
        }

        // 5. General beasts / mob voices
        if (semantics == SoundSemantics.VOICE || semantics == SoundSemantics.IDLE_VOICE
                || id.contains(".ambient") || id.contains(".growl") || id.contains(".roar")
                || id.contains(".grunt") || id.contains(".screaming") || id.contains(".say")) {
            return ChorusCategory.BEAST_ROAR;
        }

        return ChorusCategory.GENERIC_CHORUS;
    }

    /**
     * Checks if a count meets the density threshold for chorus promotion.
     */
    public static boolean isEligibleForPromotion(int count) {
        return isEligibleForPromotion(count, DEFAULT_PROMOTION_THRESHOLD);
    }

    public static boolean isEligibleForPromotion(int count, int threshold) {
        return count >= threshold;
    }

    /**
     * Determines whether a sound qualifies as an instant-kill or critical threat establishing a spotlight.
     */
    public static boolean isCriticalThreat(String soundId) {
        if (soundId == null || soundId.isEmpty()) return false;
        return ThreatRegistry.evaluate(soundId) == ThreatRegistry.ThreatLevel.CRITICAL;
    }

    /**
     * Finds all active tactical spotlight zones produced by critical threats.
     */
    public static List<SpotlightZone> findSpotlights(List<SoundEntry> sounds) {
        if (sounds == null || sounds.isEmpty()) {
            return List.of();
        }
        List<SpotlightZone> result = null;
        for (int i = 0, size = sounds.size(); i < size; i++) {
            SoundEntry s = sounds.get(i);
            if (s != null && isCriticalThreat(s.soundId())) {
                if (result == null) {
                    result = new ArrayList<>(4);
                }
                result.add(new SpotlightZone(s.x(), s.y(), s.z(), s.soundId()));
            }
        }
        return result == null ? List.of() : Collections.unmodifiableList(result);
    }

    /**
     * Checks whether a sound at the specified coordinate is suppressed by any tactical spotlight.
     */
    public static boolean isSuppressedBySpotlight(double x, double y, double z, String soundId, List<SpotlightZone> spotlights) {
        if (isCriticalThreat(soundId)) {
            return false; // Critical threats never suppress themselves or each other
        }
        if (spotlights == null || spotlights.isEmpty() || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            return false;
        }
        for (SpotlightZone zone : spotlights) {
            if (zone != null && zone.contains(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Evaluates suppressed alpha (transparency) under active spotlights.
     */
    public static float evaluateSpotlightAlpha(double x, double y, double z, String soundId, List<SpotlightZone> spotlights, float baseAlpha) {
        if (!Float.isFinite(baseAlpha)) {
            return SUPPRESSED_ALPHA;
        }
        if (isSuppressedBySpotlight(x, y, z, soundId, spotlights)) {
            return Math.min(baseAlpha, SUPPRESSED_ALPHA);
        }
        return baseAlpha;
    }

    /**
     * Evaluates reduced priority weight under active spotlights.
     */
    public static int evaluateSpotlightPriority(double x, double y, double z, String soundId, List<SpotlightZone> spotlights, int basePriority) {
        if (isSuppressedBySpotlight(x, y, z, soundId, spotlights)) {
            return Math.max(1, basePriority / 2);
        }
        return basePriority;
    }

    /**
     * Calculates cumulative outward repulsion displacement from all active spotlights.
     */
    public static RepulsionVector calculateRepulsion(double px, double py, double pz, List<SpotlightZone> spotlights) {
        if (spotlights == null || spotlights.isEmpty() || !Double.isFinite(px) || !Double.isFinite(py) || !Double.isFinite(pz)) {
            return RepulsionVector.ZERO;
        }
        double totalDx = 0.0;
        double totalDy = 0.0;
        double totalDz = 0.0;
        for (SpotlightZone zone : spotlights) {
            if (zone == null) continue;
            RepulsionVector v = zone.repulsionFor(px, py, pz);
            totalDx += v.dx();
            totalDy += v.dy();
            totalDz += v.dz();
        }
        return new RepulsionVector(totalDx, totalDy, totalDz);
    }

    /**
     * Applies both transparency suppression and outward repulsion to a sound entry.
     */
    public static SoundEntry applySpotlightEffects(SoundEntry sound, List<SpotlightZone> spotlights) {
        if (sound == null || spotlights == null || spotlights.isEmpty()) {
            return sound;
        }
        if (isCriticalThreat(sound.soundId())) {
            return sound;
        }
        float alpha = evaluateSpotlightAlpha(sound.x(), sound.y(), sound.z(), sound.soundId(), spotlights, sound.alpha());
        int priority = evaluateSpotlightPriority(sound.x(), sound.y(), sound.z(), sound.soundId(), spotlights, sound.priority());
        RepulsionVector rep = calculateRepulsion(sound.x(), sound.y(), sound.z(), spotlights);
        return new SoundEntry(
                sound.id(),
                sound.soundId(),
                sound.text(),
                sound.x() + rep.dx(),
                sound.y() + rep.dy(),
                sound.z() + rep.dz(),
                sound.semantics(),
                priority,
                alpha
        );
    }

    /**
     * Runs full battlefield clustering and LOD pass on the given sound entries.
     */
    public static ProcessedBattlefield process(List<SoundEntry> sounds) {
        return process(sounds, DEFAULT_PROMOTION_THRESHOLD);
    }

    /**
     * Runs full battlefield clustering and LOD pass with a custom promotion threshold.
     */
    public static ProcessedBattlefield process(List<SoundEntry> sounds, int promotionThreshold) {
        if (sounds == null || sounds.isEmpty()) {
            return ProcessedBattlefield.EMPTY;
        }

        int threshold = Math.max(1, promotionThreshold);

        // 1. Discover all active spotlights
        List<SpotlightZone> spotlights = findSpotlights(sounds);

        // Fast-path: below threshold and no spotlights active
        if (sounds.size() < threshold && spotlights.isEmpty()) {
            return fastUnclusteredResult(sounds);
        }

        // 2. Spatial Binning by 2.5m grid cell & normalized soundId
        Map<ClusterBucketKey, List<SoundEntry>> gridBuckets = new LinkedHashMap<>(Math.min(sounds.size(), 64));
        for (int i = 0, size = sounds.size(); i < size; i++) {
            SoundEntry s = sounds.get(i);
            if (s == null) continue;
            ClusterBucketKey key = new ClusterBucketKey(
                    gridCoord(s.x()), gridCoord(s.y()), gridCoord(s.z()), normalizeClusterKey(s.soundId()));
            gridBuckets.computeIfAbsent(key, k -> new ArrayList<>(4)).add(s);
        }

        return finalizeBuckets(gridBuckets, threshold, spotlights);
    }

    private static ProcessedBattlefield fastUnclusteredResult(List<SoundEntry> sounds) {
        List<SoundEntry> valid = null;
        int size = sounds.size();
        for (int i = 0; i < size; i++) {
            SoundEntry s = sounds.get(i);
            if (s == null) {
                if (valid == null) {
                    valid = new ArrayList<>(size);
                    for (int j = 0; j < i; j++) {
                        valid.add(sounds.get(j));
                    }
                }
            } else if (valid != null) {
                valid.add(s);
            }
        }
        return new ProcessedBattlefield(List.of(), valid == null ? sounds : valid, List.of());
    }

    private static ChorusCluster buildClusterFromMembers(
            List<SoundEntry> bucket,
            String clusterKey,
            List<SpotlightZone> spotlights
    ) {
        int bsize = bucket.size();
        double sumX = 0.0;
        double sumY = 0.0;
        double sumZ = 0.0;
        for (int i = 0; i < bsize; i++) {
            SoundEntry s = bucket.get(i);
            sumX += s.x();
            sumY += s.y();
            sumZ += s.z();
        }
        double avgX = sumX / bsize;
        double avgY = sumY / bsize;
        double avgZ = sumZ / bsize;

        SoundEntry rep = selectRepresentative(bucket);
        ChorusCategory cat = categorize(rep.soundId(), rep.semantics());
        MultiplierBadge badge = MultiplierBadge.of(bsize);
        String translationKey = resolveBaseTranslationKey(rep);
        String promotedText = stripLiteralPrefix(translationKey);
        float clusterAlpha = spotlights.isEmpty()
                ? 1.0f
                : evaluateSpotlightAlpha(avgX, avgY, avgZ, rep.soundId(), spotlights, 1.0f);
        return new ChorusCluster(
                cat,
                promotedText,
                translationKey,
                avgX,
                avgY,
                avgZ,
                badge,
                bucket,
                clusterAlpha,
                ChorusCluster.maxMemberPriority(bucket),
                clusterKey
        );
    }

    private static ProcessedBattlefield finalizeBuckets(
            Map<ClusterBucketKey, List<SoundEntry>> gridBuckets,
            int threshold,
            List<SpotlightZone> spotlights
    ) {
        List<ChorusCluster> clusters = null;
        List<SoundEntry> unclustered = null;
        List<String> unclusteredGroupKeys = null;

        // 3. Density promotion and spotlight suppression
        for (Map.Entry<ClusterBucketKey, List<SoundEntry>> entry : gridBuckets.entrySet()) {
            List<SoundEntry> bucket = entry.getValue();
            String groupKey = entry.getKey().groupKey();

            int bsize = bucket.size();
            if (bsize >= threshold) {
                if (clusters == null) {
                    clusters = new ArrayList<>(4);
                }
                clusters.add(buildClusterFromMembers(bucket, groupKey, spotlights));
            } else {
                if (unclustered == null) {
                    unclustered = new ArrayList<>(bsize);
                    unclusteredGroupKeys = new ArrayList<>(bsize);
                }
                if (spotlights.isEmpty()) {
                    for (int i = 0; i < bsize; i++) {
                        unclustered.add(bucket.get(i));
                        unclusteredGroupKeys.add(groupKey);
                    }
                } else {
                    for (int i = 0; i < bsize; i++) {
                        SoundEntry s = bucket.get(i);
                        float alpha = evaluateSpotlightAlpha(s.x(), s.y(), s.z(), s.soundId(), spotlights, s.alpha());
                        int priority = evaluateSpotlightPriority(s.x(), s.y(), s.z(), s.soundId(), spotlights, s.priority());
                        unclustered.add(alpha == s.alpha() && priority == s.priority()
                                ? s
                                : s.withAlpha(alpha).withPriority(priority));
                        unclusteredGroupKeys.add(groupKey);
                    }
                }
            }
        }

        // 4. Absorb cross-grid-boundary unclustered solo entries into nearby matching promoted clusters
        if (clusters != null && !clusters.isEmpty() && unclustered != null && !unclustered.isEmpty()) {
            unclustered = absorbNearbyUnclustered(clusters, unclustered, unclusteredGroupKeys, spotlights);
        }

        return new ProcessedBattlefield(
                clusters == null ? List.of() : clusters,
                unclustered == null ? List.of() : unclustered,
                spotlights
        );
    }

    private static List<SoundEntry> absorbNearbyUnclustered(
            List<ChorusCluster> clusters,
            List<SoundEntry> unclustered,
            List<String> unclusteredGroupKeys,
            List<SpotlightZone> spotlights
    ) {
        int clusterCount = clusters.size();
        List<List<SoundEntry>> absorbedPerCluster = null;
        int usize = unclustered.size();
        boolean[] absorbed = null;
        for (int i = 0; i < usize; i++) {
            SoundEntry candidate = unclustered.get(i);
            String groupKey = unclusteredGroupKeys.get(i);
            int bestClusterIdx = -1;
            double bestDistSq = CLUSTER_ABSORPTION_RADIUS_SQUARED;
            for (int c = 0; c < clusterCount; c++) {
                ChorusCluster cluster = clusters.get(c);
                if (!cluster.clusterKey().equals(groupKey)) {
                    continue;
                }
                double dx = candidate.x() - cluster.x();
                double dy = candidate.y() - cluster.y();
                double dz = candidate.z() - cluster.z();
                double distSq = dx * dx + dy * dy + dz * dz;
                if (distSq <= bestDistSq) {
                    bestDistSq = distSq;
                    bestClusterIdx = c;
                }
            }
            if (bestClusterIdx >= 0) {
                if (absorbedPerCluster == null) {
                    absorbedPerCluster = new ArrayList<>(clusterCount);
                    for (int c = 0; c < clusterCount; c++) {
                        absorbedPerCluster.add(null);
                    }
                    absorbed = new boolean[usize];
                }
                absorbed[i] = true;
                List<SoundEntry> extra = absorbedPerCluster.get(bestClusterIdx);
                if (extra == null) {
                    extra = new ArrayList<>(4);
                    absorbedPerCluster.set(bestClusterIdx, extra);
                }
                extra.add(candidate);
            }
        }
        if (absorbedPerCluster == null) {
            return unclustered;
        }
        List<SoundEntry> finalRemaining = new ArrayList<>(usize);
        for (int i = 0; i < usize; i++) {
            if (!absorbed[i]) {
                finalRemaining.add(unclustered.get(i));
            }
        }
        for (int c = 0; c < clusterCount; c++) {
            List<SoundEntry> extra = absorbedPerCluster.get(c);
            if (extra != null && !extra.isEmpty()) {
                ChorusCluster existing = clusters.get(c);
                List<SoundEntry> combined = new ArrayList<>(existing.members().size() + extra.size());
                combined.addAll(existing.members());
                combined.addAll(extra);
                clusters.set(c, buildClusterFromMembers(combined, existing.clusterKey(), spotlights));
            }
        }
        return finalRemaining;
    }

    /**
     * Fast zero-allocation pre-check determining whether any grid bucket in {@code subtitles}
     * can reach the default cluster promotion threshold.
     */
    public static boolean hasPotentialCluster(List<SubtitlePool.Subtitle> subtitles) {
        return hasPotentialCluster(subtitles, DEFAULT_PROMOTION_THRESHOLD, null);
    }

    /**
     * Fast zero-allocation pre-check determining whether any grid bucket in {@code subtitles}
     * can reach {@code promotionThreshold}.
     */
    public static boolean hasPotentialCluster(List<SubtitlePool.Subtitle> subtitles, int promotionThreshold) {
        return hasPotentialCluster(subtitles, promotionThreshold, null);
    }

    /**
     * Fast pre-check determining whether any grid bucket in {@code subtitles}
     * can reach {@code promotionThreshold} under {@code keyExtractor} (when null, uses default sound/literal key).
     * Common frames without dense cells exit in Phase 1 with zero allocations and without calling {@code keyExtractor}.
     */
    public static boolean hasPotentialCluster(
            List<SubtitlePool.Subtitle> subtitles,
            int promotionThreshold,
            Function<SubtitlePool.Subtitle, String> keyExtractor
    ) {
        if (subtitles == null || subtitles.isEmpty()) {
            return false;
        }
        int threshold = Math.max(1, promotionThreshold);
        int size = subtitles.size();
        int maxPossibleTotal = 0;
        for (int i = 0; i < size; i++) {
            SubtitlePool.Subtitle sub = subtitles.get(i);
            if (sub == null) {
                continue;
            }
            int weight = subtitleWeight(sub);
            if (weight >= threshold) {
                return true;
            }
            maxPossibleTotal += weight;
        }
        if (maxPossibleTotal < threshold) {
            return false;
        }

        for (int i = 0; i < size; i++) {
            SubtitlePool.Subtitle si = subtitles.get(i);
            if (si == null) {
                continue;
            }
            long gxi = gridCoord(si.x());
            long gyi = gridCoord(si.y());
            long gzi = gridCoord(si.z());
            boolean alreadyCheckedCell = false;
            for (int j = 0; j < i; j++) {
                SubtitlePool.Subtitle sj = subtitles.get(j);
                if (sj != null && gridCoord(sj.x()) == gxi && gridCoord(sj.y()) == gyi && gridCoord(sj.z()) == gzi) {
                    alreadyCheckedCell = true;
                    break;
                }
            }
            if (alreadyCheckedCell) {
                continue;
            }

            int cellUpperBound = subtitleWeight(si);
            for (int k = i + 1; k < size; k++) {
                SubtitlePool.Subtitle sk = subtitles.get(k);
                if (sk != null && gridCoord(sk.x()) == gxi && gridCoord(sk.y()) == gyi && gridCoord(sk.z()) == gzi) {
                    cellUpperBound += subtitleWeight(sk);
                }
            }
            if (cellUpperBound < threshold) {
                continue;
            }

            if (cellHasMatchingGroup(subtitles, size, i, gxi, gyi, gzi, threshold, keyExtractor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean cellHasMatchingGroup(
            List<SubtitlePool.Subtitle> subtitles,
            int size,
            int cellStartIndex,
            long gx,
            long gy,
            long gz,
            int threshold,
            Function<SubtitlePool.Subtitle, String> keyExtractor
    ) {
        if (keyExtractor == null) {
            for (int k = cellStartIndex; k < size; k++) {
                SubtitlePool.Subtitle sk = subtitles.get(k);
                if (sk == null || gridCoord(sk.x()) != gx || gridCoord(sk.y()) != gy || gridCoord(sk.z()) != gz) {
                    continue;
                }
                String gk = defaultSubtitleClusterKey(sk);
                boolean seenEarlier = false;
                for (int prev = cellStartIndex; prev < k; prev++) {
                    SubtitlePool.Subtitle sp = subtitles.get(prev);
                    if (sp != null && gridCoord(sp.x()) == gx && gridCoord(sp.y()) == gy && gridCoord(sp.z()) == gz
                            && defaultSubtitleClusterKey(sp).equals(gk)) {
                        seenEarlier = true;
                        break;
                    }
                }
                if (seenEarlier) {
                    continue;
                }
                int count = subtitleWeight(sk);
                if (count >= threshold) {
                    return true;
                }
                for (int m = k + 1; m < size; m++) {
                    SubtitlePool.Subtitle sm = subtitles.get(m);
                    if (sm != null && gridCoord(sm.x()) == gx && gridCoord(sm.y()) == gy && gridCoord(sm.z()) == gz
                            && defaultSubtitleClusterKey(sm).equals(gk)) {
                        count += subtitleWeight(sm);
                        if (count >= threshold) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        Map<String, Integer> groupCounts = new HashMap<>(8);
        for (int k = cellStartIndex; k < size; k++) {
            SubtitlePool.Subtitle sk = subtitles.get(k);
            if (sk == null || gridCoord(sk.x()) != gx || gridCoord(sk.y()) != gy || gridCoord(sk.z()) != gz) {
                continue;
            }
            String gk = resolveSubtitleClusterKey(sk, keyExtractor);
            int nextCount = groupCounts.getOrDefault(gk, 0) + subtitleWeight(sk);
            if (nextCount >= threshold) {
                return true;
            }
            groupCounts.put(gk, nextCount);
        }
        return false;
    }

    /**
     * Processes live subtitles directly from {@link SubtitlePool.Subtitle}.
     */
    public static ProcessedBattlefield processSubtitles(List<SubtitlePool.Subtitle> subtitles) {
        return processSubtitles(subtitles, DEFAULT_PROMOTION_THRESHOLD, null);
    }

    /**
     * Processes live subtitles directly with custom promotion threshold.
     */
    public static ProcessedBattlefield processSubtitles(List<SubtitlePool.Subtitle> subtitles, int promotionThreshold) {
        return processSubtitles(subtitles, promotionThreshold, null);
    }

    /**
     * Processes live subtitles with a custom grouping key extractor (e.g. localized display text + visual style).
     */
    public static ProcessedBattlefield processSubtitles(
            List<SubtitlePool.Subtitle> subtitles,
            int promotionThreshold,
            Function<SubtitlePool.Subtitle, String> keyExtractor
    ) {
        if (subtitles == null || subtitles.isEmpty()) {
            return ProcessedBattlefield.EMPTY;
        }
        int threshold = Math.max(1, promotionThreshold);
        int size = subtitles.size();
        List<SpotlightZone> spotlights = null;
        int totalEntries = 0;
        for (int i = 0; i < size; i++) {
            SubtitlePool.Subtitle sub = subtitles.get(i);
            if (sub == null) {
                continue;
            }
            totalEntries += subtitleWeight(sub);
            if (isCriticalThreat(sub.soundId())) {
                if (spotlights == null) {
                    spotlights = new ArrayList<>(4);
                }
                spotlights.add(new SpotlightZone(sub.x(), sub.y(), sub.z(), sub.soundId()));
            }
        }
        if (totalEntries == 0) {
            return ProcessedBattlefield.EMPTY;
        }
        List<SpotlightZone> spotlightList = spotlights == null ? List.of() : Collections.unmodifiableList(spotlights);
        if (totalEntries < threshold && spotlightList.isEmpty()) {
            List<SoundEntry> unclustered = new ArrayList<>(totalEntries);
            for (int i = 0; i < size; i++) {
                SubtitlePool.Subtitle sub = subtitles.get(i);
                if (sub == null) continue;
                SoundEntry base = SoundEntry.fromSubtitle(sub);
                int weight = subtitleWeight(sub);
                for (int r = 0; r < weight; r++) {
                    unclustered.add(base);
                }
            }
            return new ProcessedBattlefield(List.of(), unclustered, List.of());
        }

        Map<ClusterBucketKey, List<SoundEntry>> gridBuckets = new LinkedHashMap<>(Math.min(size, 64));
        for (int i = 0; i < size; i++) {
            SubtitlePool.Subtitle sub = subtitles.get(i);
            if (sub == null) continue;
            SoundEntry base = SoundEntry.fromSubtitle(sub);
            String groupKey = resolveSubtitleClusterKey(sub, keyExtractor);
            ClusterBucketKey key = new ClusterBucketKey(
                    gridCoord(base.x()), gridCoord(base.y()), gridCoord(base.z()), groupKey);
            List<SoundEntry> bucket = gridBuckets.computeIfAbsent(key, k -> new ArrayList<>(4));
            int weight = subtitleWeight(sub);
            for (int r = 0; r < weight; r++) {
                bucket.add(base);
            }
        }
        return finalizeBuckets(gridBuckets, threshold, spotlightList);
    }

    /**
     * Converts a promoted ChorusCluster into a synthetic Subtitle representation
     * suitable for consumption by DynamicSubtitleLayout while preserving original sound semantics
     * and translation/onomatopoeia key.
     */
    public static SubtitlePool.Subtitle createClusterSubtitle(ChorusCluster cluster, long now) {
        if (cluster == null || cluster.members().isEmpty()) return null;
        SoundEntry rep = cluster.representative();
        String soundId = rep.soundId();
        String baseKey = (cluster.translationKey() != null && !cluster.translationKey().isEmpty())
                ? cluster.translationKey()
                : resolveBaseTranslationKey(rep);
        long id = computeStableClusterId(cluster.clusterKey(), cluster.x(), cluster.y(), cluster.z());
        SoundSemantics semantics = rep.semantics() != null ? rep.semantics() : switch (cluster.category()) {
            case ZOMBIE_CHORUS, BEAST_ROAR -> SoundSemantics.VOICE;
            case ARROW_RAIN -> SoundSemantics.DAMAGE;
            case ARTHROPOD_SWARM -> SoundSemantics.STEP;
            case STRUCTURE_COLLAPSE -> SoundSemantics.BREAK;
            case GENERIC_CHORUS -> SoundSemantics.AMBIENT;
        };
        long duration = 1_500_000_000L;
        long createdAt = now - 100_000_000L;
        long radarDur = ThreatRegistry.radarDurationNanos(soundId, duration);
        long radarExpires = radarDur > 0 ? now + radarDur : now + duration;
        return new SubtitlePool.Subtitle(
                id,
                soundId,
                baseKey,
                "cluster",
                "chorus",
                cluster.x(), cluster.y(), cluster.z(),
                cluster.x(), cluster.y(), cluster.z(),
                createdAt, now + duration, now + 2 * duration, now,
                cluster.priority(),
                semantics,
                semantics.mergeGroup(soundId),
                false,
                radarExpires,
                cluster.badge().count()
        );
    }

    /**
     * Generates a deterministic, unique, negative Long ID for a cluster based on its
     * discrete spatial grid cell (2.5m grid) and cluster key, ensuring frame-to-frame stability
     * even when multiple distinct sounds cluster inside the same cell.
     */
    public static long computeStableClusterId(String clusterKey, double x, double y, double z) {
        long gx = gridCoord(x);
        long gy = gridCoord(y);
        long gz = gridCoord(z);
        long hash = 0x9E3779B97F4A7C15L;
        if (clusterKey != null) {
            for (int i = 0, len = clusterKey.length(); i < len; i++) {
                hash = hash * 31L + clusterKey.charAt(i);
            }
        }
        hash = hash * 31L + gx;
        hash = hash * 31L + gy;
        hash = hash * 31L + gz;
        hash = (hash ^ (hash >>> 32)) * 0xBF58476D1CE4E5B9L;
        hash = (hash ^ (hash >>> 27)) * 0x94D049BB133111EBL;
        hash = hash ^ (hash >>> 31);
        long abs = hash == Long.MIN_VALUE ? 1L : Math.abs(hash);
        return -Math.max(1L, abs);
    }

    /**
     * Generates a deterministic, unique, negative Long ID for a cluster based on its
     * discrete spatial grid cell (2.5m grid) and category, ensuring frame-to-frame stability.
     */
    public static long computeStableClusterId(ChorusCategory category, double x, double y, double z) {
        long gx = (long) Math.floor(x / 2.5);
        long gy = (long) Math.floor(y / 2.5);
        long gz = (long) Math.floor(z / 2.5);
        long cat = category != null ? category.ordinal() : 0L;
        long hash = cat;
        hash = hash * 31 + gx;
        hash = hash * 31 + gy;
        hash = hash * 31 + gz;
        hash = (hash ^ (hash >>> 32)) * 0x45d9f3bL;
        hash = (hash ^ (hash >>> 32)) * 0x45d9f3bL;
        hash = hash ^ (hash >>> 32);
        return -Math.max(1L, Math.abs(hash));
    }

    /**
     * Resolves a pure-JVM clustering key combining displayed onomatopoeia text with
     * resolved visual styling, threat level, source geometry, and semantics so only
     * visually and semantically equivalent hot-cell sounds merge into {@code <text> xN}.
     */
    public static String styleGroupingKey(SubtitlePool.Subtitle event, UnaryOperator<String> translationResolver) {
        if (event == null) {
            return "";
        }
        String soundId = event.soundId();
        String rawKey = event.translationKey();
        String resolvedText;
        if (rawKey == null || rawKey.isBlank()) {
            resolvedText = soundId != null ? soundId : "";
        } else if (rawKey.startsWith("literal:")) {
            resolvedText = rawKey.substring("literal:".length());
        } else {
            String translated = translationResolver != null ? translationResolver.apply(rawKey) : rawKey;
            resolvedText = translated != null ? translated : rawKey;
        }
        CustomWordRule customRule = SoundCustomizationManager.resolve(soundId);
        VisualType type = CaptionVisuals.type(soundId, customRule);
        KineticTypography.Mode kinetic = KineticTypography.resolveMode(type.id(), soundId, customRule);
        VisualColor color = VisualTypeRegistry.resolveColor(soundId, type);
        boolean isBold = customRule != null && customRule.bold() != null ? customRule.bold() : type.isBold();
        boolean isItalic = customRule != null && customRule.italic() != null ? customRule.italic() : type.isItalic();
        float scaleMultiplier = customRule != null && customRule.scaleMultiplier() != null
                ? customRule.scaleMultiplier() : 1.0f;
        double heightOffset = customRule != null && customRule.heightOffset() != null
                ? customRule.heightOffset() : 0.0;
        ThreatRegistry.ThreatLevel threat = ThreatRegistry.evaluate(soundId);
        SoundDefinition.Source source = SoundDefinitions.source(soundId);
        SoundSemantics semantics = event.semantics() != null ? event.semantics() : SoundSemantics.OTHER;
        String bossIdentity = BossChoreographyDirector.isBossSound(soundId) ? soundId : "";

        StringBuilder sb = new StringBuilder(96);
        sb.append(resolvedText).append('|')
                .append(type.id()).append('|')
                .append(kinetic.ordinal()).append('|')
                .append(color.vibrant()).append(':').append(color.pastel()).append('|')
                .append(isBold ? '1' : '0').append(isItalic ? '1' : '0').append('|')
                .append(Float.floatToIntBits(scaleMultiplier)).append('|')
                .append(Double.doubleToLongBits(heightOffset)).append('|')
                .append(threat.ordinal()).append('|')
                .append(source.kind().ordinal()).append(':').append(Float.floatToIntBits((float) source.height())).append('|')
                .append(semantics.ordinal());
        if (!bossIdentity.isEmpty()) {
            sb.append('|').append(bossIdentity);
        }
        return sb.toString();
    }
}
