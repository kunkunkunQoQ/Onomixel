package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundEventPolicy;
import dev.kunkun.onomixel.sound.SoundSemantics;

/**
 * Resolves material and semantic comic colors from sound IDs.
 * <p>
 * Decouples physics motion dynamics (e.g. footsteps being a light impact)
 * from visual color semantics (e.g. footsteps on sand being warm golden, not hurt-red).
 */
public final class MaterialColorResolver {
    private MaterialColorResolver() {}

    // Footstep & Ground Contact material palettes
    private static final VisualColor FOOTSTEP_SAND = new VisualColor(0xF4C430, 0xF5E6AB);   // Warm Sand Gold
    private static final VisualColor FOOTSTEP_GRASS = new VisualColor(0x68D391, 0xC6F6D5);  // Fresh Grass Green
    private static final VisualColor FOOTSTEP_WOOD = new VisualColor(0xD97706, 0xFDE68A);   // Warm Timber Amber
    private static final VisualColor FOOTSTEP_STONE = new VisualColor(0xCBD5E1, 0xF1F5F9);  // Cool Stone Gray
    private static final VisualColor FOOTSTEP_METAL = new VisualColor(0x38BDF8, 0xE0F2FE);  // Steel Silver Blue
    private static final VisualColor FOOTSTEP_SNOW = new VisualColor(0xF8FAFC, 0xFFFFFF);   // Snow / Soft White
    private static final VisualColor FOOTSTEP_GLASS = new VisualColor(0x67E8F9, 0xCFFAFE);  // Crystal Cyan
    private static final VisualColor FOOTSTEP_SLIME = new VisualColor(0xA3E635, 0xECFCCB);  // Slime Green
    private static final VisualColor FOOTSTEP_WATER = new VisualColor(0x0EA5E9, 0xBAE6FD);  // Splash Azure
    private static final VisualColor FOOTSTEP_SOUL = new VisualColor(0x2DD4BF, 0xCCFBF1);   // Ethereal Soul Teal
    private static final VisualColor FOOTSTEP_DEFAULT = new VisualColor(0xE2E8F0, 0xF8FAFC);// Neutral Movement Gray

    // Combat & Damage palettes
    private static final VisualColor COMBAT_EXPLOSION = new VisualColor(0xDC2626, 0xFCA5A5);// Blaze Red
    private static final VisualColor COMBAT_CRIT = new VisualColor(0xF97316, 0xFED7AA);     // Impact Flame Orange
    private static final VisualColor COMBAT_HURT = new VisualColor(0xEF4444, 0xFECACA);     // Alert Crimson Red
    private static final VisualColor COMBAT_SPARK = new VisualColor(0xF59E0B, 0xFEF3C7);    // Spark Amber

    // Block Break palettes
    private static final VisualColor BREAK_GLASS = new VisualColor(0x67E8F9, 0xCFFAFE);     // Glass Crystal Cyan
    private static final VisualColor BREAK_FOLIAGE = new VisualColor(0x86EFAC, 0xDCFCE7);   // Leaves / Grass Green
    private static final VisualColor BREAK_WOOD = new VisualColor(0xD97706, 0xFDE68A);      // Wood Amber
    private static final VisualColor BREAK_SNOW = new VisualColor(0xF1F5F9, 0xFFFFFF);      // Snow White

    // Entity Voice palettes
    private static final VisualColor VOICE_UNDEAD = new VisualColor(0x4ADE80, 0xDCFCE7);    // Eerie Toxic Green
    private static final VisualColor VOICE_OMINOUS = new VisualColor(0xC084FC, 0xF3E8FF);   // Mystic Void Purple
    private static final VisualColor VOICE_PASSIVE = new VisualColor(0x60A5FA, 0xDBEAFE);   // Friendly Sky Blue
    private static final VisualColor VOICE_VILLAGER = new VisualColor(0x38BDF8, 0xE0F2FE);  // Community Blue

    // Environment palettes
    private static final VisualColor AMBIENT_RAIN = new VisualColor(0x0284C7, 0xBAE6FD);    // Rain Sky Blue
    private static final VisualColor AMBIENT_FIRE = new VisualColor(0xEA580C, 0xFDBA74);    // Inferno Flame
    private static final VisualColor AMBIENT_SOUL = new VisualColor(0x2DD4BF, 0xCCFBF1);    // Soul / Portal Teal

    /**
     * Resolves the semantic color pair for a sound event ID and its base visual type.
     */
    public static VisualColor resolve(String soundId, VisualType baseType) {
        if (soundId == null) {
            return baseType != null ? new VisualColor(baseType.vibrantColor(), baseType.pastelColor())
                    : new VisualColor(0xFFFFFF, 0xFFFFFF);
        }

        SoundSemantics semantics = SoundSemantics.classify(soundId);
        return switch (semantics) {
            case DAMAGE -> COMBAT_HURT;
            case ATTACK -> resolveAttackColor(soundId);
            case STEP, GALLOP, HEAVY_STEP -> resolveFootstepColor(soundId);
            case LANDING -> soundId.startsWith("minecraft:block.")
                    ? resolveFootstepColor(soundId) : COMBAT_CRIT;
            case BREAK -> soundId.startsWith("minecraft:block.")
                    ? resolveBreakColor(soundId, baseType) : baseColor(baseType);
            case IDLE_VOICE, VOICE -> resolveVoiceColor(soundId);
            case AMBIENT -> resolveAmbientColor(soundId);
            case INTERACTION, PICKUP, OTHER -> isWaterMotionSound(soundId)
                    ? FOOTSTEP_WATER : baseColor(baseType);
        };
    }

