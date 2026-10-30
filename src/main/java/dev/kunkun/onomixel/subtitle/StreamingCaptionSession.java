package dev.kunkun.onomixel.subtitle;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Objects;

/**
 * Continuous Streaming Caption Session (循环长音流式追踪与平滑生命周期).
 * <p>
 * Implements Chapter 7, Section 1 of the Onomixel design specification:
 * <ul>
 *   <li>Weak Reference Sound Anchor: holds the underlying audio instance via {@link WeakReference}
 *       with Heartbeat Renewal to keep the caption alive without leaking memory or crashing on GC.</li>
 *   <li>Dynamic Translation Tracking: hooks the host entity's per-frame smooth/interpolated coordinates
 *       so subtitles move seamlessly with fast-traveling vehicles or flying players.</li>
 *   <li>Breathing Motion Scale: applies a subtle continuous breathing scale oscillation
 *       between 0.96x and 1.04x during playback.</li>
 *   <li>Smooth Exit Gate: triggers an exact 0.35s smooth fade-out when the sound stops or is collected,
 *       avoiding harsh, abrupt cutoffs.</li>
 * </ul>
 */
public final class StreamingCaptionSession {
    public static final long FADE_IN_DURATION_NANOS = 100_000_000L;    // 0.10s fade-in
    public static final long FADE_OUT_DURATION_NANOS = 350_000_000L;   // 0.35s fade-out (Smooth Exit Gate)
    public static final long HEARTBEAT_TIMEOUT_NANOS = 800_000_000L;   // 0.80s without heartbeat triggers exit gate

    public static final float BREATHING_MIN_SCALE = 0.96f;
    public static final float BREATHING_MAX_SCALE = 1.04f;
    public static final double BREATHING_FREQUENCY_HZ = 1.20; // 1.2 Hz gentle oscillation

    public static final SoundSourceHandle DEFAULT_HANDLE = () -> false;

    private static final java.util.concurrent.ConcurrentMap<Long, StreamingCaptionSession> ACTIVE_BY_ID = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<String, java.util.concurrent.ConcurrentMap<Long, StreamingCaptionSession>> ACTIVE_BY_SOUND = new java.util.concurrent.ConcurrentHashMap<>();

    public static void registerSession(StreamingCaptionSession session) {
        if (session == null) return;
        ACTIVE_BY_ID.put(session.id(), session);
        ACTIVE_BY_SOUND.computeIfAbsent(session.soundId(), k -> new java.util.concurrent.ConcurrentHashMap<>()).put(session.id(), session);
    }

    public static StreamingCaptionSession getSession(long id) {
        return ACTIVE_BY_ID.get(id);
    }

    public static StreamingCaptionSession getSessionBySound(String soundId) {
        if (soundId == null) return null;
        var map = ACTIVE_BY_SOUND.get(soundId);
        if (map == null || map.isEmpty()) return null;
        for (StreamingCaptionSession s : map.values()) {
            if (s.isActive()) return s;
        }
        return map.values().iterator().hasNext() ? map.values().iterator().next() : null;
    }

    public static java.util.Collection<StreamingCaptionSession> getSessionsBySound(String soundId) {
        if (soundId == null) return java.util.List.of();
        var map = ACTIVE_BY_SOUND.get(soundId);
        return map != null ? map.values() : java.util.List.of();
    }

    public static void removeSession(long id) {
        StreamingCaptionSession removed = ACTIVE_BY_ID.remove(id);
        if (removed != null) {
            var map = ACTIVE_BY_SOUND.get(removed.soundId());
            if (map != null) {
                map.remove(id);
                if (map.isEmpty()) {
                    ACTIVE_BY_SOUND.remove(removed.soundId(), map);
                }
            }
        }
    }

    public static void clearAllSessions() {
        ACTIVE_BY_ID.clear();
        ACTIVE_BY_SOUND.clear();
    }

    public static java.util.Collection<StreamingCaptionSession> activeSessions() {
        return ACTIVE_BY_ID.values();
    }

    @FunctionalInterface
    public interface SoundSourceHandle {
        boolean isStopped();
    }

    @FunctionalInterface
    public interface PositionSupplier {
        CaptionPlacement.Point getPosition(float partialTicks);
    }

    public enum SessionState {
        STARTING,
        STREAMING,
        FADING_OUT,
        TERMINATED
    }

