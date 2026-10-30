package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundSemantics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Boss Choreography Director (首领级生物复合技能编排链).
 * <p>
 * Implements Chapter 7, Section 2 of the Onomixel design specification:
 * <ul>
 *   <li>Choreographed Boss Sequences: multi-stage tension chains for apex bosses
 *       (Warden, Ender Dragon, Wither).
 *       <p>Warden Tension Chain:
 *       <br>{@code 【潜伏心跳】 -> 【嗅闻警惕】 -> 【怒吼锁定】 -> 【音波冲击波】}
 *   </li>
 *   <li>Coordinated Avoidance Privilege (协同避让特权): during ultimate boss skills,
 *       projects a high-priority 3D spatial repulsion field that sweeps away and dims
 *       low-threat mob murmurs and ambient chatter, spotlighting the boss visual climax.</li>
 * </ul>
 */
public final class BossChoreographyDirector {
    public static final long SEQUENCE_TIMEOUT_NANOS = 10_000_000_000L;       // 10s idle resets chain
    public static final long ULTIMATE_FIELD_DURATION_NANOS = 2_500_000_000L; // 2.5s repulsion field
    public static final float DEFAULT_CLUTTER_ALPHA_SCALE = 0.15f;           // Clutter dimmed to 15%
    public static final double DEFAULT_MAX_REPULSION_DISPLACEMENT = 2.0;     // 2.0m outward displacement

    private final AtomicLong eventSequence = new AtomicLong(1);
    private final Map<BossType, BossTrack> tracks = new EnumMap<>(BossType.class);
    private final List<RepulsionField> activeFields = new ArrayList<>();
    private volatile List<RepulsionField> activeFieldsSnapshot = List.of();

    public enum BossType {
        WARDEN("Warden", "监守者"),
        ENDER_DRAGON("Ender Dragon", "末影龙"),
        WITHER("Wither", "凋灵");

        private final String englishName;
        private final String chineseName;

        BossType(String englishName, String chineseName) {
            this.englishName = englishName;
            this.chineseName = chineseName;
        }

        public String englishName() { return englishName; }
        public String chineseName() { return chineseName; }
    }

    public record BossStage(
            String id,
            String displayName,
            String translationKey,
            int stepIndex,
            float visualScale,
            double repulsionRadius,
            boolean isUltimate
    ) {}

    public record ChoreographedBossEvent(
            long eventId,
            BossType bossType,
            BossStage stage,
            int comboCount,
            CaptionPlacement.Point position,
            long timestampNanos,
            float visualScale,
            double repulsionRadius,
            boolean isUltimate,
            boolean isClimax
    ) {}

