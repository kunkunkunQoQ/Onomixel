package dev.kunkun.onomixel.custom;

import java.util.regex.Pattern;

/**
 * Immutable declaration of a customized onomatopoeia rule.
 * Can match sound events exactly or via compiled wildcard/regex patterns.
 * Zero Minecraft runtime dependencies for pure JVM testability.
 */
public record CustomWordRule(
        String exactId,
        Pattern pattern,
        String patternString,
        DisplayStrategy display,
        String visualTypeId,
        String kineticMotion,
        Integer vibrantColor,
        Integer pastelColor,
        Boolean bold,
        Boolean italic,
        Float scaleMultiplier,
        Float durationMultiplier,
        Double heightOffset,
        int priority
) implements Comparable<CustomWordRule> {

    public CustomWordRule {
        if (exactId == null && pattern == null) {
            throw new IllegalArgumentException("CustomWordRule must define either an exact soundId or a regex pattern");
        }
    }

    public boolean isExact() {
        return exactId != null;
    }

    public boolean matches(String soundId) {
        if (soundId == null) return false;
        if (exactId != null) {
            return exactId.equals(soundId);
        }
        return pattern != null && pattern.matcher(soundId).matches();
    }

    @Override
    public int compareTo(CustomWordRule other) {
        // Higher priority sorts first
        int p = Integer.compare(other.priority, this.priority);
        if (p != 0) return p;
        // Exact matches sort before pattern matches
        if (this.isExact() && !other.isExact()) return -1;
        if (!this.isExact() && other.isExact()) return 1;
        return 0;
    }
}