    public record SessionSnapshot(
            long id,
            String soundId,
            String translationKey,
            SessionState state,
            CaptionPlacement.Point position,
            float scale,
            float alpha,
            boolean isStopped,
            long elapsedNanos
    ) {
        public SessionSnapshot {
            Objects.requireNonNull(soundId, "soundId must not be null");
            Objects.requireNonNull(state, "state must not be null");
            position = (position != null) ? position : new CaptionPlacement.Point(0, 0, 0);
        }
    }

    private final long id;
    private final String soundId;
    private final String translationKey;
    private final WeakReference<SoundSourceHandle> soundHandleRef;
    private volatile PositionSupplier positionSupplier;
    private volatile CaptionPlacement.Point lastKnownPosition;

    private final long createdNanos;
    private volatile long lastHeartbeatNanos;
    private volatile long stoppedAtNanos = -1L;
    private volatile SessionState state = SessionState.STARTING;

    public StreamingCaptionSession(long id, String soundId, String translationKey,
                                   SoundSourceHandle soundHandle, CaptionPlacement.Point initialPosition,
                                   long nowNanos) {
        this.id = id;
        this.soundId = Objects.requireNonNull(soundId, "soundId must not be null");
        this.translationKey = translationKey != null ? translationKey : soundId;
        this.soundHandleRef = new WeakReference<>(soundHandle != null ? soundHandle : DEFAULT_HANDLE);
        this.lastKnownPosition = initialPosition != null ? initialPosition : new CaptionPlacement.Point(0, 0, 0);
        this.createdNanos = nowNanos;
        this.lastHeartbeatNanos = nowNanos;
    }

    public StreamingCaptionSession(long id, String soundId, String translationKey,
                                   SoundSourceHandle soundHandle, PositionSupplier positionSupplier,
                                   long nowNanos) {
        this(id, soundId, translationKey, soundHandle,
                positionSupplier != null ? positionSupplier.getPosition(0.0f) : new CaptionPlacement.Point(0, 0, 0),
                nowNanos);
        this.positionSupplier = positionSupplier;
    }

    public long id() { return id; }
    public String soundId() { return soundId; }
    public String translationKey() { return translationKey; }
    public SessionState state() { return state; }
    public long createdNanos() { return createdNanos; }
    public long lastHeartbeatNanos() { return lastHeartbeatNanos; }
    public long stoppedAtNanos() { return stoppedAtNanos; }

    public void setPositionSupplier(PositionSupplier supplier) {
        this.positionSupplier = supplier;
    }

