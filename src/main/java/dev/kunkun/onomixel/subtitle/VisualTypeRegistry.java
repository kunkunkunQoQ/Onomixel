package dev.kunkun.onomixel.subtitle;

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

    public record Rule(Predicate<String> matcher, VisualType type) {}

    static {
        initBuiltins();
    }

    /**
     * Re-initializes built-in visual types and purges dynamic external mappings for test isolation.
     */
    public static void resetForTesting() {
        TYPES.clear();
        SOUND_MAP.clear();
        SOUND_COLORS.clear();
        PATTERNS.clear();
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
            TYPES.put(type.id(), type);
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
        }
    }

    /**
     * Unregisters an explicit sound color override.
     */
    public static void unregisterSoundColor(String soundId) {
        if (soundId != null) {
            SOUND_COLORS.remove(soundId);
        }
    }

    /**
     * Resolves the color for a sound ID, prioritizing explicit overrides then material-semantic resolution.
     */
    public static VisualColor resolveColor(String soundId, VisualType baseType) {
        if (soundId != null) {
            VisualColor explicit = SOUND_COLORS.get(soundId);
            if (explicit != null) return explicit;
        }
        if (soundId == null || !soundId.startsWith("minecraft:") || !isBuiltin(baseType))
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
        VisualType explicit = SOUND_MAP.get(soundId);
        if (explicit != null) return explicit;
        for (Rule rule : PATTERNS) {
            if (rule.matcher.test(soundId)) return rule.type;
        }
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
     * Finds a visual type by its ID.
     */
    public static VisualType getType(String id) {
        return TYPES.get(id);
    }
}
