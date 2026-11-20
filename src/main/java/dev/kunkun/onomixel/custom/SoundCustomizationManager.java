package dev.kunkun.onomixel.custom;

import dev.kunkun.onomixel.sound.SoundDefinition;
import dev.kunkun.onomixel.sound.SoundDefinitions;
import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Publishes a coherent user, resource-pack and addon rule set to sound and render consumers. */
public final class SoundCustomizationManager {
    private static final System.Logger LOGGER = System.getLogger(SoundCustomizationManager.class.getName());
    private static final List<Runnable> RELOAD_LISTENERS = new CopyOnWriteArrayList<>();
    private static final List<CustomWordRule> apiRules = new ArrayList<>();
    private static List<CustomWordRule> packRules = List.of();
    private static List<CustomWordRule> userRules = List.of();

    private record Rules(List<CompiledWordOverrideIndex> tiers, Map<String, SoundDefinition> legacyDefinitions,
                         int activeCount, int totalCount, boolean hasRadar, boolean hasClusters) {}
    private static final Rules EMPTY = new Rules(List.of(), Map.of(), 0, 0, false, false);
    private static volatile Rules active = EMPTY;

    private SoundCustomizationManager() {}

    public static CustomWordRule resolve(String soundId) {
        var tiers = active.tiers();
        for (int i = 0; i < tiers.size(); i++) {
            CustomWordRule rule = tiers.get(i).find(soundId);
            if (rule != null) return rule;
        }
        return null;
    }

    public static SoundDefinition legacyPackDefinition(String soundId) {
        return soundId == null ? null : active.legacyDefinitions().get(soundId);
    }

    public static CustomWordRule getFirstActiveRule() {
        var tiers = active.tiers();
        return tiers.isEmpty() ? null : tiers.getFirst().getFirstRule();
    }

    public static String resolveDisplayText(String soundId, long seed) {
        return resolveDisplayText(resolve(soundId), soundId, seed);
    }

    public static String resolveDisplayText(CustomWordRule rule, String soundId, long seed) {
        if (rule == null || rule.display() == null) return null;
        return rule.display().resolveFormatted(seed, soundId);
    }

    public static synchronized void registerApiRule(CustomWordRule rule) {
        apiRules.add(Objects.requireNonNull(rule, "rule cannot be null"));
        publish(active.legacyDefinitions());
    }

    public static synchronized void registerApiRules(List<CustomWordRule> rules) {
        apiRules.addAll(Objects.requireNonNull(rules, "rules cannot be null"));
        publish(active.legacyDefinitions());
    }

    /** Replaces all rules; used for standalone imports and tests. */
    public static synchronized void applyRules(List<CustomWordRule> rules) {
        Objects.requireNonNull(rules, "rules cannot be null");
        apiRules.clear();
        apiRules.addAll(rules);
        packRules = List.of();
        userRules = List.of();
        publish(Map.of());
    }

    public static int applyJson(String jsonString) {
        List<CustomWordRule> parsed = CustomWordParser.parse(jsonString);
        applyRules(parsed);
        return parsed.size();
    }

    public static int reloadAll(Path localConfigFile, List<String> packJsons) {
        return reloadAll(localConfigFile, packJsons, Map.of());
    }

    /** Reads the new layers before replacing the previous snapshot. Addon registrations survive reloads. */
    public static synchronized int reloadAll(Path localConfigFile, List<String> packJsons,
                                            Map<String, SoundDefinition> legacyDefinitions) {
        List<CustomWordRule> newPackRules = new ArrayList<>();
        if (packJsons != null) {
            for (String json : packJsons) newPackRules.addAll(CustomWordParser.parse(json));
        }
        List<CustomWordRule> newUserRules = List.of();
        if (localConfigFile != null && Files.isRegularFile(localConfigFile)) {
            try {
                newUserRules = CustomWordParser.parse(Files.readString(localConfigFile));
            } catch (IOException e) {
                LOGGER.log(System.Logger.Level.WARNING, "Cannot read custom words from " + localConfigFile, e);
                newUserRules = userRules;
            }
        }
        packRules = newPackRules;
        userRules = newUserRules;
        publish(Map.copyOf(legacyDefinitions));
        return totalRuleCount();
    }

    private static void publish(Map<String, SoundDefinition> definitions) {
        List<CompiledWordOverrideIndex> tiers = new ArrayList<>(3);
        var exactIds = new HashSet<String>();
        int patternCount = 0;
        boolean hasRadar = false;
        boolean hasClusters = false;
        for (var rules : List.of(userRules, packRules, apiRules)) {
            if (rules.isEmpty()) continue;
            var index = new CompiledWordOverrideIndex(rules);
            if (index.isEmpty()) continue;
            tiers.add(index);
            hasRadar |= index.hasRadarRules();
            hasClusters |= index.hasClusterRules();
            for (var rule : rules) {
                if (rule == null) continue;
                if (rule.isExact()) exactIds.add(rule.exactId());
                else patternCount++;
            }
        }
        active = new Rules(List.copyOf(tiers), definitions, exactIds.size() + patternCount,
                userRules.size() + packRules.size() + apiRules.size(), hasRadar, hasClusters);
        invalidateDerivedCaches();
        for (Runnable listener : RELOAD_LISTENERS) {
            try {
                listener.run();
            } catch (Throwable ignored) {
                // Keep one addon listener from interrupting publication to the rest.
            }
        }
    }

    private static void invalidateDerivedCaches() {
        VisualTypeRegistry.invalidateResolutionCaches();
        SoundDefinitions.clearBuiltinCache();
    }

    public static void addReloadListener(Runnable listener) {
        if (listener != null) RELOAD_LISTENERS.add(listener);
    }

    public static void removeReloadListener(Runnable listener) {
        if (listener != null) RELOAD_LISTENERS.remove(listener);
    }

    public static boolean hasRadarOverrides() { return active.hasRadar(); }
    public static boolean hasClusterOverrides() { return active.hasClusters(); }
    public static int activeRuleCount() { return active.activeCount(); }
    public static int totalRuleCount() { return active.totalCount(); }

    public static synchronized void resetToDefaults() {
        apiRules.clear();
        userRules = List.of();
        packRules = List.of();
        active = EMPTY;
        invalidateDerivedCaches();
    }
}