    public void updatePosition(double x, double y, double z) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return;
        this.lastKnownPosition = new CaptionPlacement.Point(x, y, z);
    }

    public void updatePosition(CaptionPlacement.Point point) {
        if (point != null && Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z())) {
            this.lastKnownPosition = point;
        }
    }

    public CaptionPlacement.Point currentPosition(float partialTicks) {
        PositionSupplier supplier = positionSupplier;
        if (supplier != null) {
            try {
                CaptionPlacement.Point pos = supplier.getPosition(partialTicks);
                if (pos != null && Double.isFinite(pos.x()) && Double.isFinite(pos.y()) && Double.isFinite(pos.z())) {
                    lastKnownPosition = pos;
                }
            } catch (Throwable ignored) {
                // Ignore position supplier failures gracefully
            }
        }
        return lastKnownPosition;
    }

    /**
     * Heartbeat Renewal: keeps the streaming session alive and resets the idle timeout.
     */
    public void heartbeat(long nowNanos) {
        if (state == SessionState.TERMINATED) return;

        this.lastHeartbeatNanos = Math.max(lastHeartbeatNanos, nowNanos);

        // If currently in FADING_OUT due to a temporary heartbeat pause but sound is still active,
        // we can renew if the sound handle is confirmed not stopped (or null).
        if (state == SessionState.FADING_OUT && stoppedAtNanos > 0) {
            SoundSourceHandle handle = soundHandleRef.get();
            if (handle == null || !handle.isStopped()) {
                state = SessionState.STREAMING;
                stoppedAtNanos = -1L;
            }
        } else if (state == SessionState.STARTING && (nowNanos - createdNanos) >= FADE_IN_DURATION_NANOS) {
            state = SessionState.STREAMING;
        }
    }

    /**
     * Triggers the smooth exit gate (0.35s fade-out).
     */
    public void stop(long nowNanos) {
        if (state == SessionState.TERMINATED || state == SessionState.FADING_OUT) return;
        state = SessionState.FADING_OUT;
        stoppedAtNanos = nowNanos;
    }

    /**
     * Updates the session lifecycle against the simulated clock and sound handle status.
     */
    public SessionState update(long nowNanos) {
        if (state == SessionState.TERMINATED) {
            return SessionState.TERMINATED;
        }

        // Check weak reference and underlying sound stopped state
        SoundSourceHandle handle = soundHandleRef.get();
        boolean handleExplicitlyStopped = (handle != null) && handle.isStopped();

        // Check heartbeat timeout (< 800ms heartbeat keeps session alive even if handle GC'd)
        boolean heartbeatTimedOut = (nowNanos - lastHeartbeatNanos) > HEARTBEAT_TIMEOUT_NANOS;

        if (handleExplicitlyStopped || heartbeatTimedOut) {
            if (state != SessionState.FADING_OUT) {
                stop(nowNanos);
            }
        }

        switch (state) {
            case STARTING -> {
                if ((nowNanos - createdNanos) >= FADE_IN_DURATION_NANOS) {
                    state = SessionState.STREAMING;
                }
            }
            case STREAMING -> {
                // Remains streaming while handle is alive and heartbeat continues
            }
            case FADING_OUT -> {
                if (stoppedAtNanos > 0 && (nowNanos - stoppedAtNanos) >= FADE_OUT_DURATION_NANOS) {
                    state = SessionState.TERMINATED;
                }
            }
            case TERMINATED -> {}
        }

        return state;
    }

    /**
     * Computes the subtle breathing scale oscillation (0.96x ~ 1.04x).
     */
    public float breathingScale(long nowNanos) {
        if (state == SessionState.TERMINATED) return 0.0f;
        double elapsedSeconds = Math.max(0.0, (nowNanos - createdNanos) / 1_000_000_000.0);
        double phase = 2.0 * Math.PI * BREATHING_FREQUENCY_HZ * elapsedSeconds;
        float amplitude = (BREATHING_MAX_SCALE - BREATHING_MIN_SCALE) * 0.5f; // 0.04f
        float mid = (BREATHING_MAX_SCALE + BREATHING_MIN_SCALE) * 0.5f;       // 1.00f
        return mid + (float) (amplitude * Math.sin(phase));
    }

    /**
     * Computes the current transparency alpha [0.0, 1.0].
     */
    public float alpha(long nowNanos) {
        return switch (state) {
            case STARTING -> {
                long elapsed = Math.max(0, nowNanos - createdNanos);
                yield Math.clamp((float) elapsed / FADE_IN_DURATION_NANOS, 0.0f, 1.0f);
            }
            case STREAMING -> 1.0f;
            case FADING_OUT -> {
                if (stoppedAtNanos <= 0) yield 0.0f;
                long fadingElapsed = Math.max(0, nowNanos - stoppedAtNanos);
                float remaining = 1.0f - ((float) fadingElapsed / FADE_OUT_DURATION_NANOS);
                yield Math.clamp(remaining, 0.0f, 1.0f);
            }
            case TERMINATED -> 0.0f;
        };
    }

    public boolean isActive() {
        return state == SessionState.STARTING || state == SessionState.STREAMING;
    }

    public boolean isFadingOut() {
        return state == SessionState.FADING_OUT;
    }

    public boolean isTerminated() {
        return state == SessionState.TERMINATED;
    }

    public SessionSnapshot snapshot(long nowNanos, float partialTicks) {
        update(nowNanos);
        return new SessionSnapshot(
                id,
                soundId,
                translationKey,
                state,
                currentPosition(partialTicks),
                breathingScale(nowNanos),
                alpha(nowNanos),
                state == SessionState.FADING_OUT || state == SessionState.TERMINATED,
                Math.max(0, nowNanos - createdNanos)
        );
    }

    /**
     * Checks if a sound identifier is a recognized streaming/looping sound.
     */
    public static boolean isStreamingCandidate(String soundId) {
        if (soundId == null || soundId.isEmpty()) return false;
        String id = soundId.toLowerCase(Locale.ROOT);
        return id.contains("minecart.inside")
                || id.contains("elytra.flying")
                || id.contains("beacon.ambient")
                || id.contains("conduit.ambient")
                || id.contains("ghast.warn")
                || id.contains("ghast.scream")
                || id.contains("weather.rain")
                || id.contains(".loop")
                || id.contains("portal.travel")
                || id.contains("ambient.underwater.loop");
    }
}
