package dev.kunkun.onomixel.sound;

/**
 * Physical acoustic delay simulation for distant atmospheric events (thunder, distant explosions).
 * Sound speed is modeled at 343 m/s (~17.15 blocks per game tick).
 * Zero Minecraft runtime dependencies for pure JUnit testability.
 */
public final class SpeedOfSoundDelayBuffer {
    public static final double SPEED_OF_SOUND = 343.0; // m/s in air

    private SpeedOfSoundDelayBuffer() {}

    public static long calculateDelayNanos(double distance) {
        return calculateDelayNanos(distance, false);
    }

    public static long calculateDelayNanos(double distance, boolean isUnderwater) {
        if (!Double.isFinite(distance) || distance < 16.0) return 0L;
        double speed = AcousticEnvironmentReactor.calculateSpeedOfSound(isUnderwater, isUnderwater);
        // Cap max acoustic delay to 3.0 seconds to prevent stale subtitle delivery
        double seconds = Math.min(3.0, distance / speed);
        return Math.round(seconds * 1_000_000_000L);
    }

    public static boolean shouldDelay(String soundId, double distance) {
        if (soundId == null || distance < 16.0) return false;
        return soundId.contains("lightning_bolt.thunder")
                || soundId.contains("raid.horn")
                || soundId.contains("wither.spawn");
    }
}
