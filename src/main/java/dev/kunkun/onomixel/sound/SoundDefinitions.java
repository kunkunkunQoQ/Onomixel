package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.subtitle.VisualType;
import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Public addon registration. Unknown events stay excluded until given a complete definition. */
public final class SoundDefinitions {
    private static final ConcurrentMap<String, SoundDefinition> ADDONS = new ConcurrentHashMap<>();
    private static final BoundedLookupCache<String, SoundDefinition> INFERRED =
            new BoundedLookupCache<>(256, 4096, 512);
    private static final ConcurrentMap<String, SoundDefinition.Source> BUILTIN_SOURCES = new ConcurrentHashMap<>();
    private static volatile Map<String, SoundDefinition> builtinCache = new ConcurrentHashMap<>(256);
    private static final BoundedLookupCache<String, Boolean> UNKNOWN_BUILTIN =
            new BoundedLookupCache<>(128, 1024, 128);

    private SoundDefinitions() {}

    public static void register(SoundDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        INFERRED.remove(definition.soundId());
        builtinCache.remove(definition.soundId());
        UNKNOWN_BUILTIN.remove(definition.soundId());
        SoundDefinition previous = ADDONS.putIfAbsent(definition.soundId(), definition);
        if (previous != null && !previous.equals(definition))
            throw new IllegalArgumentException("Duplicate sound definition: " + definition.soundId());
        VisualTypeRegistry.registerType(definition.visualType());
        VisualTypeRegistry.registerSound(definition.soundId(), definition.visualType());
    }

    public static void registerOrOverride(SoundDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        INFERRED.remove(definition.soundId());
        builtinCache.remove(definition.soundId());
        UNKNOWN_BUILTIN.remove(definition.soundId());
        ADDONS.put(definition.soundId(), definition);
        VisualTypeRegistry.registerType(definition.visualType());
        VisualTypeRegistry.registerSound(definition.soundId(), definition.visualType());
    }

    private static SoundDefinition registered(String soundId) {
        SoundDefinition pack = SoundCustomizationManager.legacyPackDefinition(soundId);
        return pack != null ? pack : ADDONS.get(soundId);
    }

    public static boolean contains(String soundId) {
        return soundId != null && (registered(soundId) != null
                || builtinCache.containsKey(soundId)
                || BuiltinSoundMappings.translationKey(soundId) != null
                || INFERRED.containsKey(soundId));
    }

    public static SoundSemantics semantics(String soundId) {
        if (soundId == null) return SoundSemantics.OTHER;
        SoundDefinition cached = registered(soundId);
        if (cached == null) cached = builtinCache.get(soundId);
        if (cached == null) cached = INFERRED.get(soundId);
        return cached == null ? SoundSemantics.classify(soundId) : cached.semantics();
    }

    public static SoundDefinition.Source source(String soundId) {
        if (soundId == null) return SoundDefinition.Source.DEFAULT;
        SoundDefinition definition = registered(soundId);
        if (definition == null) definition = builtinCache.get(soundId);
        if (definition == null) definition = INFERRED.get(soundId);
        if (definition != null) return definition.source();
        SoundDefinition.Source cachedSource = BUILTIN_SOURCES.get(soundId);
        if (cachedSource != null) return cachedSource;
        if (BuiltinSoundMappings.translationKey(soundId) == null) return SoundDefinition.Source.DEFAULT;
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

    /** Returns an inferred VisualType only if already cached (avoids re-entrant find calls). */
    public static VisualType inferredVisualType(String soundId) {
        SoundDefinition inferred = INFERRED.get(soundId);
        return inferred != null ? inferred.visualType() : null;
    }

    public static SoundDefinition find(String soundId) {
        if (soundId == null) return null;
        SoundDefinition definition = registered(soundId);
        if (definition != null) return definition;
        var cache = builtinCache;
        SoundDefinition cached = cache.get(soundId);
        if (cached != null) return cached;
        SoundDefinition inferred = INFERRED.get(soundId);
        if (inferred != null) return inferred;
        if (soundId.startsWith("minecraft:") && UNKNOWN_BUILTIN.get(soundId) != null) {
            return null;
        }
        String key = BuiltinSoundMappings.translationKey(soundId);
        if (key != null) {
            SoundDefinition def = new SoundDefinition(soundId, key, SoundSemantics.classify(soundId),
                    source(soundId), VisualTypeRegistry.resolve(soundId));
            SoundDefinition existing = cache.putIfAbsent(soundId, def);
            return existing != null ? existing : def;
        }
        if (soundId.startsWith("minecraft:")) {
            UNKNOWN_BUILTIN.put(soundId, Boolean.TRUE);
        }
        return null;
    }

    public static SoundDefinition findOrInfer(String soundId) {
        SoundDefinition def = find(soundId);
        if (def != null) return def;
        if (soundId == null || soundId.isBlank() || soundId.startsWith("minecraft:")) return null;
        SoundDefinition inferred = UniversalModdedInferenceEngine.inferDefinition(soundId);
        INFERRED.put(inferred.soundId(), inferred);
        VisualTypeRegistry.registerType(inferred.visualType());
        VisualTypeRegistry.invalidateSound(inferred.soundId());
        return inferred;
    }

    public static void clearInferred() {
        INFERRED.clear();
        VisualTypeRegistry.invalidateResolutionCaches();
    }

    public static void clearBuiltinCache() {
        builtinCache = new ConcurrentHashMap<>(256);
        UNKNOWN_BUILTIN.clear();
    }

    /** Resets dynamic addon registrations and caches for isolated unit testing. */
    public static void resetForTesting() {
        ADDONS.clear();
        INFERRED.clear();
        BUILTIN_SOURCES.clear();
        builtinCache = new ConcurrentHashMap<>(256);
        UNKNOWN_BUILTIN.clear();
    }
}
