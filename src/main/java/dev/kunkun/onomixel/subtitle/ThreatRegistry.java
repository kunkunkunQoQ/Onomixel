package dev.kunkun.onomixel.subtitle;

import java.util.Locale;

/**
 * P0/P1 Comprehensive high-threat sound classification for off-screen radar and priority tactical alerts.
 * Covers all lethal events in Minecraft: explosions, boss attacks, snipers, aerial dives,
 * raids, lasers, and ambush strikes.
 */
public final class ThreatRegistry {
    public enum ThreatLevel { CRITICAL, ELEVATED, TACTICAL, NONE }

    public static ThreatLevel evaluate(String soundId) {
        if (soundId == null || soundId.isEmpty()) return ThreatLevel.NONE;
        String id = soundId.toLowerCase(Locale.ROOT);

        // P0 Critical Threats: Instant-Kill, Explosions, Boss Attacks, Lightning
        if (id.contains("creeper.primed")
                || id.contains("tnt.primed")
                || id.contains("generic.explode")
                || id.contains("warden.sonic_boom")
                || id.contains("warden.roar")
                || id.contains("warden.emerge")
                || id.contains("wither.spawn")
                || id.contains("wither.shoot")
                || id.contains("ender_dragon.growl")
                || id.contains("ender_dragon.shoot")
                || id.contains("ghast.warn")
                || id.contains("ghast.shoot")
                || id.contains("lightning_bolt.thunder")
                || id.contains("lightning_bolt.impact")) {
            return ThreatLevel.CRITICAL;
        }

        // P1 Elevated Threats: Snipers, Aerial Dives, Magic & Ambush Strikes
        if (id.contains("skeleton.shoot")
                || id.contains("stray.shoot")
                || id.contains("bogged.shoot")
                || (id.contains("arrow.hit") && !id.contains("hit_player"))
                || id.contains("arrow.shoot")
                || id.contains("trident.hit")
                || id.contains("trident.throw")
                || id.contains("phantom.swoop")
                || id.contains("phantom.bite")
                || id.contains("blaze.shoot")
                || id.contains("breeze.shoot")
                || id.contains("breeze.charge")
                || id.contains("ravager.roar")
                || id.contains("ravager.attack")
                || id.contains("witch.throw")
                || id.contains("evoker_fangs.attack")
                || id.contains("evoker.prepare_attack")
                || id.contains("pillager.shoot")
                || id.contains("crossbow.shoot")
                || id.contains("guardian.attack")
                || id.contains("elder_guardian.curse")
                || id.contains("drowned.shoot")
                || id.contains("piglin_brute.angry")
                || id.contains("enderman.scream")
                || id.contains("enderman.stare")
                || id.contains("shulker_bullet.hit")
                || id.contains("raid.horn")
                || id.contains("warden.heartbeat")
                || id.contains("warden.sniff")) {
            return ThreatLevel.ELEVATED;
        }

        // Tactical Callouts (Village defense, Bell sonar)
        if (id.contains("bell.use") || id.contains("bell.resonate")) {
            return ThreatLevel.TACTICAL;
        }

        return ThreatLevel.NONE;
    }

    /**
     * Discrete radar countdown lifetimes decoupled from 3D visual subtitle rendering.
     * Keeps urgent tactical threats visible on the radar perimeter for appropriate reaction windows.
     */
    public static long radarDurationNanos(String soundId) {
        return radarDurationNanos(soundId, 800_000_000L);
    }

    public static long radarDurationNanos(String soundId, long fallbackNanos) {
        if (soundId == null || soundId.isEmpty()) return 0L;
        String id = soundId.toLowerCase(Locale.ROOT);

        // Cat 3: 持续引信/倒计时
        if (id.contains("tnt.primed")) return 3_000_000_000L; // 3000ms
        if (id.contains("creeper.primed")) return 1_500_000_000L; // 1500ms (30 ticks)
        if (id.contains("ghast.warn") || id.contains("evoker.prepare_attack")) return 1_200_000_000L; // 1200ms
        if (id.contains("breeze.charge")) return 1_000_000_000L; // 1000ms

        // Cat 6: Boss 史诗级
        if (id.contains("wither.spawn") || id.contains("warden.emerge")) return 2_000_000_000L; // 2000ms
        if (id.contains("warden.roar") || id.contains("ender_dragon.growl")) return 1_500_000_000L; // 1500ms

        // Cat 4: 爆发破坏
        if (id.contains("warden.sonic_boom")) return 800_000_000L; // 800ms
        if (id.contains("generic.explode") || id.contains("lightning_bolt.thunder")) return 750_000_000L; // 750ms

        // Cat 5: 近战追踪
        if (id.contains("raid.horn") || id.contains("bell.use") || id.contains("bell.resonate")) return 1_200_000_000L; // 1200ms
        if (id.contains("phantom.swoop")
                || id.contains("guardian.attack")
                || id.contains("elder_guardian.curse")
                || id.contains("enderman.scream")
                || id.contains("enderman.stare")) return 900_000_000L; // 900ms
        if (id.contains("ravager.roar") || id.contains("ravager.attack")) return 850_000_000L; // 850ms
        if (id.contains("piglin_brute.angry")
                || id.contains("warden.heartbeat")
                || id.contains("warden.sniff")) return 800_000_000L; // 800ms
        if (id.contains("phantom.bite")) return 700_000_000L; // 700ms

        // Cat 2: 远程发射/狙击 (700ms)
        if (id.contains("skeleton.shoot")
                || id.contains("stray.shoot")
                || id.contains("bogged.shoot")
                || id.contains("arrow.shoot")
                || id.contains("crossbow.shoot")
                || id.contains("pillager.shoot")
                || id.contains("drowned.shoot")
                || id.contains("trident.throw")
                || id.contains("blaze.shoot")
                || id.contains("breeze.shoot")
                || id.contains("witch.throw")
                || id.contains("ghast.shoot")
                || id.contains("wither.shoot")) return 700_000_000L; // 700ms

        // Cat 1: 瞬态落点 (450ms)
        if ((id.contains("arrow.hit") && !id.contains("hit_player"))
                || id.contains("trident.hit")
                || id.contains("shulker_bullet.hit")
                || id.contains("lightning_bolt.impact")
                || id.contains("evoker_fangs.attack")) return 450_000_000L; // 450ms

        ThreatLevel level = evaluate(soundId);
        if (level == ThreatLevel.CRITICAL || level == ThreatLevel.ELEVATED) {
            return fallbackNanos > 0 ? fallbackNanos : 800_000_000L;
        }

        return 0L;
    }

