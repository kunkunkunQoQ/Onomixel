package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Public addon registration. Unknown events stay excluded until given a complete definition. */
public final class SoundDefinitions {
    private static final ConcurrentMap<String, SoundDefinition> ADDONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, SoundDefinition> INFERRED = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, SoundDefinition.Source> BUILTIN_SOURCES = new ConcurrentHashMap<>();

    private SoundDefinitions() {}

    public static void register(SoundDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        INFERRED.remove(definition.soundId());
        SoundDefinition previous = ADDONS.putIfAbsent(definition.soundId(), definition);
        if (previous != null && !previous.equals(definition))
            throw new IllegalArgumentException("Duplicate sound definition: " + definition.soundId());
        VisualTypeRegistry.registerType(definition.visualType());
        VisualTypeRegistry.registerSound(definition.soundId(), definition.visualType());
    }

    public static void registerOrOverride(SoundDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        INFERRED.remove(definition.soundId());
        ADDONS.put(definition.soundId(), definition);
        VisualTypeRegistry.registerType(definition.visualType());
        VisualTypeRegistry.registerSound(definition.soundId(), definition.visualType());
    }

    public static boolean contains(String soundId) {
        return soundId != null && (ADDONS.containsKey(soundId)
                || BuiltinSoundMappings.translationKey(soundId) != null
                || INFERRED.containsKey(soundId));
    }

    public static SoundSemantics semantics(String soundId) {
        SoundDefinition addon = soundId == null ? null : ADDONS.get(soundId);
        if (addon == null && soundId != null) addon = INFERRED.get(soundId);
        return addon == null ? SoundSemantics.classify(soundId) : addon.semantics();
    }

    public static SoundDefinition.Source source(String soundId) {
        SoundDefinition definition = soundId == null ? null : ADDONS.get(soundId);
        if (definition == null && soundId != null) definition = INFERRED.get(soundId);
        if (definition != null) return definition.source();
        if (soundId == null || BuiltinSoundMappings.translationKey(soundId) == null) return SoundDefinition.Source.DEFAULT;
        return BUILTIN_SOURCES.computeIfAbsent(soundId, SoundDefinitions::builtinSource);
    }

    private static SoundDefinition.Source builtinSource(String soundId) {
        var semantics = SoundSemantics.classify(soundId);
        if (semantics == SoundSemantics.STEP || semantics == SoundSemantics.GALLOP
                || semantics == SoundSemantics.HEAVY_STEP || semantics == SoundSemantics.LANDING) return SoundDefinition.Source.GROUND;
        if (SoundEventPolicy.isRain(soundId)) return SoundDefinition.Source.RAIN;
        if (soundId.startsWith("minecraft:block.")) return SoundDefinition.Source.BLOCK;
        double height = SoundPlacement.mobHeight(soundId);
        return height > 0 ? SoundDefinition.Source.entity(height, Math.clamp(height * .42, .35, 1.8))
                : SoundDefinition.Source.DEFAULT;
    }

    public static SoundDefinition find(String soundId) {
        if (soundId == null) return null;
        SoundDefinition addon = ADDONS.get(soundId);
        if (addon != null) return addon;
        String key = BuiltinSoundMappings.translationKey(soundId);
        if (key != null) {
            return new SoundDefinition(soundId, key, SoundSemantics.classify(soundId),
                    source(soundId), VisualTypeRegistry.resolve(soundId));
        }
        return INFERRED.get(soundId);
    }

    public static SoundDefinition findOrInfer(String soundId) {
        SoundDefinition def = find(soundId);
        if (def != null) return def;
        if (soundId == null || soundId.isBlank() || soundId.startsWith("minecraft:")) return null;
        SoundDefinition inferred = UniversalModdedInferenceEngine.inferDefinition(soundId);
        INFERRED.put(inferred.soundId(), inferred);
        VisualTypeRegistry.registerType(inferred.visualType());
        VisualTypeRegistry.registerSound(inferred.soundId(), inferred.visualType());
        return inferred;
    }

    public static void clearInferred() {
        INFERRED.clear();
    }
}
