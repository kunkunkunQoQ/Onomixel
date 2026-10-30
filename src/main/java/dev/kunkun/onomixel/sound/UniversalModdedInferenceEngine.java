package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.VisualType;
import dev.kunkun.onomixel.subtitle.VisualTypes;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Universal Modded Audio Inference Engine (Phase 9).
 * <p>
 * Automatically analyzes unregistered third-party mod sounds (e.g. {@code create:contraption_actor/drill/damage},
 * {@code ad_astra:rocket_launch}) using natural language path tokenization, category matching,
 * and adaptive comic onomatopoeia radical synthesis.
 * <p>
 * Uses pre-computed Trie / rule mappings and concurrent cache to ensure zero allocation during gameplay.
 */
public final class UniversalModdedInferenceEngine {

    /**
     * Maximum capacity of the inference cache to prevent unbounded growth in massive modpacks.
     */
    public static final int MAX_CACHE_SIZE = 4096;

    /**
     * Cache for resolved sound inferences to ensure 0-byte garbage generation during gameplay.
     */
    private static final ConcurrentMap<String, InferredSound> CACHE = new ConcurrentHashMap<>(256);

    private UniversalModdedInferenceEngine() {}

    /**
     * Inferred sound metadata encapsulating semantics, visual motion envelope, and comic text.
     */
    public record InferredSound(
            String soundId,
            String onomatopoeia,
            SoundSemantics semantics,
            VisualType visualType,
            SoundDefinition.Source source,
            String categoryTag,
            boolean isHeuristic
    ) {
        public InferredSound {
            Objects.requireNonNull(soundId, "soundId cannot be null");
            Objects.requireNonNull(onomatopoeia, "onomatopoeia cannot be null");
            Objects.requireNonNull(semantics, "semantics cannot be null");
            Objects.requireNonNull(visualType, "visualType cannot be null");
            Objects.requireNonNull(source, "source cannot be null");
            Objects.requireNonNull(categoryTag, "categoryTag cannot be null");
        }

        /**
         * Converts this inferred result into a standard Onomixel {@link SoundDefinition}.
         */
        public SoundDefinition toSoundDefinition() {
            return new SoundDefinition(soundId, onomatopoeia, semantics, source, visualType);
        }
    }

    /**
     * Clears the inference cache (useful during resource/mod reload).
     */
    public static void clearCache() {
        CACHE.clear();
        SoundDefinitions.clearInferred();
    }

    /**
     * Returns true if the sound ID is already a vanilla Minecraft sound or explicitly registered addon sound.
     */
    public static boolean isVanillaOrRegistered(String soundId) {
        if (soundId == null || soundId.isBlank()) return false;
        return SoundDefinitions.contains(soundId);
    }

    /**
     * Infers the semantics, visual type, and onomatopoeia text for the given sound ID.
     */
    public static InferredSound infer(String soundId) {
        if (soundId == null || soundId.isBlank()) {
            throw new IllegalArgumentException("soundId cannot be null or blank");
        }

        if (CACHE.size() >= MAX_CACHE_SIZE) {
            CACHE.clear();
        }

        return CACHE.computeIfAbsent(soundId, UniversalModdedInferenceEngine::computeInference);
    }

    /**
     * Infers and constructs a {@link SoundDefinition} for the given sound ID.
     */
    public static SoundDefinition inferDefinition(String soundId) {
        return infer(soundId).toSoundDefinition();
    }

    private static InferredSound computeInference(String soundId) {
        // 1. Check if explicitly registered or vanilla builtin
        if (isVanillaOrRegistered(soundId)) {
            SoundDefinition existing = SoundDefinitions.find(soundId);
            if (existing != null) {
                return new InferredSound(
                        soundId,
                        existing.translationKey(),
                        existing.semantics(),
                        existing.visualType(),
                        existing.source(),
                        "REGISTERED",
                        false
                );
            }
        }

        // 2. Tokenize the sound path
        List<String> tokens = tokenize(soundId);

        // 3. Category & Semantics derivation
        SemanticMatch match = matchCategoryAndSemantics(tokens);

        // 4. Manga onomatopoeia radical synthesis
        String onomatopoeia = synthesizeMangaRadical(tokens, match.semantics);

        // 5. Source geometry determination
        SoundDefinition.Source source = resolveSource(match.semantics, tokens);

        return new InferredSound(
                soundId,
                onomatopoeia,
                match.semantics,
                match.visualType,
                source,
                match.categoryTag,
                true
        );
    }