    public static boolean isEligibleForRadar(String soundId, double distance) {
        double maxDistance = maxRadarDistance(evaluate(soundId));
        return maxDistance > 0.0 && distance <= maxDistance;
    }

    public static double maxRadarDistance(ThreatLevel level) {
        return switch (level) {
            case CRITICAL -> 24.0;
            case ELEVATED -> 16.0;
            case TACTICAL -> 32.0;
            case NONE -> 0.0;
        };
    }

    /**
     * Resolves human-readable translation key for the threat reminder display.
     */
    public static String resolveThreatNameKey(String soundId) {
        if (soundId == null || soundId.isEmpty()) return "onomixel.threat.generic_critical";
        String id = soundId.toLowerCase(Locale.ROOT);

        // Explosives
        if (id.contains("creeper.primed")) return "onomixel.threat.creeper_primed";
        if (id.contains("tnt.primed")) return "onomixel.threat.tnt_primed";
        if (id.contains("generic.explode")) return "onomixel.threat.explode";

        // Bosses
        if (id.contains("warden.sonic_boom")) return "onomixel.threat.warden_sonic";
        if (id.contains("warden.roar")) return "onomixel.threat.warden_roar";
        if (id.contains("warden.emerge")) return "onomixel.threat.warden_emerge";
        if (id.contains("wither.spawn")) return "onomixel.threat.wither_spawn";
        if (id.contains("wither.shoot")) return "onomixel.threat.wither_shoot";
        if (id.contains("ender_dragon.growl") || id.contains("ender_dragon.shoot")) return "onomixel.threat.dragon";

        // Nether & Aerial
        if (id.contains("ghast.warn") || id.contains("ghast.shoot")) return "onomixel.threat.ghast";
        if (id.contains("phantom.swoop") || id.contains("phantom.bite")) return "onomixel.threat.phantom_swoop";
        if (id.contains("blaze.shoot")) return "onomixel.threat.blaze_shoot";
        if (id.contains("breeze.shoot") || id.contains("breeze.charge")) return "onomixel.threat.breeze";

        // Projectile impacts (landing point)
        if (id.contains("arrow.hit") && !id.contains("hit_player")) return "onomixel.threat.arrow_hit";
        if (id.contains("trident.hit")) return "onomixel.threat.trident_hit";

        // Snipers & Archery (shooters)
        if (id.contains("skeleton.shoot") || id.contains("stray.shoot") || id.contains("bogged.shoot"))
            return "onomixel.threat.skeleton_shoot";
        if (id.contains("arrow.shoot")) return "onomixel.threat.arrow_shoot";
        if (id.contains("pillager.shoot")) return "onomixel.threat.pillager_shoot";
        if (id.contains("crossbow.shoot")) return "onomixel.threat.crossbow_shoot";
        if (id.contains("drowned.shoot") || id.contains("trident.throw")) return "onomixel.threat.drowned_trident";

        // Raids & Illagers
        if (id.contains("ravager.roar") || id.contains("ravager.attack")) return "onomixel.threat.ravager";
        if (id.contains("evoker_fangs.attack") || id.contains("evoker.prepare_attack")) return "onomixel.threat.evoker_fangs";
        if (id.contains("witch.throw")) return "onomixel.threat.witch_throw";
        if (id.contains("raid.horn")) return "onomixel.threat.raid_horn";

        // Ocean & Nether Brutes
        if (id.contains("guardian.attack") || id.contains("elder_guardian.curse")) return "onomixel.threat.guardian";
        if (id.contains("piglin_brute.angry")) return "onomixel.threat.piglin_brute";
        if (id.contains("enderman.scream") || id.contains("enderman.stare")) return "onomixel.threat.enderman_scream";
        if (id.contains("shulker_bullet.hit")) return "onomixel.threat.shulker";

        // Lightning
        if (id.contains("lightning_bolt.thunder") || id.contains("lightning_bolt.impact")) return "onomixel.threat.lightning";

        return evaluate(soundId) == ThreatLevel.CRITICAL
                ? "onomixel.threat.generic_critical"
                : "onomixel.threat.generic_elevated";
    }
}
