package dev.kunkun.onomixel.custom;

import dev.kunkun.onomixel.subtitle.VisualType;
import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Central manager for custom onomatopoeia overrides across all tiers:
 * Tier 3: Local user configs (.minecraft/config/onomixel/custom_words.json)
 * Tier 2: Resource packs (assets/&lt;namespace&gt;/onomixel/custom_words.json)
 * Tier 1: Modder API registrations
 * Tier 0: Builtin sound mappings & inference engine
 *
 * Implements an isolated multi-tier storage architecture to prevent rules from wiping each other out during reloads.
 */
public final class SoundCustomizationManager {
    private static final System.Logger LOGGER = System.getLogger(SoundCustomizationManager.class.getName());

    // Tier 1: Modder API registrations (persist across resource pack reloads)
    private static final List<CustomWordRule> TIER1_API_RULES = new CopyOnWriteArrayList<>();

    // Tier 2: Resource pack rules (reloaded on F3+T / resource reload)
    private static final List<CustomWordRule> TIER2_PACK_RULES = new CopyOnWriteArrayList<>();

    // Tier 3: Local user configuration rules (reloaded on F3+T, +1000 priority)
    private static final List<CustomWordRule> TIER3_USER_RULES = new CopyOnWriteArrayList<>();

    // Track sound IDs whose colors were overridden in VisualTypeRegistry so we can clean them up safely
    private static final java.util.Set<String> REGISTERED_COLOR_SOUNDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final AtomicReference<CompiledWordOverrideIndex> ACTIVE_INDEX =
            new AtomicReference<>(CompiledWordOverrideIndex.EMPTY);

    private SoundCustomizationManager() {}

    /**
     * Resolves customization rule for the given sound event ID.
     */
    public static CustomWordRule resolve(String soundId) {
        if (soundId == null) return null;
        return ACTIVE_INDEX.get().find(soundId);
    }

    /**
     * Retrieves the first active customization rule, useful for sandbox previewing.
     */
    public static CustomWordRule getFirstActiveRule() {
        return ACTIVE_INDEX.get().getFirstRule();
    }

    /**
     * Resolves the customized display string for the given sound event.
     * Returns a string prefixed with "literal:" if it is raw text, or a translation key, or null if no rule matches.
     * Zero-allocation guarantee: returns pre-cached formatted string directly.
     */
    public static String resolveDisplayText(String soundId, long seed) {
        CustomWordRule rule = resolve(soundId);
        return resolveDisplayText(rule, soundId, seed);
    }

    /**
     * Resolves the customized display string using an already-resolved rule.
     * Avoids duplicate index lookups and provides zero-allocation formatting.
     */
    public static String resolveDisplayText(CustomWordRule rule, String soundId, long seed) {
        if (rule == null || rule.display() == null) {
            return null;
        }
        return rule.display().resolveFormatted(seed, soundId);
    }

    /**
     * Registers a rule from third-party Modder Java API (Tier 1).
     * These rules persist cleanly across resource pack reloads.
     */
    public static void registerApiRule(CustomWordRule rule) {
        Objects.requireNonNull(rule, "rule cannot be null");
        TIER1_API_RULES.add(rule);
        recompile();
    }

    /**
     * Registers a batch of rules from third-party Modder Java API (Tier 1).
     */
    public static void registerApiRules(List<CustomWordRule> rules) {
        Objects.requireNonNull(rules, "rules cannot be null");
        TIER1_API_RULES.addAll(rules);
        recompile();
    }

    /**
     * Applies a list of rules directly (used primarily for test environments).
     */
    public static void applyRules(List<CustomWordRule> rules) {
        Objects.requireNonNull(rules, "rules cannot be null");
        TIER1_API_RULES.clear();
        TIER2_PACK_RULES.clear();
        TIER3_USER_RULES.clear();
        TIER1_API_RULES.addAll(rules);
        recompile();
    }

    /**
     * Parses and applies rules from a single JSON string.
     */
    public static int applyJson(String jsonString) {
        List<CustomWordRule> parsed = CustomWordParser.parse(jsonString);
        applyRules(parsed);
        return parsed.size();
    }

