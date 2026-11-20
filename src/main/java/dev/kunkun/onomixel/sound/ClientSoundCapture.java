package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.OnomixelConfig;
import dev.kunkun.onomixel.api.OnomixelAPI;
import dev.kunkun.onomixel.subtitle.BossChoreographyDirector;
import dev.kunkun.onomixel.subtitle.CaptionSettings;
import dev.kunkun.onomixel.subtitle.CaptionPlacement.Point;
import dev.kunkun.onomixel.subtitle.CaptionVisuals;
import dev.kunkun.onomixel.subtitle.StreamingCaptionSession;
import dev.kunkun.onomixel.subtitle.SubtitleClock;
import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.subtitle.SubtitlePool;
import dev.kunkun.onomixel.subtitle.ThreatRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.ThrownTrident;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientPauseChangeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.client.event.sound.PlayStreamingSourceEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.event.sound.SoundEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Observes requests and confirmed source starts without replacing sounds or vanilla listeners. */
public final class ClientSoundCapture {
    private final SubtitlePool subtitles = new SubtitlePool();
    private final SubtitleClock clock = new SubtitleClock();
    private final PlaybackMailbox<SoundInstance, Request> mailbox = new PlaybackMailbox<>();
    private final BoundedDelayedQueue<PendingDelayedEmission> delayedEmissions = new BoundedDelayedQueue<>(256);
    private final AtomicBoolean engineReloaded = new AtomicBoolean();
    private final SoundEmissionThrottle throttle = new SoundEmissionThrottle();
    private final ProjectileOwnershipEvidence projectileEvidence = new ProjectileOwnershipEvidence();
    private final SoundLifecycleCoordinator lifecycle = new SoundLifecycleCoordinator(
            subtitles, clock, mailbox, delayedEmissions, throttle, projectileEvidence
    );
    private ClientLevel currentLevel;
    private volatile CaptionSettings settings = CaptionSettings.defaults();
    private volatile long lastActiveNanos;
    private boolean worldStateCleared = true;

    /**
     * Evaluates whether a sound event needs capturing under the current toggle matrix.
     * In radar-only mode ({@code !spatialWords && threatRadar}), non-threat sounds are rejected
     * before definition inference, stack walking, or mailbox queueing.
     */
    public static boolean shouldCaptureSound(CaptionSettings activeSettings, String soundId) {
        return SoundEventPolicy.shouldCaptureSound(activeSettings, soundId);
    }

    record Request(SoundEngine engine, SoundDefinition definition, SoundSource category,
                           double x, double y, double z, boolean localPlayerShot) {}

