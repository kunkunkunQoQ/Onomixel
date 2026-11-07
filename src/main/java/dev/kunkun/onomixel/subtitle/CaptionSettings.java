package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundSemantics;

/**
 * One immutable configuration snapshot shared by admission and frame extraction.
 *
 * @param altitudeBadges deprecated per DEC-07, prefix altitude badges have been superseded by 3D world projection
 *                       and 2D radar altitude arrows. Preserved solely for backward compatibility.
 */
public record CaptionSettings(boolean enabled, DisplayMode mode, ColorStyle style, int maxDistance,
                              int maxVisible, long durationNanos, double textScale, double opacity,
                              boolean distanceScaling, boolean fade, Durations durations,
                              boolean spatialWords, boolean threatRadar, @Deprecated boolean altitudeBadges,
                              boolean kineticTypography, boolean speedOfSound,
                              AestheticStyle aestheticStyle, ColorblindMode colorblindMode) {

    /**
     * 17-parameter backward-compatible constructor (defaults spatialWords = true).
     */
    public CaptionSettings(boolean enabled, DisplayMode mode, ColorStyle style, int maxDistance,
                           int maxVisible, long durationNanos, double textScale, double opacity,
                           boolean distanceScaling, boolean fade, Durations durations,
                           boolean threatRadar, @Deprecated boolean altitudeBadges, boolean kineticTypography,
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
                case STEP, GALLOP -> step;
                case PICKUP -> pickup;
                case INTERACTION -> interaction;
                case BREAK -> breaking;
                case IDLE_VOICE, VOICE -> voice;
                case AMBIENT -> ambient;
                default -> impact;
            };
        }
    }

    private static final Durations DEFAULT_DURATIONS = new Durations(0.5, 0.7, 0.9, 1, 1.1, 0.85, 0.8);
    private static final CaptionSettings DEFAULTS = new CaptionSettings(
            true, DisplayMode.LIGHTWEIGHT, ColorStyle.VIBRANT, 24, 12,
            1_000_000_000L, 1, 0.9, true, true, DEFAULT_DURATIONS,
            true, true, true, true, true, AestheticStyle.SHONEN, ColorblindMode.OFF
    );

    public static CaptionSettings defaults() {
        return DEFAULTS;
    }

    public long duration(SoundSemantics semantics) {
        return Math.clamp(Math.round(durationNanos * durations.scale(semantics)), 200_000_000L, 5_000_000_000L);
    }
}
