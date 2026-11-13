package dev.kunkun.onomixel.api;

import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.DisplayStrategy;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.sound.SoundDefinition;
import dev.kunkun.onomixel.sound.SoundDefinitions;
import dev.kunkun.onomixel.sound.SoundEventPolicy;
import dev.kunkun.onomixel.subtitle.CaptionSettings;
import dev.kunkun.onomixel.subtitle.ThreatRegistry;
import dev.kunkun.onomixel.subtitle.VisualType;
import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Public Java API for third-party modders and integration authors.
 * Provides clean methods to register custom sounds, visual animation envelopes,
 * style/onomatopoeia rules, radar threats, self-action suppressions, read-only queries,
 * and lifecycle/alert listeners without accessing internal implementation classes.
 */
public interface OnomixelAPI {

    /**
     * Listener invoked when an admitted external sound triggers an active radar threat.
     */
    @FunctionalInterface
    interface RadarAlertListener {
        void onRadarThreat(String soundId, ThreatRegistry.ThreatLevel level,
                           double x, double y, double z, double distanceToCamera);
    }

    /** Returns the global Onomixel API singleton. */
    static OnomixelAPI get() {
        return InstanceHolder.INSTANCE;
    }

    /** Registers or overrides a sound definition. */
    void registerSound(SoundDefinition definition);

    /** Registers a custom visual type. */
    void registerVisualType(VisualType visualType);

    /** Maps a sound ID to a visual type. */
    void mapSoundToVisualType(String soundId, VisualType visualType);

    /** Registers a pattern matcher mapping sound IDs to a visual type. */
    void registerVisualRule(Predicate<String> matcher, VisualType visualType);

    /** Assigns custom vibrant and pastel colors to a sound ID. */
    void setSoundColor(String soundId, int vibrantRgb, int pastelRgb);

    /** Registers or overrides a custom onomatopoeia literal text for a sound event. */
    void registerCustomWord(String soundId, String customText);

    /** Registers or overrides a translatable onomatopoeia localization key for a sound event. */
    void registerTranslatableWord(String soundId, String translationKey);

    /** Registers or overrides a weighted random pool of onomatopoeia words for a sound event. */
    void registerWeightedRandomWords(String soundId, List<DisplayStrategy.Variant> variants);

    /** Registers a full custom onomatopoeia rule (patterns, kinetics, scaling, radar &amp; clustering). */
    void registerCustomRule(CustomWordRule rule);

    /** Registers a batch of full custom rules cleanly under Tier 1 API storage. */
    void registerCustomRules(List<CustomWordRule> rules);

    /**
     * Registers or overrides a sound event on the off-screen Threat Edge Radar.
     *
     * @param soundId exact sound event ID
     * @param level severity tier ({@code CRITICAL}=24m, {@code ELEVATED}=16m, {@code TACTICAL}=32m, {@code NONE}=suppress)
     * @param durationMillis display window in milliseconds (&lt;= 0 selects tier default)
     * @param threatNameOrKey localized display key or literal threat label
     */
    void registerRadarThreat(String soundId, ThreatRegistry.ThreatLevel level,
                             long durationMillis, String threatNameOrKey);

    /**
     * Registers a predicate-based off-screen radar threat rule (evaluated after custom JSON overrides).
     */
    void registerRadarThreatRule(Predicate<String> matcher, ThreatRegistry.ThreatLevel level,
                                 long durationMillis, String threatNameOrKey);

    /**
     * Registers a player-initiated action sound that should not trigger false radar alarms on the local player.
     *
     * @param soundId exact sound event ID
     * @param requiredSourceCategory optional SoundSource name (e.g. {@code "PLAYERS"} or {@code "NEUTRAL"}), or null for any
     * @param maxRadiusBlocks feet radius around the local player within which the sound counts as self-triggered (0, 16]
     */
    void registerLocalPlayerActionSound(String soundId, String requiredSourceCategory, double maxRadiusBlocks);

