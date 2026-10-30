package dev.kunkun.onomixel.subtitle;

/** A monotonic active-time clock. Paused wall time never ages an existing subtitle. */
public final class SubtitleClock {
    private long lastWallNanos;
    private long activeNanos;
    private boolean initialized;
    private boolean wasPaused;

    public long sample(long wallNanos, boolean paused) {
        if (initialized && !paused && !wasPaused) {
            activeNanos += Math.max(0, wallNanos - lastWallNanos);
        }
        initialized = true;
        lastWallNanos = wallNanos;
        wasPaused = paused;
        return activeNanos;
    }

    public void reset() {
        initialized = false;
        activeNanos = 0;
    }
}
