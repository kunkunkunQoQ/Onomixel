package dev.kunkun.onomixel.api;

import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.DisplayStrategy;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.sound.SoundDefinition;
import dev.kunkun.onomixel.sound.SoundDefinitions;
import dev.kunkun.onomixel.subtitle.VisualColor;
import dev.kunkun.onomixel.subtitle.VisualType;
import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;

import java.util.List;
import java.util.function.Predicate;

/**
 * Public Java API for third-party modders and integration authors.
 * Provides clean methods to register custom sounds, visual animation envelopes,
 * and style rules without accessing internal implementation classes.
 */
public interface OnomixelAPI {

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

    /** Registers or overrides a weighted random pool of onomatopoeia words for a sound event. */
    void registerWeightedRandomWords(String soundId, List<DisplayStrategy.Variant> variants);

    class InstanceHolder {
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
            public void registerWeightedRandomWords(String soundId, List<DisplayStrategy.Variant> variants) {
                SoundCustomizationManager.registerApiRule(new CustomWordRule(
                        soundId, null, null, new DisplayStrategy.WeightedRandom(variants),
                        null, null, null, null, null, null, null, null, null, 150
                ));
            }
        };
    }
}