    // ---- Read-only state queries ----

    /** Returns true if a sound ID is registered or admitted in the built-in/addon catalog. */
    boolean isSoundRegistered(String soundId);

    /** Returns true if the given sound ID currently qualifies for the edge radar. */
    boolean isRadarThreat(String soundId);

    /** Evaluates the effective radar threat severity for a sound ID (including API &amp; JSON overrides). */
    ThreatRegistry.ThreatLevel evaluateRadarThreat(String soundId);

    /** Resolves the effective visual type for a sound ID (respecting rules, overrides &amp; inference). */
    VisualType resolveVisualType(String soundId);

    /** Resolves the highest-priority active customization rule matching a sound ID, or null. */
    CustomWordRule resolveCustomRule(String soundId);

    /** Returns whether 3D spatial onomatopoeia rendering is currently enabled in client config. */
    boolean isSpatialWordsEnabled();

    /** Returns whether the off-screen edge threat radar is currently enabled in client config. */
    boolean isThreatRadarEnabled();

    // ---- Notification listeners ----

    /** Registers a callback invoked after custom rules finish recompiling (e.g. startup or F3+T reload). */
    void addRuleReloadListener(Runnable listener);

    /** Removes a previously registered rule reload listener. */
    void removeRuleReloadListener(Runnable listener);

    /** Registers a callback invoked when an external admitted sound triggers a radar threat. */
    void addRadarAlertListener(RadarAlertListener listener);

    /** Removes a previously registered radar alert listener. */
    void removeRadarAlertListener(RadarAlertListener listener);

    /** Internal client config synchronization hook (called from client capture/config). */
    static void syncLiveSettings(CaptionSettings settings) {
        if (settings != null) {
            InstanceHolder.liveSettings = settings;
        }
    }

    /** Returns true when at least one radar alert listener is registered. */
    static boolean hasRadarListeners() {
        return !InstanceHolder.RADAR_LISTENERS.isEmpty();
    }

    /** Dispatches an admitted radar threat notification safely to registered listeners. */
    static void notifyRadarThreat(String soundId, ThreatRegistry.ThreatLevel level,
                                  double x, double y, double z, double distanceToCamera) {
        if (InstanceHolder.RADAR_LISTENERS.isEmpty() || level == null || level == ThreatRegistry.ThreatLevel.NONE) {
            return;
        }
        for (RadarAlertListener listener : InstanceHolder.RADAR_LISTENERS) {
            try {
                listener.onRadarThreat(soundId, level, x, y, z, distanceToCamera);
            } catch (Throwable ignored) {
                // Safeguard sound capture thread/tick from buggy third-party listeners.
            }
        }
    }

    class InstanceHolder {
        private static volatile CaptionSettings liveSettings = CaptionSettings.defaults();
        private static final List<RadarAlertListener> RADAR_LISTENERS = new CopyOnWriteArrayList<>();