    record PendingDelayedEmission(long triggerAtNanos, SoundDefinition definition, String category,
                                          double sourceX, double sourceY, double sourceZ,
                                          double anchorX, double anchorY, double anchorZ,
                                          long duration, int importance, String world, boolean localPlayerShot) {}

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onSoundRequested(PlaySoundEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        // Off-thread requests have no trustworthy world token; conservatively skip them in P1.
        if (!minecraft.isSameThread() || minecraft.level == null) return;
        synchronizeWorld(minecraft);
        if (minecraft.isPaused()) return;
        CaptionSettings activeSettings = OnomixelConfig.snapshot();
        if (!activeSettings.enabled() || (!activeSettings.spatialWords() && !activeSettings.threatRadar())) return;
        SoundInstance sound = event.getSound();
        if (sound == null || sound.isRelative() || sound.getAttenuation() != SoundInstance.Attenuation.LINEAR
                || sound.canStartSilent() || sound.getSource() == SoundSource.MUSIC) return;
        String soundId = sound.getIdentifier().toString();
        if (!shouldCaptureSound(activeSettings, soundId)) return;
        SoundDefinition definition = SoundDefinitions.findOrInfer(soundId);
        if (definition == null || !Double.isFinite(sound.getX()) || !Double.isFinite(sound.getY()) || !Double.isFinite(sound.getZ())) return;
        if (definition.semantics() == SoundSemantics.STEP && minecraft.player != null) {
            double dx = sound.getX() - minecraft.player.getX();
            double dy = sound.getY() - minecraft.player.getY();
            double dz = sound.getZ() - minecraft.player.getZ();
            if (dx * dx + dy * dy + dz * dz <= 1.0 && SoundEventPolicy.hasPlaybackCaller(SoundEventPolicy::isLocalPlayerCall)) return;
        }
        long requestedAt = System.nanoTime();
        var explicitOwner = projectileEvidence.resolveEntitySound(soundId, sound.getX(), sound.getY(), sound.getZ(), requestedAt);
        var impactOwner = explicitOwner.isPresent() ? java.util.Optional.<Boolean>empty()
                : projectileEvidence.resolveImpact(soundId, sound.getX(), sound.getY(), sound.getZ(), requestedAt);
        boolean localPlayerShot = explicitOwner.or(() -> impactOwner).orElseGet(() -> minecraft.player != null
                && SoundEventPolicy.isLocalPlayerShot(soundId, sound.getSource().name(), sound.getX(), sound.getY(), sound.getZ(),
                minecraft.player.getX(), minecraft.player.getY(), minecraft.player.getZ()));
        // getVolume/getPitch are random samplers and may require unresolved data. Never call them here.
        worldStateCleared = false;
        mailbox.request(sound, currentLevel, new Request(event.getEngine(), definition,
                sound.getSource(), sound.getX(), sound.getY(), sound.getZ(), localPlayerShot), requestedAt);
        SoundSemantics sem = definition.semantics();
        if (sem == SoundSemantics.BREAK || sem == SoundSemantics.INTERACTION
                || sem == SoundSemantics.ATTACK || sem == SoundSemantics.DAMAGE
                || sem == SoundSemantics.PICKUP || sem == SoundSemantics.LANDING) {
            mailbox.confirmRequested(sound, requestedAt);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onProjectileImpact(ProjectileImpactEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.level == null || !OnomixelConfig.ENABLED.get()) return;
        if (!OnomixelConfig.spatialWords() && !OnomixelConfig.THREAT_RADAR.get()) return;
        synchronizeWorld(minecraft);
        if (event.getProjectile().level() != minecraft.level) return;
        ProjectileOwnershipEvidence.Family family;
        if (event.getProjectile() instanceof ThrownTrident) {
            family = ProjectileOwnershipEvidence.Family.TRIDENT;
        } else if (event.getProjectile() instanceof AbstractArrow) {
            family = ProjectileOwnershipEvidence.Family.ARROW;
        } else {
            return;
        }
        Entity owner = event.getProjectile().getOwner();
        ProjectileOwnershipEvidence.Owner ownership = owner == null
                ? ProjectileOwnershipEvidence.Owner.UNKNOWN
                : owner == minecraft.player ? ProjectileOwnershipEvidence.Owner.LOCAL
                : ProjectileOwnershipEvidence.Owner.EXTERNAL;
        Vec3 location = event.getRayTraceResult().getLocation();
        projectileEvidence.recordImpact(family, ownership, location.x, location.y, location.z, System.nanoTime());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onEntitySound(PlayLevelSoundEvent.AtEntity event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread() || minecraft.level == null || event.getLevel() != minecraft.level
                || !OnomixelConfig.ENABLED.get() || !(event.getEntity() instanceof ThrownTrident trident)) return;
        if (!OnomixelConfig.spatialWords() && !OnomixelConfig.THREAT_RADAR.get()) return;
        synchronizeWorld(minecraft);
        String soundId = event.getSound() == null ? null
                : event.getSound().unwrapKey().map(key -> key.identifier().toString()).orElse(null);
        if (!"minecraft:item.trident.throw".equals(soundId)) return;
        Entity owner = trident.getOwner();
        ProjectileOwnershipEvidence.Owner ownership = owner == null
                ? ProjectileOwnershipEvidence.Owner.UNKNOWN
                : owner == minecraft.player ? ProjectileOwnershipEvidence.Owner.LOCAL
                : ProjectileOwnershipEvidence.Owner.EXTERNAL;
        projectileEvidence.recordEntitySound(soundId, ownership, trident.getX(), trident.getY(), trident.getZ(), System.nanoTime());
    }

    @SubscribeEvent
    public void onStaticSource(PlaySoundSourceEvent event) { onSourceStarted(event); }

    @SubscribeEvent
    public void onStreamingSource(PlayStreamingSourceEvent event) { onSourceStarted(event); }

    private void onSourceStarted(SoundEvent.SoundSourceEvent event) {
        if (event.getChannel().playing()) {
            mailbox.confirmRequested(event.getSound(), System.nanoTime());
        }
    }

    @SubscribeEvent
    public void onTick(ClientTickEvent.Post event) { pump(Minecraft.getInstance()); }

    public long pump(Minecraft minecraft) {
        CaptionSettings next = OnomixelConfig.snapshot();
        if (next != settings) {
            OnomixelAPI.syncLiveSettings(next);
            if (next.mode() != settings.mode()) {
                subtitles.filterAllowed(next.mode());
                delayedEmissions.removeIf(pending -> !pending.definition().semantics().allowed(next.mode()));
                throttle.clear();
            }
            if (!next.spatialWords() && settings.spatialWords()) {
                subtitles.retainRadarThreatsOnly();
                delayedEmissions.removeIf(pending -> ThreatRegistry.evaluate(pending.definition().soundId())
                        == ThreatRegistry.ThreatLevel.NONE);
            }
            settings = next;
            // Legacy extension getters stay current; the runtime path uses the immutable snapshot directly.
            CaptionVisuals.setActiveStyle(settings.style());
            CaptionVisuals.setDisplayMode(settings.mode());
            CaptionVisuals.setActiveAestheticStyle(settings.aestheticStyle());
            CaptionVisuals.setActiveColorblindMode(settings.colorblindMode());
            var durations = settings.durations();
            CaptionVisuals.setDurationScales(durations.step(), durations.pickup(), durations.interaction(),
                    durations.breaking(), durations.impact(), durations.voice(), durations.ambient());
        }
        synchronizeWorld(minecraft);
        long wallNow = System.nanoTime();
        long now = clock.sample(wallNow, minecraft.isPaused());
        lastActiveNanos = now;
        boolean active = settings.enabled() && (settings.spatialWords() || settings.threatRadar());
        if (currentLevel == null || !active) {
            if (!worldStateCleared) {
                lifecycle.resetAllWorldState();
                worldStateCleared = true;
            }
            return now;
        }
        if (minecraft.isPaused()) {
            mailbox.clear();
            return now;
        }
        mailbox.expire(wallNow);
        renewStreamingCaptions(now);
        subtitles.expire(now);

        // Acoustic Speed-of-Sound: deliver pending physically delayed atmospheric events
        int newPlacements = delayedEmissions.deliver(now, 12, !settings.speedOfSound(),
                pending -> pending.definition().semantics().allowed(settings.mode())
                        && shouldCaptureSound(settings, pending.definition().soundId()),
                pending -> {
                    String customDeliverText = SoundCustomizationManager.resolveDisplayText(pending.definition().soundId(), now);
                    String deliverKey = customDeliverText != null ? customDeliverText : pending.definition().translationKey();
                    subtitles.addAt(pending.definition().soundId(), deliverKey,
                            pending.world(), pending.category(), pending.sourceX(), pending.sourceY(), pending.sourceZ(),
                            pending.anchorX(), pending.anchorY(), pending.anchorZ(), now, pending.duration(),
                            pending.importance(), pending.definition().semantics(), pending.definition().mergeGroup(),
                            pending.localPlayerShot());
                    if (!pending.localPlayerShot() && settings.threatRadar() && OnomixelAPI.hasRadarListeners()) {
                        String pId = pending.definition().soundId();
                        ThreatRegistry.ThreatLevel tLevel = ThreatRegistry.evaluate(pId);
                        if (tLevel != ThreatRegistry.ThreatLevel.NONE) {
                            var cPos = minecraft.gameRenderer.mainCamera().position();
                            double dx = pending.sourceX() - cPos.x;
                            double dy = pending.sourceY() - cPos.y;
                            double dz = pending.sourceZ() - cPos.z;
                            OnomixelAPI.notifyRadarThreat(pId, tLevel, pending.sourceX(), pending.sourceY(),
                                    pending.sourceZ(), Math.sqrt(dx * dx + dy * dy + dz * dz));
                        }
                    }
                });
        for (int i = 0; i < PlaybackMailbox.CONFIRMATION_LIMIT; i++) {
            // Bound new event creation; the renderer separately budgets dynamic world-ray searches.
            if (newPlacements == 12) break;
            var confirmation = mailbox.poll();
            if (confirmation == null) break;
            Request request = mailbox.consume(confirmation, currentLevel, wallNow);
            if (request == null || !request.engine.isActive(confirmation.identity())
                    || minecraft.options.getFinalSoundSourceVolume(request.category) <= 0) continue;
            SoundDefinition definition = request.definition;
            String soundId = definition.soundId();
            if (!shouldCaptureSound(settings, soundId)) continue;
            worldStateCleared = false;
            if (SoundEventPolicy.isRain(soundId)
                    && !currentLevel.isRainingAt(BlockPos.containing(minecraft.gameRenderer.mainCamera().position()))) continue;
            if (!throttle.admit(soundId, definition.semantics(), request.x, request.y, request.z, now, settings.mode())) continue;
            long duration = settings.duration(definition.semantics());
            if (definition.semantics() == SoundSemantics.IDLE_VOICE
                    && (soundId.contains("zombie") || soundId.contains("husk") || soundId.contains("drowned")
                        || soundId.contains("zombified") || soundId.contains("spider") || soundId.contains("silverfish"))) {
                duration = Math.max(duration, 1_650_000_000L);
            }
            String world = currentLevel.dimension().identifier().toString();
            boolean streaming = StreamingCaptionSession.isStreamingCandidate(soundId);
            var existing = streaming ? null : subtitles.existing(world, request.category.name(), request.x, request.y, request.z,
                    definition.semantics(), definition.mergeGroup());
            if (existing != null && now >= existing.hardExpiresNanos()) existing = null;
            var anchor = existing == null
                    ? SpatialAnchorResolver.initialAnchor(minecraft, definition, request.x, request.y, request.z)
                    : new dev.kunkun.onomixel.subtitle.CaptionPlacement.Point(existing.x(), existing.y(), existing.z());

            CustomWordRule customRule = SoundCustomizationManager.resolve(soundId);
            if (customRule != null) {
                if (customRule.durationMultiplier() != null && customRule.durationMultiplier() > 0) {
                    duration = Math.round(duration * customRule.durationMultiplier());
                }
                if (customRule.heightOffset() != null && existing == null) {
                    anchor = new dev.kunkun.onomixel.subtitle.CaptionPlacement.Point(
                            anchor.x(), anchor.y() + customRule.heightOffset(), anchor.z());
                }
            }

            // Speed-of-Sound Delay Buffer
            var camPos = minecraft.gameRenderer.mainCamera().position();
            double dx = request.x - camPos.x;
            double dy = request.y - camPos.y;
            double dz = request.z - camPos.z;
            double camDist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            boolean camSubmerged = currentLevel != null && !currentLevel.getFluidState(BlockPos.containing(camPos)).isEmpty();
            boolean srcSubmerged = currentLevel != null && !currentLevel.getFluidState(BlockPos.containing(request.x, request.y, request.z)).isEmpty();
            boolean bothSubmerged = camSubmerged && srcSubmerged;
            if (bothSubmerged) {
                duration = Math.round(AcousticEnvironmentReactor.calculateUnderwaterDuration(duration / 1_000_000_000.0) * 1_000_000_000L);
            }
            if (settings.speedOfSound() && SpeedOfSoundDelayBuffer.shouldDelay(soundId, camDist)) {
                long delayNanos = SpeedOfSoundDelayBuffer.calculateDelayNanos(camDist, bothSubmerged);
                if (delayNanos > 0) {
                    delayedEmissions.offer(now + delayNanos, new PendingDelayedEmission(now + delayNanos, definition,
                            request.category.name(), request.x, request.y, request.z,
                            anchor.x(), anchor.y(), anchor.z(), duration, definition.importance(), world,
                            request.localPlayerShot()));
                    continue;
                }
            }

            String customText = SoundCustomizationManager.resolveDisplayText(customRule, soundId, now);
            String effectiveKey = customText != null ? customText : definition.translationKey();

            if (existing == null) newPlacements++;
            if (streaming) {
                var subtitle = subtitles.addStreamingAt(soundId, effectiveKey, world, request.category.name(),
                        request.x, request.y, request.z, anchor.x(), anchor.y(), anchor.z(), now, duration,
                        definition.importance(), definition.semantics(), definition.mergeGroup(), request.localPlayerShot());
                if (subtitle != null) {
                    trackPlayback(subtitle, request.engine(), confirmation.identity(), now);
                }
            } else {
                subtitles.addAt(soundId, effectiveKey, world, request.category.name(),
                        request.x, request.y, request.z, anchor.x(), anchor.y(), anchor.z(), now, duration,
                        definition.importance(), definition.semantics(), definition.mergeGroup(), request.localPlayerShot());
            }

            if (!request.localPlayerShot() && settings.threatRadar() && OnomixelAPI.hasRadarListeners()) {
                ThreatRegistry.ThreatLevel tLevel = ThreatRegistry.evaluate(soundId);
                if (tLevel != ThreatRegistry.ThreatLevel.NONE) {
                    OnomixelAPI.notifyRadarThreat(soundId, tLevel, request.x, request.y, request.z, camDist);
                }
            }

            if (BossChoreographyDirector.isBossSound(soundId)) {
                BossChoreographyDirector.getInstance().onSoundEvent(soundId, request.x, request.y, request.z, now);
            }
        }

        return now;
    }

    private void renewStreamingCaptions(long now) {
        for (var session : StreamingCaptionSession.activeSessions()) {
            session.pollLiveHeartbeat(now);
            session.update(now);
            if (session.isActive() || session.isFadingOut()) {
                var pos = session.currentPosition(0.0f);
                long targetExpiry = session.isFadingOut()
                        ? session.stoppedAtNanos() + StreamingCaptionSession.FADE_OUT_DURATION_NANOS
                        : now + 1_000_000_000L;
                if (!subtitles.renewStreaming(session.id(), pos.x(), pos.y(), pos.z(), targetExpiry, session.isFadingOut())) {
                    StreamingCaptionSession.removeSession(session.id());
                }
            } else if (session.isTerminated()) {
                StreamingCaptionSession.removeSession(session.id());
            }
        }
    }

    private static void trackPlayback(SubtitlePool.Subtitle subtitle, SoundEngine engine, SoundInstance sound, long now) {
        var engineRef = new WeakReference<>(engine);
        var soundRef = new WeakReference<>(sound);
        var session = new StreamingCaptionSession(subtitle.id(), subtitle.soundId(), subtitle.translationKey(),
                StreamingCaptionSession.DEFAULT_HANDLE,
                new Point(subtitle.sourceX(), subtitle.sourceY(), subtitle.sourceZ()), now);
        session.setLivePlayingCheck(() -> {
            var liveEngine = engineRef.get();
            var liveSound = soundRef.get();
            return liveEngine != null && liveSound != null && liveEngine.isActive(liveSound);
        });
        session.setPositionSupplier(partialTick -> {
            var liveSound = soundRef.get();
            return liveSound == null ? null : new Point(liveSound.getX(), liveSound.getY(), liveSound.getZ());
        });
        StreamingCaptionSession.registerSession(session);
    }

    private void synchronizeWorld(Minecraft minecraft) {
        if (engineReloaded.getAndSet(false) || currentLevel != minecraft.level) {
            resetAllWorldState();
            currentLevel = minecraft.level;
        }
    }

    @SubscribeEvent
    public void onUnload(LevelEvent.Unload event) {
        if (event.getLevel() == currentLevel && Minecraft.getInstance().isSameThread()) {
            resetAllWorldState();
            currentLevel = null;
        }
    }

    /**
     * Resets all cascade state machines and event queues across dimensions, world unloads, or death respawns.
     */
    void resetAllWorldState() {
        lifecycle.resetAllWorldState();
    }

    PlaybackMailbox<SoundInstance, Request> mailbox() { return mailbox; }
    BoundedDelayedQueue<PendingDelayedEmission> delayedEmissions() { return delayedEmissions; }
    SoundEmissionThrottle throttle() { return throttle; }
    ProjectileOwnershipEvidence projectileEvidence() { return projectileEvidence; }
    SubtitleClock clock() { return clock; }
    SoundLifecycleCoordinator lifecycle() { return lifecycle; }

    public void handlePauseState(boolean paused, long nowNanos) {
        lifecycle.handlePauseState(paused, nowNanos);
    }

    @SubscribeEvent
    public void onPauseChanged(ClientPauseChangeEvent.Post event) {
        handlePauseState(event.isPaused(), System.nanoTime());
    }

    /** Mod-bus callback may run during a resource reload; state is reset later on the client thread. */
    public void onSoundEngineLoaded(SoundEngineLoadEvent event) {
        engineReloaded.set(true);
        UniversalModdedInferenceEngine.clearCache();
    }

    public SubtitlePool subtitles() { return subtitles; }
    public CaptionSettings settings() { return settings; }
    public long currentActiveNanos() { return lastActiveNanos; }
    public long worldGeneration() { return lifecycle.worldGeneration(); }
}