    public record RepulsionField(
            long fieldId,
            BossType bossType,
            CaptionPlacement.Point center,
            double radius,
            double maxDisplacement,
            float clutterAlphaScale,
            long startedNanos,
            long expiresNanos
    ) {
        public RepulsionField {
            Objects.requireNonNull(bossType, "bossType must not be null");
            center = (center != null) ? center : new CaptionPlacement.Point(0, 0, 0);
            radius = Double.isFinite(radius) && radius > 0.0 ? radius : 1.0;
            maxDisplacement = Double.isFinite(maxDisplacement) && maxDisplacement > 0.0 ? maxDisplacement : DEFAULT_MAX_REPULSION_DISPLACEMENT;
            clutterAlphaScale = Float.isFinite(clutterAlphaScale) ? Math.clamp(clutterAlphaScale, 0.0f, 1.0f) : DEFAULT_CLUTTER_ALPHA_SCALE;
        }

        public boolean isActive(long nowNanos) {
            return nowNanos >= startedNanos && nowNanos < expiresNanos;
        }

        public double distanceSquared(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return Double.POSITIVE_INFINITY;
            }
            double dx = center.x() - x;
            double dy = center.y() - y;
            double dz = center.z() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    public record PrivilegeResult(
            boolean isSuppressed,
            float alphaMultiplier,
            CaptionPlacement.Point displacement
    ) {
        public static final PrivilegeResult UNCHANGED =
                new PrivilegeResult(false, 1.0f, new CaptionPlacement.Point(0, 0, 0));
    }

    private static final class BossTrack {
        final BossType bossType;
        int currentStep = 0;
        int comboCount = 0;
        long lastEventTimeNanos = -1L;
        CaptionPlacement.Point lastPosition = new CaptionPlacement.Point(0, 0, 0);

        BossTrack(BossType bossType) {
            this.bossType = bossType;
        }

        void reset() {
            currentStep = 0;
            comboCount = 0;
            lastEventTimeNanos = -1L;
        }
    }

    private static final BossChoreographyDirector INSTANCE = new BossChoreographyDirector();

    public static BossChoreographyDirector getInstance() {
        return INSTANCE;
    }

    public BossChoreographyDirector() {
        for (BossType type : BossType.values()) {
            tracks.put(type, new BossTrack(type));
        }
    }

    // ==================== Boss Stage Definitions ====================

    // Warden Stages
    public static final BossStage WARDEN_HEARTBEAT = new BossStage(
            "warden_heartbeat", "【潜伏心跳】", "onomixel.boss.warden.heartbeat",
            1, 1.15f, 4.0, false);
    public static final BossStage WARDEN_SNIFF = new BossStage(
            "warden_sniff", "【嗅闻警惕】", "onomixel.boss.warden.sniff",
            2, 1.30f, 6.0, false);
    public static final BossStage WARDEN_ROAR = new BossStage(
            "warden_roar", "【怒吼锁定】", "onomixel.boss.warden.roar",
            3, 1.60f, 10.0, false);
    public static final BossStage WARDEN_SONIC_BOOM = new BossStage(
            "warden_sonic_boom", "【音波冲击波】", "onomixel.boss.warden.sonic_boom",
            4, 2.20f, 16.0, true);

    public static final List<BossStage> WARDEN_CHAIN = List.of(
            WARDEN_HEARTBEAT, WARDEN_SNIFF, WARDEN_ROAR, WARDEN_SONIC_BOOM);

    // Ender Dragon Stages
    public static final BossStage DRAGON_FLAP = new BossStage(
            "dragon_flap", "【振翅疾旋】", "onomixel.boss.dragon.flap",
            1, 1.15f, 5.0, false);
    public static final BossStage DRAGON_GROWL = new BossStage(
            "dragon_growl", "【龙威咆哮】", "onomixel.boss.dragon.growl",
            2, 1.40f, 8.0, false);
    public static final BossStage DRAGON_BREATH = new BossStage(
            "dragon_breath", "【龙息爆轰】", "onomixel.boss.dragon.breath",
            3, 1.85f, 14.0, true);
    public static final BossStage DRAGON_DEATH = new BossStage(
            "dragon_death", "【终焉碎裂】", "onomixel.boss.dragon.death",
            4, 2.40f, 20.0, true);

    public static final List<BossStage> DRAGON_CHAIN = List.of(
            DRAGON_FLAP, DRAGON_GROWL, DRAGON_BREATH, DRAGON_DEATH);

    // Wither Stages
    public static final BossStage WITHER_SPAWN = new BossStage(
            "wither_spawn", "【诞世充能】", "onomixel.boss.wither.spawn",
            1, 1.80f, 14.0, true);
    public static final BossStage WITHER_SKULL = new BossStage(
            "wither_skull", "【凋灵飞弹】", "onomixel.boss.wither.shoot",
            2, 1.25f, 6.0, false);
    public static final BossStage WITHER_ARMOR = new BossStage(
            "wither_armor", "【凋灵铠化】", "onomixel.boss.wither.armor",
            3, 1.45f, 8.0, false);
    public static final BossStage WITHER_DEATH = new BossStage(
            "wither_death", "【凋灭爆鸣】", "onomixel.boss.wither.death",
            4, 2.10f, 18.0, true);

    public static final List<BossStage> WITHER_CHAIN = List.of(
            WITHER_SPAWN, WITHER_SKULL, WITHER_ARMOR, WITHER_DEATH);

    // ==================== Event Handling & Stage Progression ====================

    /**
     * Registers a sound event and advances the choreography track if it belongs to a Boss sequence.
     */
    public ChoreographedBossEvent onSoundEvent(String soundId, double x, double y, double z, long nowNanos) {
        if (soundId == null || soundId.isEmpty()) return null;

        BossType type = identifyBoss(soundId);
        if (type == null) return null;

        BossStage matchedStage = identifyStage(type, soundId);
        if (matchedStage == null) return null;

        double sx = Double.isFinite(x) ? x : 0.0;
        double sy = Double.isFinite(y) ? y : 0.0;
        double sz = Double.isFinite(z) ? z : 0.0;
        CaptionPlacement.Point pos = new CaptionPlacement.Point(sx, sy, sz);
        return advanceTrack(type, matchedStage, pos, nowNanos);
    }

    /**
     * Explicitly advances a boss stage (convenient for testing or scripted animation hooks).
     */
    public ChoreographedBossEvent advanceStage(BossType type, BossStage stage, CaptionPlacement.Point pos, long nowNanos) {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(stage, "stage must not be null");
        CaptionPlacement.Point safePos = (pos != null && Double.isFinite(pos.x()) && Double.isFinite(pos.y()) && Double.isFinite(pos.z()))
                ? pos : new CaptionPlacement.Point(0, 0, 0);
        return advanceTrack(type, stage, safePos, nowNanos);
    }

    private synchronized ChoreographedBossEvent advanceTrack(BossType type, BossStage matchedStage,
                                                             CaptionPlacement.Point pos, long nowNanos) {
        BossTrack track = tracks.get(type);
        if (track == null) return null;

        // Check if track has timed out
        if (track.lastEventTimeNanos > 0 && (nowNanos - track.lastEventTimeNanos) > SEQUENCE_TIMEOUT_NANOS) {
            track.reset();
        }

        // Sequential combo progression
        boolean isSequential = (matchedStage.stepIndex() == track.currentStep + 1);
        if (isSequential) {
            track.currentStep = matchedStage.stepIndex();
            track.comboCount++;
        } else if (matchedStage.stepIndex() == 1) {
            // Restart chain at step 1
            track.currentStep = 1;
            track.comboCount = 1;
        } else {
            // Jumped to another stage: update step, reset combo
            track.currentStep = matchedStage.stepIndex();
            track.comboCount = 1;
        }

        track.lastEventTimeNanos = nowNanos;
        track.lastPosition = pos;

        int chainLength = getStages(type).size();
        boolean isClimax = (track.currentStep == chainLength);

        // If this is an ultimate skill or climax, activate Coordinated Avoidance Repulsion Field
        if (matchedStage.isUltimate()) {
            activeFields.add(new RepulsionField(
                    eventSequence.incrementAndGet(),
                    type,
                    pos,
                    matchedStage.repulsionRadius(),
                    DEFAULT_MAX_REPULSION_DISPLACEMENT,
                    DEFAULT_CLUTTER_ALPHA_SCALE,
                    nowNanos,
                    nowNanos + ULTIMATE_FIELD_DURATION_NANOS
            ));
        }

        // Clean expired repulsion fields and update snapshot
        activeFields.removeIf(field -> !field.isActive(nowNanos));
        activeFieldsSnapshot = List.copyOf(activeFields);

        return new ChoreographedBossEvent(
                eventSequence.incrementAndGet(),
                type,
                matchedStage,
                track.comboCount,
                pos,
                nowNanos,
                matchedStage.visualScale(),
                matchedStage.repulsionRadius(),
                matchedStage.isUltimate(),
                isClimax
        );
    }

    // ==================== Coordinated Avoidance Privilege ====================

    /**
     * Applies Coordinated Avoidance Privilege: dims low-threat clutter and pushes it away
     * from active boss ultimate repulsion fields.
     * <p>
     * Lock-free read path using immutable snapshot to ensure zero latency during high-frequency layout.
     */
    public PrivilegeResult evaluatePrivilege(SubtitlePool.Subtitle subtitle, long nowNanos) {
        if (subtitle == null) return PrivilegeResult.UNCHANGED;
        var fields = activeFieldsSnapshot;
        if (fields.isEmpty()) return PrivilegeResult.UNCHANGED;

        // Boss sounds themselves and critical attacks are never suppressed
        if (isBossSound(subtitle.soundId()) || subtitle.priority() >= 10) {
            return PrivilegeResult.UNCHANGED;
        }

        double subX = subtitle.x();
        double subY = subtitle.y();
        double subZ = subtitle.z();
        if (!Double.isFinite(subX) || !Double.isFinite(subY) || !Double.isFinite(subZ)) {
            return PrivilegeResult.UNCHANGED;
        }

        for (RepulsionField field : fields) {
            if (!field.isActive(nowNanos)) continue;

            double distSq = field.distanceSquared(subX, subY, subZ);
            double radius = field.radius();
            if (distSq < radius * radius) {
                // Within repulsion field!
                double dist = Math.sqrt(distSq);
                CaptionPlacement.Point disp;
                if (dist < 1e-4) {
                    // Exact overlap: push along positive Y
                    disp = new CaptionPlacement.Point(0, field.maxDisplacement(), 0);
                } else {
                    double push = Math.min(field.maxDisplacement(), (radius - dist) * 0.25);
                    double dx = (subX - field.center().x()) / dist * push;
                    double dy = (subY - field.center().y()) / dist * push;
                    double dz = (subZ - field.center().z()) / dist * push;
                    disp = new CaptionPlacement.Point(dx, dy, dz);
                }

                // If low-threat clutter, suppress alpha
                boolean isClutter = isClutter(subtitle.semantics());
                float alphaScale = isClutter ? field.clutterAlphaScale() : 0.70f;
                return new PrivilegeResult(isClutter, alphaScale, disp);
            }
        }

        return PrivilegeResult.UNCHANGED;
    }

    private static boolean isClutter(SoundSemantics semantics) {
        if (semantics == null) return true;
        return switch (semantics) {
            case STEP, GALLOP, IDLE_VOICE, VOICE, AMBIENT, OTHER -> true;
            case ATTACK, DAMAGE, HEAVY_STEP, LANDING, BREAK, PICKUP, INTERACTION -> false;
        };
    }

    /**
     * Batch cleans expired fields once per frame to avoid hot removeIf iterations.
     */
    public synchronized void cleanExpiredFields(long nowNanos) {
        if (activeFields.removeIf(field -> !field.isActive(nowNanos))) {
            activeFieldsSnapshot = List.copyOf(activeFields);
        }
    }

    public synchronized List<RepulsionField> activeRepulsionFields(long nowNanos) {
        cleanExpiredFields(nowNanos);
        return Collections.unmodifiableList(new ArrayList<>(activeFields));
    }

    public synchronized void reset() {
        for (BossTrack track : tracks.values()) {
            track.reset();
        }
        activeFields.clear();
        activeFieldsSnapshot = List.of();
    }

    // ==================== Identification Helpers ====================

    public static BossType identifyBoss(String soundId) {
        if (soundId == null) return null;
        String id = soundId.toLowerCase(Locale.ROOT);
        if (id.contains("warden")) return BossType.WARDEN;
        if (id.contains("ender_dragon") || id.contains("dragon")) return BossType.ENDER_DRAGON;
        if (id.contains("wither")) return BossType.WITHER;
        return null;
    }

    public static boolean isBossSound(String soundId) {
        return identifyBoss(soundId) != null;
    }

    public static List<BossStage> getStages(BossType type) {
        if (type == null) return List.of();
        return switch (type) {
            case WARDEN -> WARDEN_CHAIN;
            case ENDER_DRAGON -> DRAGON_CHAIN;
            case WITHER -> WITHER_CHAIN;
        };
    }

    public static BossStage identifyStage(BossType type, String soundId) {
        if (type == null || soundId == null) return null;
        String id = soundId.toLowerCase(Locale.ROOT);

        return switch (type) {
            case WARDEN -> {
                if (id.contains("sonic_boom") || id.contains("sonic_charge")) yield WARDEN_SONIC_BOOM;
                if (id.contains("roar")) yield WARDEN_ROAR;
                if (id.contains("sniff")) yield WARDEN_SNIFF;
                if (id.contains("heartbeat")) yield WARDEN_HEARTBEAT;
                yield null;
            }
            case ENDER_DRAGON -> {
                if (id.contains("death")) yield DRAGON_DEATH;
                if (id.contains("shoot") || id.contains("breath")) yield DRAGON_BREATH;
                if (id.contains("growl")) yield DRAGON_GROWL;
                if (id.contains("flap")) yield DRAGON_FLAP;
                yield null;
            }
            case WITHER -> {
                if (id.contains("death")) yield WITHER_DEATH;
                if (id.contains("spawn")) yield WITHER_SPAWN;
                if (id.contains("shoot")) yield WITHER_SKULL;
                if (id.contains("ambient") || id.contains("hurt")) yield WITHER_ARMOR;
                yield null;
            }
        };
    }
}
