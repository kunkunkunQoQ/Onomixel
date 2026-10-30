package dev.kunkun.onomixel.sound;

import java.util.function.BooleanSupplier;

/** Explicit special cases for attributable movement sounds and real rain surfaces. */
public final class SoundEventPolicy {
    private SoundEventPolicy() {}

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

    /** Marks vanilla player projectile launches by sound identity, category and local source position. */
    public static boolean isLocalPlayerShot(String id, String category,
                                            double soundX, double soundY, double soundZ,
                                            double playerX, double playerY, double playerZ) {
        if (!"PLAYERS".equals(category)) return false;
        double maxDistanceSquared;
        if ("minecraft:entity.arrow.shoot".equals(id) || "minecraft:item.crossbow.shoot".equals(id)) {
            maxDistanceSquared = 1.0;
        } else if ("minecraft:item.trident.throw".equals(id)) {
            // TridentItem anchors its entity-bound sound at eyeY - 0.1, rather than the player's feet.
            maxDistanceSquared = 4.0;
        } else {
            return false;
        }
        double dx = soundX - playerX, dy = soundY - playerY, dz = soundZ - playerZ;
        return dx * dx + dy * dy + dz * dz <= maxDistanceSquared;
    }
}
