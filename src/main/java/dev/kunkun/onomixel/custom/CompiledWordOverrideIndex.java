package dev.kunkun.onomixel.custom;

import java.util.*;

/**
 * Immutable compiled fast-path index for custom onomatopoeia rules.
 * Provides nanosecond O(1) exact lookups and prioritized pattern evaluation with zero allocation.
 * Zero Minecraft runtime dependencies for pure JVM testability.
 */
public final class CompiledWordOverrideIndex {

    public static final CompiledWordOverrideIndex EMPTY = new CompiledWordOverrideIndex(List.of());

    private final Map<String, CustomWordRule> exactMatches;
    private final List<CustomWordRule> patternMatches;

    public CompiledWordOverrideIndex(List<CustomWordRule> rules) {
        if (rules == null || rules.isEmpty()) {
            this.exactMatches = Map.of();
            this.patternMatches = List.of();
            return;
        }

        Map<String, CustomWordRule> exacts = new HashMap<>();
        List<CustomWordRule> patterns = new ArrayList<>();

        // Sort rules so that higher priority rules process first
        List<CustomWordRule> sorted = new ArrayList<>(rules.size());
        for (CustomWordRule r : rules) {
            if (r != null) sorted.add(r);
        }
        Collections.sort(sorted);

        for (CustomWordRule rule : sorted) {
            if (rule.isExact()) {
                // First rule for an exact soundId wins because sorted by priority desc
                exacts.putIfAbsent(rule.exactId(), rule);
            } else {
                patterns.add(rule);
            }
        }

        this.exactMatches = Map.copyOf(exacts);
        this.patternMatches = List.copyOf(patterns);
    }

    /**
     * Resolves the highest-priority matching customization rule for the specified sound event.
     *
     * @param soundId the sound event ID, e.g. "minecraft:entity.creeper.primed"
     * @return the matched CustomWordRule, or null if no customization was defined
     */
    public CustomWordRule find(String soundId) {
        if (soundId == null || (exactMatches.isEmpty() && patternMatches.isEmpty())) return null;

        // 1. Fast-path exact match: O(1) hash table lookup
        CustomWordRule exact = exactMatches.get(soundId);
        if (exact != null) {
            return exact;
        }

        // 2. Pattern match: prioritized linear evaluation
        for (int i = 0; i < patternMatches.size(); i++) {
            CustomWordRule rule = patternMatches.get(i);
            if (rule.matches(soundId)) {
                return rule;
            }
        }

        return null;
    }

    /**
     * Retrieves the first active custom rule (if any), preferring exact matches over pattern matches.
     * Useful for sandbox preview introspection.
     */
    public CustomWordRule getFirstRule() {
        if (!exactMatches.isEmpty()) {
            return exactMatches.values().iterator().next();
        }
        if (!patternMatches.isEmpty()) {
            return patternMatches.get(0);
        }
        return null;
    }

    /**
     * Total number of compiled customization rules in this index.
     */
    public int size() {
        return exactMatches.size() + patternMatches.size();
    }

    public boolean isEmpty() {
        return exactMatches.isEmpty() && patternMatches.isEmpty();
    }
}
