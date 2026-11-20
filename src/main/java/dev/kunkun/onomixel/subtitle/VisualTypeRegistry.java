package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.sound.BoundedLookupCache;
import dev.kunkun.onomixel.sound.SoundDefinitions;
import dev.kunkun.onomixel.sound.SoundSemantics;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Registry for onomatopoeia visual types.
 * <p>
 * Third-party mods can call {@link #registerType(VisualType)}, {@link #registerSound(String, VisualType)},
 * or {@link #registerRule(Predicate, VisualType)} during their mod initialization to customize sound styling.
 */
public final class VisualTypeRegistry {
    private static final Map<String, VisualType> TYPES = new ConcurrentHashMap<>();
    private static final Map<String, VisualType> SOUND_MAP = new ConcurrentHashMap<>();
    private static final Map<String, VisualColor> SOUND_COLORS = new ConcurrentHashMap<>();
    private static final List<Rule> PATTERNS = new CopyOnWriteArrayList<>();

    private record ColorCacheEntry(VisualType baseType, VisualColor color) {}

    private static final class ResolutionCaches {
        final BoundedLookupCache<String, VisualType> types = new BoundedLookupCache<>(256, 4096, 512);
        final BoundedLookupCache<String, ColorCacheEntry> colors = new BoundedLookupCache<>(256, 4096, 512);
    }
    private static volatile ResolutionCaches caches = new ResolutionCaches();

    public record Rule(Predicate<String> matcher, VisualType type) {}

    static {
        initBuiltins();
    }

    /** Invalidates derived sound-to-type and color caches when rules or overrides change. */
    public static void invalidateResolutionCaches() {
        // Readers finishing a lookup begun before reload can only write into the old caches.
        caches = new ResolutionCaches();
    }

    /** Invalidates derived caches for a single sound ID. */
    public static void invalidateSound(String soundId) {
        if (soundId != null) {
            var current = caches;
            current.types.remove(soundId);
            current.colors.remove(soundId);
        }
    }

    /**
     * Re-initializes built-in visual types and purges dynamic external mappings for test isolation.
     */
    public static void resetForTesting() {
        TYPES.clear();
        SOUND_MAP.clear();
        SOUND_COLORS.clear();
        PATTERNS.clear();
        invalidateResolutionCaches();
        initBuiltins();
    }

    private static void initBuiltins() {
        registerType(VisualTypes.INTERACTION);
        registerType(VisualTypes.BREAK);
        registerType(VisualTypes.PICKUP);
        registerType(VisualTypes.VOICE);
        registerType(VisualTypes.IMPACT);
        registerType(VisualTypes.AMBIENT);
        registerType(VisualTypes.RAIN);
        registerType(VisualTypes.CAMPFIRE);
        registerType(VisualTypes.CREEPER_PRIMED);
        registerType(VisualTypes.CRITICAL_HIT);
        registerType(VisualTypes.SWEEP_ATTACK);
        registerType(VisualTypes.SHIELD_BLOCK);
        registerType(VisualTypes.SHIELD_BREAK);
        registerType(VisualTypes.DAMAGE_BLUNT);
        registerType(VisualTypes.DAMAGE_PIERCE);
        registerType(VisualTypes.DAMAGE_FALL);
        registerType(VisualTypes.WARDEN_HEART);
        registerType(VisualTypes.WARDEN_SONIC);
        registerType(VisualTypes.ENDERMAN_TP);
        registerType(VisualTypes.TOOL_BREAK);
        registerType(VisualTypes.BELL_RESONANCE);
        registerType(VisualTypes.NETHER_SOUL);
        registerType(VisualTypes.NETHER_ASH);

        // Explicit standard vanilla pickups
        registerSound("minecraft:entity.item.pickup", VisualTypes.PICKUP);
        registerSound("minecraft:entity.experience_orb.pickup", VisualTypes.PICKUP);

        // Atmospheric rain and chimney/campfire embers
        registerSound("minecraft:weather.rain", VisualTypes.RAIN);
        registerSound("minecraft:weather.rain.above", VisualTypes.RAIN);
        registerSound("minecraft:block.campfire.crackle", VisualTypes.CAMPFIRE);
        registerSound("minecraft:block.fire.ambient", VisualTypes.CAMPFIRE);
        registerSound("minecraft:block.lava.ambient", VisualTypes.CAMPFIRE);
        registerSound("minecraft:block.lava.pop", VisualTypes.CAMPFIRE);

        // High-threat creeper hiss countdown swell
        registerSound("minecraft:entity.creeper.primed", VisualTypes.CREEPER_PRIMED);

        // Combat attacks, crits, and shields
        registerSound("minecraft:entity.player.attack.crit", VisualTypes.CRITICAL_HIT);
        registerSound("minecraft:entity.player.attack.strong", VisualTypes.CRITICAL_HIT);
        registerSound("minecraft:entity.player.attack.sweep", VisualTypes.SWEEP_ATTACK);
        registerSound("minecraft:item.shield.block", VisualTypes.SHIELD_BLOCK);
        registerSound("minecraft:item.shield.break", VisualTypes.SHIELD_BREAK);

        // Piercing and projectiles
        registerSound("minecraft:entity.arrow.hit", VisualTypes.DAMAGE_PIERCE);
        registerSound("minecraft:item.trident.hit", VisualTypes.DAMAGE_PIERCE);
        registerSound("minecraft:enchant.thorns.hit", VisualTypes.DAMAGE_PIERCE);

        // Direct damage and fall impacts
        registerSound("minecraft:entity.player.hurt", VisualTypes.DAMAGE_BLUNT);
        registerSound("minecraft:entity.generic.hurt", VisualTypes.DAMAGE_BLUNT);
        registerSound("minecraft:entity.player.small_fall", VisualTypes.DAMAGE_FALL);
        registerSound("minecraft:entity.player.big_fall", VisualTypes.DAMAGE_FALL);
        registerSound("minecraft:entity.generic.small_fall", VisualTypes.DAMAGE_FALL);
        registerSound("minecraft:entity.generic.big_fall", VisualTypes.DAMAGE_FALL);

        // Boss and dimensional specials
        registerSound("minecraft:entity.warden.heartbeat", VisualTypes.WARDEN_HEART);
        registerSound("minecraft:entity.warden.sonic_boom", VisualTypes.WARDEN_SONIC);
        registerSound("minecraft:entity.warden.roar", VisualTypes.WARDEN_SONIC);
        registerSound("minecraft:entity.enderman.teleport", VisualTypes.ENDERMAN_TP);

        // Tool break climax
        registerSound("minecraft:entity.item.break", VisualTypes.TOOL_BREAK);

        // Bell resonance
        registerSound("minecraft:block.bell.use", VisualTypes.BELL_RESONANCE);
        registerSound("minecraft:block.bell.resonate", VisualTypes.BELL_RESONANCE);

        // Nether ambient sub-biomes
        registerSound("minecraft:ambient.soul_sand_valley.mood", VisualTypes.NETHER_SOUL);
        registerSound("minecraft:ambient.basalt_deltas.mood", VisualTypes.NETHER_ASH);

        // Promoted battlefield macro-LOD chorus categories
        registerSound("onomixel.chorus.zombie", VisualTypes.VOICE);
        registerSound("onomixel.chorus.beast", VisualTypes.VOICE);
        registerSound("onomixel.chorus.arrow_rain", VisualTypes.DAMAGE_PIERCE);
        registerSound("onomixel.chorus.arthropod", VisualTypes.INTERACTION);
        registerSound("onomixel.chorus.collapse", VisualTypes.BREAK);
        registerSound("onomixel.chorus.generic", VisualTypes.AMBIENT);

        // Extension rules run before the semantic builtin fallback, never behind a broad builtin rule.
    }

    private VisualTypeRegistry() {}

    /**
     * Registers a new or custom VisualType.
     *
     * @param type the visual type instance
     */
    public static void registerType(VisualType type) {
        if (type != null) {
            VisualType previous = TYPES.put(type.id(), type);
            if (previous != type) {
                invalidateResolutionCaches();
            }
        }
    }

    /**
     * Binds an exact sound ID (e.g. "mymod:magic_cast") to a VisualType.
     *
     * @param soundId the full sound event ID
     * @param type    the visual type
     */
    public static void registerSound(String soundId, VisualType type) {
        if (soundId != null && type != null) {
            SOUND_MAP.put(soundId, type);
            invalidateSound(soundId);
        }
    }

    /**
     * Binds an exact sound ID to custom vibrant and pastel colors.
     *
     * @param soundId      the full sound event ID
     * @param vibrantColor high-contrast comic RGB
     * @param pastelColor  soft pastel RGB
     */
    public static void registerSoundColor(String soundId, int vibrantColor, int pastelColor) {
        if (soundId != null) {
            SOUND_COLORS.put(soundId, new VisualColor(vibrantColor, pastelColor));
            caches.colors.remove(soundId);
        }
    }

    /**
     * Unregisters an explicit sound color override.
     */
    public static void unregisterSoundColor(String soundId) {
        if (soundId != null) {
            SOUND_COLORS.remove(soundId);
            caches.colors.remove(soundId);
        }
    }

    /**
     * Resolves custom rule colors, then explicitly registered colors and material defaults.
     */
    public static VisualColor resolveColor(String soundId, VisualType baseType) {
        if (soundId == null) {
            return new VisualColor(baseType.vibrantColor(), baseType.pastelColor());
        }
        var colors = caches.colors;
        ColorCacheEntry cached = colors.get(soundId);
        if (cached != null && cached.baseType() == baseType) {
            return cached.color();
        }
        VisualColor resolved = computeColor(soundId, baseType);
        colors.put(soundId, new ColorCacheEntry(baseType, resolved));
        return resolved;
    }

    private static VisualColor computeColor(String soundId, VisualType baseType) {
        CustomWordRule customRule = SoundCustomizationManager.resolve(soundId);
        if (customRule != null && (customRule.vibrantColor() != null || customRule.pastelColor() != null)) {
            int vibrant = customRule.vibrantColor() != null ? customRule.vibrantColor() : baseType.vibrantColor();
            int pastel = customRule.pastelColor() != null ? customRule.pastelColor() : vibrant;
            return new VisualColor(vibrant, pastel);
        }
        VisualColor explicit = SOUND_COLORS.get(soundId);
        if (explicit != null) return explicit;
        if (!soundId.startsWith("minecraft:") || !isBuiltin(baseType))
            return new VisualColor(baseType.vibrantColor(), baseType.pastelColor());
        return MaterialColorResolver.resolve(soundId, baseType);
    }

    /**
     * Binds a rule predicate to a VisualType. Evaluated if no exact sound ID match is registered.
     *
     * @param matcher predicate testing sound event ID
     * @param type    the visual type
     */
    public static void registerRule(Predicate<String> matcher, VisualType type) {
        if (matcher != null && type != null) {
            PATTERNS.add(new Rule(matcher, type));
            invalidateResolutionCaches();
        }
    }

    /**
     * Resolves a sound ID to its corresponding VisualType.
     *
     * @param soundId the sound event ID
     * @return the resolved VisualType, or {@link VisualTypes#IMPACT} as default
     */
    public static VisualType resolve(String soundId) {
        if (soundId == null) return VisualTypes.IMPACT;
        var types = caches.types;
        VisualType cached = types.get(soundId);
        if (cached != null) return cached;
        VisualType resolved = computeResolve(soundId, SoundCustomizationManager.resolve(soundId));
        types.put(soundId, resolved);
        return resolved;
    }

    /**
     * Resolves a sound ID to its corresponding VisualType using a pre-resolved custom rule on cache miss.
     */
    public static VisualType resolve(String soundId, CustomWordRule customRule) {
        if (soundId == null) return VisualTypes.IMPACT;
        var types = caches.types;
        VisualType cached = types.get(soundId);
        if (cached != null) return cached;
        VisualType resolved = computeResolve(soundId, customRule);
        types.put(soundId, resolved);
        return resolved;
    }

    private static VisualType computeResolve(String soundId, CustomWordRule customRule) {
        if (customRule != null && customRule.visualTypeId() != null) {
            VisualType customVt = getType(customRule.visualTypeId());
            if (customVt != null) return customVt;
        }
        var pack = SoundCustomizationManager.legacyPackDefinition(soundId);
        if (pack != null) return pack.visualType();
        VisualType explicit = SOUND_MAP.get(soundId);
        if (explicit != null) return explicit;
        for (Rule rule : PATTERNS) {
            if (rule.matcher.test(soundId)) return rule.type;
        }
        VisualType inferred = SoundDefinitions.inferredVisualType(soundId);
        if (inferred != null) return inferred;
        return switch (SoundSemantics.classify(soundId)) {
            case INTERACTION -> VisualTypes.INTERACTION;
            case BREAK -> VisualTypes.BREAK;
            case PICKUP -> VisualTypes.PICKUP;
            case IDLE_VOICE, VOICE -> VisualTypes.VOICE;
            case AMBIENT -> VisualTypes.AMBIENT;
            case DAMAGE -> VisualTypes.DAMAGE_BLUNT;
            default -> VisualTypes.IMPACT;
        };
    }

    private static boolean isBuiltin(VisualType type) {
        return type == VisualTypes.INTERACTION || type == VisualTypes.BREAK || type == VisualTypes.PICKUP
                || type == VisualTypes.VOICE || type == VisualTypes.IMPACT || type == VisualTypes.AMBIENT
                || type == VisualTypes.RAIN || type == VisualTypes.CAMPFIRE || type == VisualTypes.CREEPER_PRIMED
                || type == VisualTypes.CRITICAL_HIT || type == VisualTypes.SWEEP_ATTACK
                || type == VisualTypes.SHIELD_BLOCK || type == VisualTypes.SHIELD_BREAK
                || type == VisualTypes.DAMAGE_BLUNT || type == VisualTypes.DAMAGE_PIERCE
                || type == VisualTypes.DAMAGE_FALL || type == VisualTypes.WARDEN_HEART
                || type == VisualTypes.WARDEN_SONIC || type == VisualTypes.ENDERMAN_TP;
    }

    /**
     * Returns an unmodifiable collection of all registered visual types.
     */
    public static Collection<VisualType> allTypes() {
        return Collections.unmodifiableCollection(TYPES.values());
    }

    /**
     * Finds a visual type by its ID (accepting shorthand built-in names without {@code onomixel:}).
     */
    public static VisualType getType(String id) {
        if (id == null) return null;
        VisualType exact = TYPES.get(id);
        if (exact != null || id.indexOf(':') >= 0) {
            return exact;
        }
        return TYPES.get("onomixel:" + id);
    }
}