        private static final OnomixelAPI INSTANCE = new OnomixelAPI() {
            @Override
            public void registerSound(SoundDefinition definition) {
                SoundDefinitions.registerOrOverride(definition);
            }

            @Override
            public void registerVisualType(VisualType visualType) {
                VisualTypeRegistry.registerType(visualType);
            }

            @Override
            public void mapSoundToVisualType(String soundId, VisualType visualType) {
                VisualTypeRegistry.registerSound(soundId, visualType);
            }

            @Override
            public void registerVisualRule(Predicate<String> matcher, VisualType visualType) {
                VisualTypeRegistry.registerRule(matcher, visualType);
            }

            @Override
            public void setSoundColor(String soundId, int vibrantRgb, int pastelRgb) {
                VisualTypeRegistry.registerSoundColor(soundId, vibrantRgb, pastelRgb);
            }

            @Override
            public void registerCustomWord(String soundId, String customText) {
                SoundCustomizationManager.registerApiRule(new CustomWordRule(
                        soundId, null, null, new DisplayStrategy.SingleText(customText),
                        null, null, null, null, null, null, null, null, null, 150
                ));
            }

            @Override
            public void registerTranslatableWord(String soundId, String translationKey) {
                SoundCustomizationManager.registerApiRule(new CustomWordRule(
                        soundId, null, null, new DisplayStrategy.Translatable(translationKey),
                        null, null, null, null, null, null, null, null, null, 150
                ));
            }

            @Override
            public void registerWeightedRandomWords(String soundId, List<DisplayStrategy.Variant> variants) {
                SoundCustomizationManager.registerApiRule(new CustomWordRule(
                        soundId, null, null, new DisplayStrategy.WeightedRandom(variants),
                        null, null, null, null, null, null, null, null, null, 150
                ));
            }

            @Override
            public void registerCustomRule(CustomWordRule rule) {
                SoundCustomizationManager.registerApiRule(rule);
            }

            @Override
            public void registerCustomRules(List<CustomWordRule> rules) {
                SoundCustomizationManager.registerApiRules(rules);
            }

            @Override
            public void registerRadarThreat(String soundId, ThreatRegistry.ThreatLevel level,
                                            long durationMillis, String threatNameOrKey) {
                long nanos = durationMillis > 0L ? Math.multiplyExact(Math.min(durationMillis, 60_000L), 1_000_000L) : 0L;
                ThreatRegistry.registerThreat(soundId, level, nanos, threatNameOrKey);
            }

            @Override
            public void registerRadarThreatRule(Predicate<String> matcher, ThreatRegistry.ThreatLevel level,
                                                long durationMillis, String threatNameOrKey) {
                long nanos = durationMillis > 0L ? Math.multiplyExact(Math.min(durationMillis, 60_000L), 1_000_000L) : 0L;
                ThreatRegistry.registerThreatRule(matcher, level, nanos, threatNameOrKey);
            }

            @Override
            public void registerLocalPlayerActionSound(String soundId, String requiredSourceCategory, double maxRadiusBlocks) {
                SoundEventPolicy.registerLocalPlayerAction(soundId, requiredSourceCategory, maxRadiusBlocks);
            }

            @Override
            public boolean isSoundRegistered(String soundId) {
                return SoundDefinitions.contains(soundId);
            }

            @Override
            public boolean isRadarThreat(String soundId) {
                return ThreatRegistry.evaluate(soundId) != ThreatRegistry.ThreatLevel.NONE;
            }

            @Override
            public ThreatRegistry.ThreatLevel evaluateRadarThreat(String soundId) {
                return ThreatRegistry.evaluate(soundId);
            }

            @Override
            public VisualType resolveVisualType(String soundId) {
                return VisualTypeRegistry.resolve(soundId, SoundCustomizationManager.resolve(soundId));
            }

            @Override
            public CustomWordRule resolveCustomRule(String soundId) {
                return SoundCustomizationManager.resolve(soundId);
            }

            @Override
            public boolean isSpatialWordsEnabled() {
                CaptionSettings snapshot = liveSettings;
                return snapshot.enabled() && snapshot.spatialWords();
            }

            @Override
            public boolean isThreatRadarEnabled() {
                CaptionSettings snapshot = liveSettings;
                return snapshot.enabled() && snapshot.threatRadar();
            }

            @Override
            public void addRuleReloadListener(Runnable listener) {
                SoundCustomizationManager.addReloadListener(listener);
            }

            @Override
            public void removeRuleReloadListener(Runnable listener) {
                SoundCustomizationManager.removeReloadListener(listener);
            }

            @Override
            public void addRadarAlertListener(RadarAlertListener listener) {
                if (listener != null) {
                    RADAR_LISTENERS.add(listener);
                }
            }

            @Override
            public void removeRadarAlertListener(RadarAlertListener listener) {
                if (listener != null) {
                    RADAR_LISTENERS.remove(listener);
                }
            }
        };
    }
}