    /**
     * Reloads and merges rules from both resource pack JSON strings and local configuration files.
     * Note: Mod API rules (Tier 1) are safely preserved!
     * Local user configuration rules (Tier 3) are automatically given elevated priority (+1000) over resource packs.
     *
     * @param localConfigFile path to .minecraft/config/onomixel/custom_words.json (may be null or non-existent)
     * @param resourcePackJsons list of JSON contents gathered from active resource packs
     * @return total count of rules active across all tiers
     */
    public static int reloadAll(Path localConfigFile, List<String> resourcePackJsons) {
        // 1. Refresh Tier 2 (Resource Packs)
        List<CustomWordRule> newPackRules = new ArrayList<>();
        if (resourcePackJsons != null) {
            for (String packJson : resourcePackJsons) {
                if (packJson != null && !packJson.isBlank()) {
                    newPackRules.addAll(CustomWordParser.parse(packJson));
                }
            }
        }
        TIER2_PACK_RULES.clear();
        TIER2_PACK_RULES.addAll(newPackRules);

        // 2. Refresh Tier 3 (Local User Config)
        List<CustomWordRule> newUserRules = new ArrayList<>();
        if (localConfigFile != null && Files.isRegularFile(localConfigFile)) {
            try {
                String localJson = Files.readString(localConfigFile);
                List<CustomWordRule> parsed = CustomWordParser.parse(localJson);
                for (CustomWordRule r : parsed) {
                    newUserRules.add(new CustomWordRule(
                            r.exactId(), r.pattern(), r.patternString(), r.display(),
                            r.visualTypeId(), r.kineticMotion(), r.vibrantColor(), r.pastelColor(),
                            r.bold(), r.italic(), r.scaleMultiplier(), r.durationMultiplier(),
                            r.heightOffset(), r.priority() + 1000
                    ));
                }
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Failed to read local onomatopoeia config from " + localConfigFile + ": " + e.getMessage());
            }
        }
        TIER3_USER_RULES.clear();
        TIER3_USER_RULES.addAll(newUserRules);

        recompile();
        return TIER1_API_RULES.size() + TIER2_PACK_RULES.size() + TIER3_USER_RULES.size();
    }

    /**
     * Atomically recompiles all rules from Tier 1, Tier 2, and Tier 3 into the active index.
     */
    private static synchronized void recompile() {
        List<CustomWordRule> aggregated = new ArrayList<>(
                TIER1_API_RULES.size() + TIER2_PACK_RULES.size() + TIER3_USER_RULES.size()
        );
        aggregated.addAll(TIER1_API_RULES);
        aggregated.addAll(TIER2_PACK_RULES);
        aggregated.addAll(TIER3_USER_RULES);

        CompiledWordOverrideIndex compiled = new CompiledWordOverrideIndex(aggregated);
        ACTIVE_INDEX.set(compiled);

        // Clean up previously registered custom colors from VisualTypeRegistry
        for (String prevId : REGISTERED_COLOR_SOUNDS) {
            VisualTypeRegistry.unregisterSoundColor(prevId);
        }
        REGISTERED_COLOR_SOUNDS.clear();

        // Synchronize visual colors and styles into VisualTypeRegistry
        for (CustomWordRule rule : aggregated) {
            if (rule.isExact() && (rule.vibrantColor() != null || rule.pastelColor() != null)) {
                int vibrant = rule.vibrantColor() != null ? rule.vibrantColor() : 0xFFFFFF;
                int pastel = rule.pastelColor() != null ? rule.pastelColor() : vibrant;
                VisualTypeRegistry.registerSoundColor(rule.exactId(), vibrant, pastel);
                REGISTERED_COLOR_SOUNDS.add(rule.exactId());
            }
            if (rule.isExact() && rule.visualTypeId() != null) {
                VisualType vt = VisualTypeRegistry.getType(rule.visualTypeId());
                if (vt != null) {
                    VisualTypeRegistry.registerSound(rule.exactId(), vt);
                }
            }
        }

        LOGGER.log(System.Logger.Level.INFO, "Onomixel: Recompiled {0} custom onomatopoeia rules (Tier1 API: {1}, Tier2 Pack: {2}, Tier3 User: {3}).",
                compiled.size(), TIER1_API_RULES.size(), TIER2_PACK_RULES.size(), TIER3_USER_RULES.size());
    }

    /**
     * Resets customization index and all tiers back to empty.
     */
    public static void resetToDefaults() {
        TIER1_API_RULES.clear();
        TIER2_PACK_RULES.clear();
        TIER3_USER_RULES.clear();
        for (String prevId : REGISTERED_COLOR_SOUNDS) {
            VisualTypeRegistry.unregisterSoundColor(prevId);
        }
        REGISTERED_COLOR_SOUNDS.clear();
        ACTIVE_INDEX.set(CompiledWordOverrideIndex.EMPTY);
    }

    public static int activeRuleCount() {
        return ACTIVE_INDEX.get().size();
    }

    public static int totalRuleCount() {
        return TIER1_API_RULES.size() + TIER2_PACK_RULES.size() + TIER3_USER_RULES.size();
    }
}
