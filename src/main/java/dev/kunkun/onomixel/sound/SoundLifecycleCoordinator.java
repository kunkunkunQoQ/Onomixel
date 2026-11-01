package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.BossChoreographyDirector;
import dev.kunkun.onomixel.subtitle.StreamingCaptionSession;
import dev.kunkun.onomixel.subtitle.SubtitleClock;
import dev.kunkun.onomixel.subtitle.SubtitlePool;

import java.util.Objects;

/**
 * Coordinates world lifecycle transitions, dimension warps, level unloads, and respawns
 * across all 8 audio/subtitle subsystems to guarantee zero ghost state, zero lingering
 * radar markers, and zero memory leaks.
 */
public final class SoundLifecycleCoordinator {
    private final SubtitlePool subtitles;
    private final SubtitleClock clock;
    private final PlaybackMailbox<?, ?> mailbox;
    private final BoundedDelayedQueue<?> delayedEmissions;
    private final SoundEmissionThrottle throttle;
    private final ProjectileOwnershipEvidence projectileEvidence;

    public SoundLifecycleCoordinator(
            SubtitlePool subtitles,
            SubtitleClock clock,
            PlaybackMailbox<?, ?> mailbox,
            BoundedDelayedQueue<?> delayedEmissions,
            SoundEmissionThrottle throttle,
            ProjectileOwnershipEvidence projectileEvidence
    ) {
        this.subtitles = Objects.requireNonNull(subtitles, "subtitles");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.mailbox = Objects.requireNonNull(mailbox, "mailbox");
        this.delayedEmissions = Objects.requireNonNull(delayedEmissions, "delayedEmissions");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
        this.projectileEvidence = Objects.requireNonNull(projectileEvidence, "projectileEvidence");
    }

    public SoundLifecycleCoordinator() {
        this(
                new SubtitlePool(),
                new SubtitleClock(),
                new PlaybackMailbox<Object, Object>(),
                new BoundedDelayedQueue<Object>(256),
                new SoundEmissionThrottle(),
                new ProjectileOwnershipEvidence()
        );
    }

    /**
     * Cascades reset across all 8 subsystems during cross-dimension warps, level unloads, and respawns.
     */
    public void resetAllWorldState() {
        subtitles.clear();
        delayedEmissions.clear();
        mailbox.clear();
        clock.reset();
        throttle.clear();
        projectileEvidence.clear();
        BossChoreographyDirector.getInstance().reset();
        StreamingCaptionSession.clearAllSessions();
    }

    /**
     * Freezes active subtitle clock advancement and purges transient audio confirmations on pause.
     */
    public void handlePauseState(boolean paused, long nowNanos) {
        clock.sample(nowNanos, paused);
        mailbox.clear();
    }

    public SubtitlePool subtitles() { return subtitles; }
    public SubtitleClock clock() { return clock; }
    @SuppressWarnings("unchecked")
    public <T, P> PlaybackMailbox<T, P> mailbox() { return (PlaybackMailbox<T, P>) mailbox; }
    @SuppressWarnings("unchecked")
    public <T> BoundedDelayedQueue<T> delayedEmissions() { return (BoundedDelayedQueue<T>) delayedEmissions; }
    public SoundEmissionThrottle throttle() { return throttle; }
    public ProjectileOwnershipEvidence projectileEvidence() { return projectileEvidence; }
}
