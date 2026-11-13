package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.CaptionSettings;
import dev.kunkun.onomixel.subtitle.ThreatRegistry;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** Explicit special cases for attributable movement sounds, capture gating, and real rain surfaces. */
public final class SoundEventPolicy {
    private record SelfActionPolicy(String requiredCategory, double maxDistanceSquared) {}

    private static final Map<String, SelfActionPolicy> CUSTOM_SELF_ACTIONS = new ConcurrentHashMap<>();

    private SoundEventPolicy() {}

    /**
     * Registers a custom self-initiated sound suppression rule for the edge radar.
     *
     * @param soundId exact sound event ID
     * @param requiredCategory optional SoundSource name (e.g. "PLAYERS", "NEUTRAL"), or null to match any category
     * @param maxRadiusBlocks maximum feet distance from local player to treat as self-initiated (&gt; 0)
     */
    public static void registerLocalPlayerAction(String soundId, String requiredCategory, double maxRadiusBlocks) {
        Objects.requireNonNull(soundId, "soundId cannot be null");
        String normalized = soundId.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("soundId cannot be blank");
        }
        if (!Double.isFinite(maxRadiusBlocks) || maxRadiusBlocks <= 0.0 || maxRadiusBlocks > 16.0) {
            throw new IllegalArgumentException("maxRadiusBlocks must be finite and within (0, 16]: " + maxRadiusBlocks);
        }
        String categoryNorm = (requiredCategory != null && !requiredCategory.isBlank())
                ? requiredCategory.trim() : null;
        CUSTOM_SELF_ACTIONS.put(normalized, new SelfActionPolicy(categoryNorm, maxRadiusBlocks * maxRadiusBlocks));
    }

    public static void resetLocalPlayerActionsForTesting() {
        CUSTOM_SELF_ACTIONS.clear();
    }

    /**
     * Evaluates whether a sound event needs capturing under the current toggle matrix.
     * In radar-only mode ({@code !spatialWords && threatRadar}), non-threat sounds are rejected
     * before definition inference, stack walking, or mailbox queueing.
     */
    public static boolean shouldCaptureSound(CaptionSettings activeSettings, String soundId) {
        if (activeSettings == null || !activeSettings.enabled()) return false;
        if (activeSettings.spatialWords()) return true;
        if (!activeSettings.threatRadar()) return false;
        return ThreatRegistry.evaluate(soundId) != ThreatRegistry.ThreatLevel.NONE;
    }

    public static boolean isStep(String id) {
        return SoundSemantics.isStep(id);
    }

    public static boolean isGroundContact(String id) {
        return SoundSemantics.isStep(id) || SoundSemantics.isLanding(id);
    }

    public static boolean isRain(String id) {
        return "minecraft:weather.rain".equals(id) || "minecraft:weather.rain.above".equals(id);
    }

    /** Caller identity is checked only for footsteps; impacts and nearby entities are not suppressed. */
    public static boolean skipLocalStep(String id, BooleanSupplier insideLocalPlayerPlayback) {
        return isStep(id) && insideLocalPlayerPlayback.getAsBoolean();
    }

    public static boolean isLocalPlayerCall(String className, String methodName) {
        return "net.minecraft.client.player.LocalPlayer".equals(className) && "playSound".equals(methodName);
    }

    /** Marks vanilla player projectile launches and self teleports by sound identity, category and local source position. */
    public static boolean isLocalPlayerShot(String id, String category,
                                            double soundX, double soundY, double soundZ,
                                            double playerX, double playerY, double playerZ) {
        double maxDistanceSquared;
        if ("minecraft:entity.arrow.shoot".equals(id) || "minecraft:item.crossbow.shoot".equals(id)) {
            if (!"PLAYERS".equals(category)) return false;
            maxDistanceSquared = 1.0;
        } else if ("minecraft:item.trident.throw".equals(id)) {
            // TridentItem anchors its entity-bound sound at eyeY - 0.1, rather than the player's feet.
            if (!"PLAYERS".equals(category)) return false;
            maxDistanceSquared = 4.0;
        } else if ("minecraft:entity.ender_pearl.throw".equals(id)) {
            // EnderpearlItem plays ENDER_PEARL_THROW with SoundSource.NEUTRAL at the thrower's feet.
            if (!"NEUTRAL".equals(category)) return false;
            maxDistanceSquared = 1.0;
        } else if ("minecraft:entity.player.teleport".equals(id)) {
            // ThrownEnderpearl teleports the owner first, then plays PLAYER_TELEPORT under PLAYERS near their arrival pos.
            if (!"PLAYERS".equals(category)) return false;
            maxDistanceSquared = 1.0;
        } else {
            SelfActionPolicy custom = CUSTOM_SELF_ACTIONS.isEmpty() || id == null ? null : CUSTOM_SELF_ACTIONS.get(id);
            if (custom == null) {
                return false;
            }
            if (custom.requiredCategory() != null && !custom.requiredCategory().equals(category)) {
                return false;
            }
            maxDistanceSquared = custom.maxDistanceSquared();
        }
        double dx = soundX - playerX, dy = soundY - playerY, dz = soundZ - playerZ;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSquared;
    }
}