    private static VisualColor resolveAttackColor(String id) {
        if (id.contains(".explode") || id.contains(".explosion") || id.endsWith(".primed")
                || id.contains(".blast") || id.contains(".sonic_boom")) return COMBAT_EXPLOSION;
        if (id.contains(".crit") || id.contains(".strong") || id.contains(".knockback")) return COMBAT_CRIT;
        if (id.contains("arrow") || id.contains("trident") || id.contains(".hit")) return COMBAT_SPARK;
        return COMBAT_CRIT;
    }

    private static VisualColor resolveBreakColor(String id, VisualType baseType) {
        if (id.contains("glass") || id.contains("amethyst")) return BREAK_GLASS;
        if (id.contains("leaves") || id.contains("grass") || id.contains("vine") || id.contains("moss")) return BREAK_FOLIAGE;
        if (id.contains("wood") || id.contains("bamboo") || id.contains("plank") || id.contains("log")) return BREAK_WOOD;
        if (id.contains("wool") || id.contains("snow")) return BREAK_SNOW;
        return baseColor(baseType);
    }

    private static VisualColor resolveVoiceColor(String id) {
        if (isUndeadOrHostile(id)) return VOICE_UNDEAD;
        if (isOminousMob(id)) return VOICE_OMINOUS;
        if (id.contains("villager") || id.contains("wandering_trader") || id.contains("iron_golem")) return VOICE_VILLAGER;
        return VOICE_PASSIVE;
    }

    private static VisualColor resolveAmbientColor(String id) {
        if (SoundEventPolicy.isRain(id)) return AMBIENT_RAIN;
        if (id.contains(".fire.") || id.contains(".lava.")) return AMBIENT_FIRE;
        if (id.contains(".portal.") || id.contains(".soul_sand.")) return AMBIENT_SOUL;
        return new VisualColor(0x6EE7B7, 0xDFF5F0);
    }

    private static boolean isWaterMotionSound(String id) {
        return id.endsWith(".swim") || id.contains(".swim.") || id.endsWith(".splash")
                || id.endsWith(".splash.high_speed");
    }

    private static VisualColor baseColor(VisualType baseType) {
        return baseType != null ? new VisualColor(baseType.vibrantColor(), baseType.pastelColor())
                : new VisualColor(0xFFFFFF, 0xFFFFFF);
    }

    private static VisualColor resolveFootstepColor(String id) {
        if (id.contains("sand") || id.contains("gravel") || id.contains("dirt") || id.contains("mud")) {
            if (id.contains("soul")) return FOOTSTEP_SOUL;
            return FOOTSTEP_SAND;
        }
        if (id.contains("grass") || id.contains("azalea") || id.contains("moss") || id.contains("roots") || id.contains("leaves") || id.contains("crop")) {
            return FOOTSTEP_GRASS;
        }
        if (id.contains("wood") || id.contains("bamboo") || id.contains("stem") || id.contains("scaffolding")) {
            return FOOTSTEP_WOOD;
        }
        if (id.contains("stone") || id.contains("deepslate") || id.contains("dripstone") || id.contains("netherrack") || id.contains("basalt") || id.contains("tuff") || id.contains("calcite")) {
            return FOOTSTEP_STONE;
        }
        if (id.contains("metal") || id.contains("copper") || id.contains("iron") || id.contains("chain") || id.contains("anvil") || id.contains("netherite")) {
            return FOOTSTEP_METAL;
        }
        if (id.contains("snow") || id.contains("wool") || id.contains("sponge") || id.contains("web")) {
            return FOOTSTEP_SNOW;
        }
        if (id.contains("glass") || id.contains("amethyst") || id.contains("coral")) {
            return FOOTSTEP_GLASS;
        }
        if (id.contains("slime") || id.contains("honey")) {
            return FOOTSTEP_SLIME;
        }
        if (id.contains("water") || id.contains("splash") || id.contains("swim")) {
            return FOOTSTEP_WATER;
        }
        return FOOTSTEP_DEFAULT;
    }

    private static boolean isUndeadOrHostile(String id) {
        return id.contains("zombie") || id.contains("skeleton") || id.contains("drowned") || id.contains("husk")
                || id.contains("stray") || id.contains("bogged") || id.contains("spider") || id.contains("phantom")
                || id.contains("creeper") || id.contains("piglin_brute") || id.contains("zoglin");
    }

    private static boolean isOminousMob(String id) {
        return id.contains("enderman") || id.contains("warden") || id.contains("evoker") || id.contains("vex")
                || id.contains("wither") || id.contains("ender_dragon") || id.contains("shulker") || id.contains("elder_guardian");
    }
}
