package dev.kunkun.onomixel.custom;

import dev.kunkun.onomixel.subtitle.ThreatRegistry;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable declaration of a customized onomatopoeia rule.
 * Can match sound events exactly or via compiled wildcard/regex patterns,
 * optionally overriding radar classification and cluster grouping behavior.
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
        int priority,
        ThreatRegistry.ThreatLevel radarLevel,
        Long radarDurationNanos,
        String radarLabel,
        Boolean clusterable,
        String customClusterGroup
) implements Comparable<CustomWordRule> {

    public CustomWordRule {
        if (exactId == null && pattern == null) {
            throw new IllegalArgumentException("CustomWordRule must define either an exact soundId or a regex pattern");
        }
        if (radarDurationNanos != null && radarDurationNanos < 0L) {
            throw new IllegalArgumentException("radarDurationNanos cannot be negative: " + radarDurationNanos);
        }
        if (customClusterGroup != null) {
            customClusterGroup = customClusterGroup.trim();
            if (customClusterGroup.isEmpty()) {
                customClusterGroup = null;
            }
        }
    }

    /** Backwards-compatible 14-argument constructor without radar or clustering overrides. */
    public CustomWordRule(
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
    ) {
        this(exactId, pattern, patternString, display, visualTypeId, kineticMotion,
                vibrantColor, pastelColor, bold, italic, scaleMultiplier, durationMultiplier,
                heightOffset, priority, null, null, null, null, null);
    }

    public boolean isExact() {
        return exactId != null;
    }

    public boolean hasRadarOverride() {
        return radarLevel != null;
    }

    public boolean hasClusterOverride() {
        return clusterable != null || customClusterGroup != null;
    }

    public CustomWordRule withPriority(int newPriority) {
        return new CustomWordRule(
                exactId, pattern, patternString, display, visualTypeId, kineticMotion,
                vibrantColor, pastelColor, bold, italic, scaleMultiplier, durationMultiplier,
                heightOffset, newPriority, radarLevel, radarDurationNanos, radarLabel,
                clusterable, customClusterGroup
        );
    }

    public static Builder exact(String soundId) {
        Objects.requireNonNull(soundId, "soundId cannot be null");
        return new Builder(soundId.trim(), null, null);
    }

    public static Builder regex(Pattern pattern) {
        Objects.requireNonNull(pattern, "pattern cannot be null");
        return new Builder(null, pattern, pattern.pattern());
    }

    public static final class Builder {
        private final String exactId;
        private final Pattern pattern;
        private final String patternString;
        private DisplayStrategy display;
        private String visualTypeId;
        private String kineticMotion;
        private Integer vibrantColor;
        private Integer pastelColor;
        private Boolean bold;
        private Boolean italic;
        private Float scaleMultiplier;
        private Float durationMultiplier;
        private Double heightOffset;
        private int priority = 150;
        private ThreatRegistry.ThreatLevel radarLevel;
        private Long radarDurationNanos;
        private String radarLabel;
        private Boolean clusterable;
        private String customClusterGroup;

        private Builder(String exactId, Pattern pattern, String patternString) {
            this.exactId = exactId;
            this.pattern = pattern;
            this.patternString = patternString;
        }

        public Builder display(DisplayStrategy display) {
            this.display = display;
            return this;
        }

        public Builder singleText(String text) {
            this.display = new DisplayStrategy.SingleText(text);
            return this;
        }

        public Builder translatable(String key) {
            this.display = new DisplayStrategy.Translatable(key);
            return this;
        }

        public Builder visualTypeId(String visualTypeId) {
            this.visualTypeId = visualTypeId;
            return this;
        }

        public Builder kineticMotion(String kineticMotion) {
            this.kineticMotion = kineticMotion;
            return this;
        }

        public Builder colors(Integer vibrantColor, Integer pastelColor) {
            this.vibrantColor = vibrantColor;
            this.pastelColor = pastelColor;
            return this;
        }

        public Builder bold(Boolean bold) {
            this.bold = bold;
            return this;
        }

        public Builder italic(Boolean italic) {
            this.italic = italic;
            return this;
        }

        public Builder scaleMultiplier(Float scaleMultiplier) {
            this.scaleMultiplier = scaleMultiplier;
            return this;
        }

        public Builder durationMultiplier(Float durationMultiplier) {
            this.durationMultiplier = durationMultiplier;
            return this;
        }

        public Builder heightOffset(Double heightOffset) {
            this.heightOffset = heightOffset;
            return this;
        }

        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder radar(ThreatRegistry.ThreatLevel level, long durationMillis, String label) {
            this.radarLevel = level;
            this.radarDurationNanos = durationMillis > 0L ? durationMillis * 1_000_000L : null;
            this.radarLabel = label;
            return this;
        }

        public Builder clusterable(Boolean clusterable) {
            this.clusterable = clusterable;
            return this;
        }

        public Builder clusterGroup(String clusterGroup) {
            this.customClusterGroup = clusterGroup;
            return this;
        }

        public CustomWordRule build() {
            return new CustomWordRule(
                    exactId, pattern, patternString, display, visualTypeId, kineticMotion,
                    vibrantColor, pastelColor, bold, italic, scaleMultiplier, durationMultiplier,
                    heightOffset, priority, radarLevel, radarDurationNanos, radarLabel,
                    clusterable, customClusterGroup
            );
        }
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