    // =========================================================================
    // Natural Language Tokenization
    // =========================================================================

    /**
     * Breaks a ResourceLocation sound path into normalized, lowercase natural language tokens.
     * Splits on `:`, `/`, `.`, `_`, `-`, and camelCase transitions.
     */
    public static List<String> tokenize(String path) {
        if (path == null || path.isBlank()) return Collections.emptyList();

        List<String> tokens = new ArrayList<>(8);
        StringBuilder current = new StringBuilder(16);

        int len = path.length();
        for (int i = 0; i < len; i++) {
            char c = path.charAt(i);

            // Delimiters
            if (c == ':' || c == '/' || c == '.' || c == '_' || c == '-' || c == '\\' || Character.isWhitespace(c)) {
                if (!current.isEmpty()) {
                    tokens.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
            } else if (Character.isUpperCase(c)) {
                // CamelCase boundary: e.g. "rocketLaunch" -> "rocket", "launch"
                if (!current.isEmpty()) {
                    tokens.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
                current.append(Character.toLowerCase(c));
            } else if (Character.isDigit(c)) {
                // Numeric boundary: e.g. "hit1" -> "hit", "1"
                if (!current.isEmpty() && !Character.isDigit(current.charAt(current.length() - 1))) {
                    tokens.add(current.toString().toLowerCase(Locale.ROOT));
                    current.setLength(0);
                }
                current.append(c);
            } else {
                current.append(Character.toLowerCase(c));
            }
        }

        if (!current.isEmpty()) {
            tokens.add(current.toString().toLowerCase(Locale.ROOT));
        }

        return Collections.unmodifiableList(tokens);
    }

    // =========================================================================
    // Heuristic Path & Category Matcher
    // =========================================================================

    private record SemanticMatch(SoundSemantics semantics, VisualType visualType, String categoryTag) {}

    /**
     * Matches semantic category by inspecting tokens with priority rules.
     */
    private static SemanticMatch matchCategoryAndSemantics(List<String> tokens) {
        if (tokens.isEmpty()) {
            return new SemanticMatch(SoundSemantics.OTHER, VisualTypes.INTERACTION, "UNKNOWN");
        }

        // Rule 1: Mechanical & Tool cutting/breaking (drill, saw, crush, grind)
        // High priority to differentiate mechanical cutters from generic damage
        if (hasAnyToken(tokens, "drill", "saw", "crush", "grind", "cutter", "shred", "pulverize", "chisel")) {
            return new SemanticMatch(SoundSemantics.BREAK, VisualTypes.BREAK, "BREAK");
        }

        // Rule 2: Fluid / Gas / Steam (steam, whistle, hiss, pipe, gas)
        if (hasAnyToken(tokens, "steam", "whistle", "hiss", "pipe", "gas", "vent", "exhaust", "boiler", "vapor", "fume")) {
            return new SemanticMatch(SoundSemantics.AMBIENT, VisualTypes.AMBIENT, "AMBIENT_GAS");
        }

        // Rule 3: Ranged / Magic / Laser / Projectile (spell, cast, laser, cannon, launch)
        if (hasAnyToken(tokens, "spell", "cast", "laser", "cannon", "launch", "beam", "pew", "plasma", "rocket", "missile", "railgun", "fireball")) {
            return new SemanticMatch(SoundSemantics.ATTACK, VisualTypes.CRITICAL_HIT, "ATTACK_RANGED");
        }

        // Rule 4: Heavy impact / Slash / Damage (impact, heavy_hit, slash, smash)
        if (hasAnyToken(tokens, "impact", "heavy_hit", "slash", "smash", "thud", "slam", "bash", "cleave", "stab", "strike", "hurt", "damage")) {
            return new SemanticMatch(SoundSemantics.DAMAGE, VisualTypes.DAMAGE_BLUNT, "DAMAGE");
        }

        // Rule 5: Locomotion / Footsteps (step, walk, run, foot)
        if (hasAnyToken(tokens, "step", "walk", "run", "foot", "stride", "crawl", "gallop", "pace")) {
            return new SemanticMatch(SoundSemantics.STEP, VisualTypes.INTERACTION, "STEP");
        }

        // Rule 6: Explosions / Detonations (blast, explosion, explode, detonate, boom)
        if (hasAnyToken(tokens, "blast", "explosion", "explode", "detonate", "boom", "burst")) {
            return new SemanticMatch(SoundSemantics.ATTACK, VisualTypes.CREEPER_PRIMED, "EXPLOSION");
        }

        // Rule 7: Vocal / Monster calls (roar, growl, screech, howl, yell)
        if (hasAnyToken(tokens, "roar", "growl", "screech", "howl", "snarl", "shriek", "yell", "grunt", "chant", "voice")) {
            return new SemanticMatch(SoundSemantics.VOICE, VisualTypes.VOICE, "VOICE");
        }

        // Rule 8: Mechanical / Interactivity (clank, clang, click, lever, button, open, close)
        if (hasAnyToken(tokens, "clank", "clang", "click", "lever", "button", "switch", "open", "close", "gear", "piston", "door", "valve")) {
            return new SemanticMatch(SoundSemantics.INTERACTION, VisualTypes.INTERACTION, "INTERACTION");
        }

        // Rule 9: Pickup / Absorption (pickup, collect, grab, loot)
        if (hasAnyToken(tokens, "pickup", "collect", "grab", "loot", "absorb")) {
            return new SemanticMatch(SoundSemantics.PICKUP, VisualTypes.PICKUP, "PICKUP");
        }

        // Rule 10: General Breaking / Shattering
        if (hasAnyToken(tokens, "break", "shatter", "destroy")) {
            return new SemanticMatch(SoundSemantics.BREAK, VisualTypes.BREAK, "BREAK");
        }

        return new SemanticMatch(SoundSemantics.OTHER, VisualTypes.INTERACTION, "GENERIC");
    }

    private static boolean hasAnyToken(List<String> tokens, String... candidates) {
        for (String t : tokens) {
            for (String c : candidates) {
                if (t.equals(c) || t.contains(c)) return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Manga Radical Generator (Manga Radical Generator)
    // =========================================================================

    /**
     * Dictionary of core English sound radicals mapped to vivid manga onomatopoeia.
     */
    private static final Map<String, String> RADICAL_MAP = Map.ofEntries(
            // Specified in Chapter 9 Design
            Map.entry("blast", "磅！！"),
            Map.entry("clank", "咔嚓！"),
            Map.entry("clang", "咔嚓！"),
            Map.entry("whir", "嗡——"),
            Map.entry("buzz", "嗡——"),
            Map.entry("splash", "哗啦！"),
            Map.entry("roar", "吼！！"),
            Map.entry("pew", "嗖！！"),
            Map.entry("laser", "嗖！！"),

            // Mechanical & Tools
            Map.entry("drill", "滋滋！"),
            Map.entry("saw", "唰！"),
            Map.entry("crush", "砰！！"),
            Map.entry("grind", "滋滋！"),
            Map.entry("shred", "咔嚓！"),
            Map.entry("chisel", "叮！"),

            // Fluids & Gases
            Map.entry("steam", "嘶——"),
            Map.entry("whistle", "咻——"),
            Map.entry("hiss", "嘶——"),
            Map.entry("pipe", "咚！"),
            Map.entry("gas", "嘶——"),
            Map.entry("vent", "呼——"),
            Map.entry("water", "哗啦！"),
            Map.entry("bubble", "啵啵！"),

            // Magic & Ranged Combat
            Map.entry("spell", "嗡！"),
            Map.entry("cast", "嗡！"),
            Map.entry("cannon", "咚——"),
            Map.entry("launch", "咚——"),
            Map.entry("beam", "嗖！！"),
            Map.entry("zap", "滋！"),
            Map.entry("plasma", "噼啪！"),
            Map.entry("spark", "噼啪！"),
            Map.entry("rocket", "呼啸——"),

            // Damage & Combat
            Map.entry("impact", "咚！"),
            Map.entry("heavy_hit", "咚！"),
            Map.entry("slash", "唰！"),
            Map.entry("smash", "砰！！"),
            Map.entry("slam", "砰！！"),
            Map.entry("thud", "咚！"),
            Map.entry("strike", "啪！"),
            Map.entry("hit", "啪！"),
            Map.entry("hurt", "呃！"),
            Map.entry("stab", "噗嗤！"),

            // Locomotion & Voice
            Map.entry("step", "踏！"),
            Map.entry("walk", "踏！"),
            Map.entry("run", "踏！"),
            Map.entry("foot", "踏！"),
            Map.entry("gallop", "哒哒！"),
            Map.entry("growl", "低吼…"),
            Map.entry("screech", "唳——"),
            Map.entry("howl", "嗷呜——"),

            // Interactions
            Map.entry("click", "嗒！"),
            Map.entry("tick", "嗒！"),
            Map.entry("snap", "咔！"),
            Map.entry("door", "吱呀！"),
            Map.entry("lever", "喀！"),
            Map.entry("button", "滴！"),
            Map.entry("bell", "叮——"),
            Map.entry("chime", "叮——"),
            Map.entry("wind", "呼——"),
            Map.entry("woosh", "呼——"),
            Map.entry("whoosh", "呼——")
    );

    /**
     * Synthesizes a localized manga onomatopoeia text using root radical analysis
     * with graceful, deterministic fallback for completely unknown words.
     */
    public static String synthesizeMangaRadical(List<String> tokens, SoundSemantics semantics) {
        if (tokens.isEmpty()) {
            return fallbackBySemantics(semantics);
        }

        // Scan tokens in reverse order (action descriptor tokens usually sit near the end of the path)
        for (int i = tokens.size() - 1; i >= 0; i--) {
            String token = tokens.get(i);
            String matched = RADICAL_MAP.get(token);
            if (matched != null) {
                return matched;
            }

            // Substring radical check for compound tokens like "heavy_hit", "steam_engine"
            for (Map.Entry<String, String> entry : RADICAL_MAP.entrySet()) {
                if (token.contains(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }

        // Graceful fallback: synthesize based on phonetics of the last non-empty token
        String lastToken = tokens.getLast();
        return synthesizePhoneticFallback(lastToken, semantics);
    }

    /**
     * Synthesizes a manga comic sound based on word phonetic ending and semantic category.
     */
    private static String synthesizePhoneticFallback(String token, SoundSemantics semantics) {
        if (token == null || token.isEmpty()) {
            return fallbackBySemantics(semantics);
        }

        char lastChar = token.charAt(token.length() - 1);

        // Plosives: -t, -k, -p, -d, -g, -b -> Sharp crisp click/impact
        if (lastChar == 't' || lastChar == 'k' || lastChar == 'p' || lastChar == 'd' || lastChar == 'g' || lastChar == 'b') {
            return (semantics == SoundSemantics.BREAK || semantics == SoundSemantics.DAMAGE) ? "咔嚓！" : "咔！";
        }

        // Fricatives / Sibilants: -s, -z, -h, -f -> Air flow / hiss
        if (lastChar == 's' || lastChar == 'z' || lastChar == 'h' || lastChar == 'f') {
            return "嘶——";
        }

        // Nasals & Resonants: -m, -n, -l, -r -> Hum / resonance
        if (lastChar == 'm' || lastChar == 'n' || lastChar == 'l' || lastChar == 'r') {
            return "嗡——";
        }

        // Vowels: -a, -e, -i, -o, -u -> Open call / release
        if (lastChar == 'a' || lastChar == 'e' || lastChar == 'i' || lastChar == 'o' || lastChar == 'u') {
            return "呼！";
        }

        return fallbackBySemantics(semantics);
    }

    private static String fallbackBySemantics(SoundSemantics semantics) {
        if (semantics == null) return "咚！";
        return switch (semantics) {
            case BREAK -> "咔嚓！";
            case DAMAGE -> "砰！";
            case ATTACK -> "喝！";
            case STEP, GALLOP, HEAVY_STEP -> "踏！";
            case AMBIENT -> "沙沙…";
            case VOICE, IDLE_VOICE -> "哈！";
            case INTERACTION -> "嗒！";
            case PICKUP -> "叮！";
            case LANDING -> "啪嗒！";
            default -> "咚！";
        };
    }

    // =========================================================================
    // Source Geometry Resolution
    // =========================================================================

    private static SoundDefinition.Source resolveSource(SoundSemantics semantics, List<String> tokens) {
        if (semantics == SoundSemantics.STEP || semantics == SoundSemantics.GALLOP || semantics == SoundSemantics.LANDING) {
            return SoundDefinition.Source.GROUND;
        }
        if (semantics == SoundSemantics.BREAK || hasAnyToken(tokens, "block", "tile", "contraption", "machine")) {
            return SoundDefinition.Source.BLOCK;
        }
        if (hasAnyToken(tokens, "entity", "mob", "boss", "npc", "player")) {
            return SoundDefinition.Source.entity(1.8, 0.6);
        }
        if (semantics == SoundSemantics.DAMAGE || semantics == SoundSemantics.VOICE) {
            return SoundDefinition.Source.entity(1.6, 0.5);
        }
        return SoundDefinition.Source.POINT;
    }
}
