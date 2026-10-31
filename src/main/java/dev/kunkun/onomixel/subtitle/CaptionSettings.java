package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundSemantics;

/** One immutable configuration snapshot shared by admission and frame extraction. */
public record CaptionSettings(boolean enabled, DisplayMode mode, ColorStyle style, int maxDistance,
                              int maxVisible, long durationNanos, double textScale, double opacity,
                              boolean distanceScaling, boolean fade, Durations durations,
                              boolean spatialWords, boolean threatRadar, boolean altitudeBadges,
                              boolean kineticTypography, boolean speedOfSound,
                              AestheticStyle aestheticStyle, ColorblindMode colorblindMode) {

    /**
     * 17-parameter backward-compatible constructor (defaults spatialWords = true).
     */
    public CaptionSettings(boolean enabled, DisplayMode mode, ColorStyle style, int maxDistance,
                           int maxVisible, long durationNanos, double textScale, double opacity,
                           boolean distanceScaling, boolean fade, Durations durations,
                           boolean threatRadar, boolean altitudeBadges, boolean kineticTypography,
                           boolean speedOfSound, AestheticStyle aestheticStyle, ColorblindMode colorblindMode) {
        this(enabled, mode, style, maxDistance, maxVisible, durationNanos, textScale, opacity,
                distanceScaling, fade, durations, true, threatRadar, altitudeBadges, kineticTypography,
                speedOfSound, aestheticStyle, colorblindMode);
    }

    /**
     * 11-parameter backward-compatible constructor (for tests and quick instantiation).
     */
    public CaptionSettings(boolean enabled, DisplayMode mode, ColorStyle style, int maxDistance,
                           int maxVisible, long durationNanos, double textScale, double opacity,
                           boolean distanceScaling, boolean fade, Durations durations) {
        this(enabled, mode, style, maxDistance, maxVisible, durationNanos, textScale, opacity,
                distanceScaling, fade, durations, true, true, true, true, true, AestheticStyle.SHONEN, ColorblindMode.OFF);
    }

    public record Durations(double step, double pickup, double interaction, double breaking,
                            double impact, double voice, double ambient) {
        public double scale(SoundSemantics semantics) {
            return switch (semantics) {
                case STEP -> step;
                case PICKUP -> pickup;
                case INTERACTION -> interaction;
                case BREAK -> breaking;
                case IDLE_VOICE, VOICE -> voice;
                case AMBIENT -> ambient;
                default -> impact;
            };
        }
    }

    public static CaptionSettings defaults() {
        return new CaptionSettings(true, DisplayMode.LIGHTWEIGHT, ColorStyle.VIBRANT, 24, 12,
                1_000_000_000L, 1, 0.9, true, true,
                new Durations(0.5, 0.7, 0.9, 1, 1.1, 0.85, 0.8),
                true, true, true, true, true, AestheticStyle.SHONEN, ColorblindMode.OFF);
    }

    public long duration(SoundSemantics semantics) {
        return Math.clamp(Math.round(durationNanos * durations.scale(semantics)), 200_000_000L, 5_000_000_000L);
    }
}
