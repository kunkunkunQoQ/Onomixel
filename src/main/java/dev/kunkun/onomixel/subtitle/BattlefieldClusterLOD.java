package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundSemantics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Battlefield Clustering & Macro-LOD (宏观战场聚类与动态音效 LOD).
 * <p>
 * Handles high-density combat zones, mob farms, raids, and PvP battlefields by:
 * 1. Spatial Binning & Clustering: Groups sounds of similar semantics within 2.5m grid cells.
 *    Promotes clusters with density &gt;= 3 into compound onomatopoeia (Chorus Promotion).
 * 2. Chorus Promotion & Multiplier Badges: Converts dense zombie moans into "群尸哀嚎",
 *    projectiles into "箭雨倾泻", arthropod movement into "百足骚动", breaks into "轰然崩解",
 *    and mob cries into "百兽狂啸", annotated with pop-art multiplier badges (e.g. "×6").
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
     * Comic pop-art multiplier badge indicating dense sound count, e.g. "×6".
     */
    public record MultiplierBadge(int count, String text) {
        public MultiplierBadge {
            if (count < 1) {
                throw new IllegalArgumentException("Badge count must be >= 1, got: " + count);
            }
            Objects.requireNonNull(text, "Badge text cannot be null");
        }

        public static MultiplierBadge of(int count) {
            return new MultiplierBadge(count, "×" + count);
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
     * A compound macro-onomatopoeia cluster promoted from multiple individual sounds.
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
            int priority
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
                    members != null && !members.isEmpty()
                            ? members.stream().mapToInt(SoundEntry::priority).max().orElse(5)
                            : 5);
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
        List<SpotlightZone> result = new ArrayList<>();
        for (SoundEntry s : sounds) {
            if (s != null && isCriticalThreat(s.soundId())) {
                result.add(new SpotlightZone(s.x(), s.y(), s.z(), s.soundId()));
            }
        }
        return Collections.unmodifiableList(result);
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
            List<SoundEntry> valid = new ArrayList<>(sounds.size());
            for (SoundEntry s : sounds) {
                if (s != null) valid.add(s);
            }
            return new ProcessedBattlefield(List.of(), valid, List.of());
        }

        // 2. Spatial Binning by 2.5m grid cell & ChorusCategory
        Map<GridKey, List<SoundEntry>> gridBuckets = new LinkedHashMap<>(Math.min(sounds.size(), 64));
        for (SoundEntry s : sounds) {
            if (s == null) continue;
            ChorusCategory cat = categorize(s.soundId(), s.semantics());
            GridKey key = gridKey(s.x(), s.y(), s.z(), cat);
            gridBuckets.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        List<ChorusCluster> clusters = new ArrayList<>();
        List<SoundEntry> unclustered = new ArrayList<>();

        // 3. Density promotion and spotlight suppression
        for (Map.Entry<GridKey, List<SoundEntry>> entry : gridBuckets.entrySet()) {
            List<SoundEntry> bucket = entry.getValue();
            ChorusCategory cat = entry.getKey().category();

            if (bucket.size() >= threshold) {
                double sumX = 0.0;
                double sumY = 0.0;
                double sumZ = 0.0;
                for (SoundEntry s : bucket) {
                    sumX += s.x();
                    sumY += s.y();
                    sumZ += s.z();
                }
                int count = bucket.size();
                double avgX = sumX / count;
                double avgY = sumY / count;
                double avgZ = sumZ / count;

                MultiplierBadge badge = MultiplierBadge.of(count);
                float clusterAlpha = evaluateSpotlightAlpha(avgX, avgY, avgZ, bucket.getFirst().soundId(), spotlights, 1.0f);
                clusters.add(new ChorusCluster(cat, cat.defaultTitle(), avgX, avgY, avgZ, badge, bucket, clusterAlpha));
            } else {
                for (SoundEntry s : bucket) {
                    float alpha = evaluateSpotlightAlpha(s.x(), s.y(), s.z(), s.soundId(), spotlights, s.alpha());
                    int priority = evaluateSpotlightPriority(s.x(), s.y(), s.z(), s.soundId(), spotlights, s.priority());
                    unclustered.add(s.withAlpha(alpha).withPriority(priority));
                }
            }
        }

        return new ProcessedBattlefield(clusters, unclustered, spotlights);
    }

    /**
     * Processes live subtitles directly from {@link SubtitlePool.Subtitle}.
     */
    public static ProcessedBattlefield processSubtitles(List<SubtitlePool.Subtitle> subtitles) {
        return processSubtitles(subtitles, DEFAULT_PROMOTION_THRESHOLD);
    }

    /**
     * Processes live subtitles directly with custom promotion threshold.
     */
    public static ProcessedBattlefield processSubtitles(List<SubtitlePool.Subtitle> subtitles, int promotionThreshold) {
        if (subtitles == null || subtitles.isEmpty()) {
            return ProcessedBattlefield.EMPTY;
        }
        List<SoundEntry> entries = new ArrayList<>(subtitles.size());
        for (SubtitlePool.Subtitle sub : subtitles) {
            if (sub != null) {
                entries.add(SoundEntry.fromSubtitle(sub));
            }
        }
        return process(entries, promotionThreshold);
    }

    /**
     * Converts a promoted ChorusCluster into a synthetic Subtitle representation
     * suitable for consumption by DynamicSubtitleLayout.
     */
    public static SubtitlePool.Subtitle createClusterSubtitle(ChorusCluster cluster, long now) {
        if (cluster == null || cluster.members().isEmpty()) return null;
        SoundEntry first = cluster.members().getFirst();
        long id = computeStableClusterId(cluster.category(), cluster.x(), cluster.y(), cluster.z());
        String badgeTitle = cluster.promotedText() + " " + cluster.badge().text();
        SoundSemantics semantics = switch (cluster.category()) {
            case ZOMBIE_CHORUS, BEAST_ROAR -> SoundSemantics.VOICE;
            case ARROW_RAIN -> SoundSemantics.DAMAGE;
            case ARTHROPOD_SWARM -> SoundSemantics.STEP;
            case STRUCTURE_COLLAPSE -> SoundSemantics.BREAK;
            case GENERIC_CHORUS -> SoundSemantics.AMBIENT;
        };
        long duration = 1_500_000_000L;
        return new SubtitlePool.Subtitle(id, cluster.translationKey(), badgeTitle, "cluster", "chorus",
                cluster.x(), cluster.y(), cluster.z(), cluster.x(), cluster.y(), cluster.z(),
                now, now + duration, now + 2 * duration, now, cluster.priority(),
                semantics, cluster.category().name(), false);
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
}
