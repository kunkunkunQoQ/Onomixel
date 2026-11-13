package dev.kunkun.onomixel;

import dev.kunkun.onomixel.subtitle.ColorStyle;
import dev.kunkun.onomixel.subtitle.DisplayMode;
import dev.kunkun.onomixel.subtitle.CaptionSettings;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Client-local settings; the built-in NeoForge screen edits these without a restart. */
public final class OnomixelConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue ENABLED = BUILDER
            .translation("onomixel.config.enabled").define("enabled", CaptionSettings.defaults().enabled());
    public static final ModConfigSpec.EnumValue<DisplayMode> DISPLAY_MODE = BUILDER
            .translation("onomixel.config.displayMode").defineEnum("displayMode", DisplayMode.LIGHTWEIGHT);
    public static final ModConfigSpec.EnumValue<ColorStyle> COLOR_STYLE = BUILDER
            .translation("onomixel.config.colorStyle").defineEnum("colorStyle", ColorStyle.VIBRANT);
    public static final ModConfigSpec.IntValue MAX_DISTANCE = BUILDER
            .translation("onomixel.config.maxDistance").defineInRange("maxDistance", 24, 4, 64);
    public static final ModConfigSpec.IntValue MAX_VISIBLE = BUILDER
            .translation("onomixel.config.maxVisible").defineInRange("maxVisible", 12, 1, 50);
    public static final ModConfigSpec.DoubleValue DURATION_SECONDS = BUILDER
            .translation("onomixel.config.durationSeconds").defineInRange("durationSeconds", 1.0, 0.5, 5.0);
    public static final ModConfigSpec.DoubleValue TEXT_SCALE = BUILDER
            .translation("onomixel.config.textScale").defineInRange("textScale", 1.0, 0.5, 2.0);
    public static final ModConfigSpec.DoubleValue OPACITY = BUILDER
            .translation("onomixel.config.opacity").defineInRange("opacity", 0.9, 0.1, 1.0);
    public static final ModConfigSpec.BooleanValue DISTANCE_SCALING = BUILDER
            .translation("onomixel.config.distanceScaling").define("distanceScaling", true);
    public static final ModConfigSpec.BooleanValue FADE = BUILDER
            .translation("onomixel.config.fade").define("fade", true);

    // Advanced Tactical, Manga Dynamics, and Universal Immersion
    public static final ModConfigSpec.BooleanValue SPATIAL_WORDS = BUILDER
            .translation("onomixel.config.spatialWords").define("spatialWords", CaptionSettings.defaults().spatialWords());
    public static final ModConfigSpec.BooleanValue THREAT_RADAR = BUILDER
            .translation("onomixel.config.threatRadar").define("threatRadar", CaptionSettings.defaults().threatRadar());
    /**
     * @deprecated Per DEC-07, prefix altitude badges have been superseded by 3D world projection
     *             and 2D radar altitude arrows. Preserved solely for backward compatibility.
     */
    @Deprecated
    public static final ModConfigSpec.BooleanValue ALTITUDE_BADGES = BUILDER
            .translation("onomixel.config.altitudeBadges").define("altitudeBadges", false);
    public static final ModConfigSpec.BooleanValue KINETIC_TYPOGRAPHY = BUILDER
            .translation("onomixel.config.kineticTypography").define("kineticTypography", true);
    public static final ModConfigSpec.BooleanValue SPEED_OF_SOUND = BUILDER
            .translation("onomixel.config.speedOfSound").define("speedOfSound", true);
    public static final ModConfigSpec.EnumValue<dev.kunkun.onomixel.subtitle.AestheticStyle> AESTHETIC_STYLE = BUILDER
            .translation("onomixel.config.aestheticStyle")
            .defineEnum("aestheticStyle", dev.kunkun.onomixel.subtitle.AestheticStyle.SHONEN);
    public static final ModConfigSpec.EnumValue<dev.kunkun.onomixel.subtitle.ColorblindMode> COLORBLIND_MODE = BUILDER
            .translation("onomixel.config.colorblindMode")
            .defineEnum("colorblindMode", dev.kunkun.onomixel.subtitle.ColorblindMode.OFF);

    // Per-sound-type duration percentages (relative to base durationSeconds)
    public static final ModConfigSpec.DoubleValue STEP_DURATION_SCALE = BUILDER
            .translation("onomixel.config.stepDurationScale").defineInRange("stepDurationScale", 0.50, 0.20, 2.0);
    public static final ModConfigSpec.DoubleValue PICKUP_DURATION_SCALE = BUILDER
            .translation("onomixel.config.pickupDurationScale").defineInRange("pickupDurationScale", 0.70, 0.20, 2.0);
    public static final ModConfigSpec.DoubleValue INTERACTION_DURATION_SCALE = BUILDER
            .translation("onomixel.config.interactionDurationScale").defineInRange("interactionDurationScale", 0.90, 0.20, 2.0);
    public static final ModConfigSpec.DoubleValue BREAK_DURATION_SCALE = BUILDER
            .translation("onomixel.config.breakDurationScale").defineInRange("breakDurationScale", 1.00, 0.20, 2.0);
    public static final ModConfigSpec.DoubleValue IMPACT_DURATION_SCALE = BUILDER
            .translation("onomixel.config.impactDurationScale").defineInRange("impactDurationScale", 1.10, 0.20, 2.0);
    public static final ModConfigSpec.DoubleValue VOICE_DURATION_SCALE = BUILDER
            .translation("onomixel.config.voiceDurationScale").defineInRange("voiceDurationScale", 0.85, 0.20, 2.0);
    public static final ModConfigSpec.DoubleValue AMBIENT_DURATION_SCALE = BUILDER
            .translation("onomixel.config.ambientDurationScale").defineInRange("ambientDurationScale", 0.80, 0.20, 2.0);

    public static final ModConfigSpec SPEC = BUILDER.build();
    private static volatile CaptionSettings cachedSnapshot;

    public static CaptionSettings snapshot() {
        if (!SPEC.isLoaded()) return CaptionSettings.defaults();
        boolean enabled = ENABLED.get();
        DisplayMode mode = DISPLAY_MODE.get();
        ColorStyle style = COLOR_STYLE.get();
        int maxDist = MAX_DISTANCE.get();
        int maxVis = MAX_VISIBLE.get();
        long durNanos = Math.round(DURATION_SECONDS.get() * 1_000_000_000L);
        double scale = TEXT_SCALE.get();
        double opacity = OPACITY.get();
        boolean distScale = DISTANCE_SCALING.get();
        boolean fade = FADE.get();
        double step = STEP_DURATION_SCALE.get();
        double pickup = PICKUP_DURATION_SCALE.get();
        double interaction = INTERACTION_DURATION_SCALE.get();
        double brk = BREAK_DURATION_SCALE.get();
        double impact = IMPACT_DURATION_SCALE.get();
        double voice = VOICE_DURATION_SCALE.get();
        double ambient = AMBIENT_DURATION_SCALE.get();
        boolean spatialWords = SPATIAL_WORDS.get();
        boolean threatRadar = THREAT_RADAR.get();
        boolean altBadges = ALTITUDE_BADGES.get();
        boolean kinetic = KINETIC_TYPOGRAPHY.get();
        boolean speedOfSound = SPEED_OF_SOUND.get();
        var aesthetic = AESTHETIC_STYLE.get();
        var colorblind = COLORBLIND_MODE.get();

        CaptionSettings prev = cachedSnapshot;
        if (prev != null
                && prev.enabled() == enabled
                && prev.mode() == mode
                && prev.style() == style
                && prev.maxDistance() == maxDist
                && prev.maxVisible() == maxVis
                && prev.durationNanos() == durNanos
                && Double.compare(prev.textScale(), scale) == 0
                && Double.compare(prev.opacity(), opacity) == 0
                && prev.distanceScaling() == distScale
                && prev.fade() == fade
                && prev.spatialWords() == spatialWords
                && prev.threatRadar() == threatRadar
                && prev.altitudeBadges() == altBadges
                && prev.kineticTypography() == kinetic
                && prev.speedOfSound() == speedOfSound
                && prev.aestheticStyle() == aesthetic
                && prev.colorblindMode() == colorblind
                && Double.compare(prev.durations().step(), step) == 0
                && Double.compare(prev.durations().pickup(), pickup) == 0
                && Double.compare(prev.durations().interaction(), interaction) == 0
                && Double.compare(prev.durations().breaking(), brk) == 0
                && Double.compare(prev.durations().impact(), impact) == 0
                && Double.compare(prev.durations().voice(), voice) == 0
                && Double.compare(prev.durations().ambient(), ambient) == 0) {
            return prev;
        }

        CaptionSettings next = new CaptionSettings(enabled, mode, style, maxDist, maxVis, durNanos,
                scale, opacity, distScale, fade,
                new CaptionSettings.Durations(step, pickup, interaction, brk, impact, voice, ambient),
                spatialWords, threatRadar, altBadges, kinetic, speedOfSound, aesthetic, colorblind);
        cachedSnapshot = next;
        return next;
    }

    public static boolean spatialWords() {
        if (SPEC != null && SPEC.isLoaded()) {
            return SPATIAL_WORDS.get();
        }
        return CaptionSettings.defaults().spatialWords();
    }

    public static boolean threatRadar() {
        if (SPEC != null && SPEC.isLoaded()) {
            return THREAT_RADAR.get();
        }
        return CaptionSettings.defaults().threatRadar();
    }

    public static ColorStyle colorStyle() {
        if (SPEC != null && SPEC.isLoaded()) {
            return COLOR_STYLE.get();
        }
        return ColorStyle.VIBRANT;
    }

    public static DisplayMode displayMode() {
        if (SPEC != null && SPEC.isLoaded()) {
            return DISPLAY_MODE.get();
        }
        return DisplayMode.LIGHTWEIGHT;
    }

    public static double stepDurationScale() {
        if (SPEC != null && SPEC.isLoaded()) return STEP_DURATION_SCALE.get();
        return 0.50;
    }

    public static double pickupDurationScale() {
        if (SPEC != null && SPEC.isLoaded()) return PICKUP_DURATION_SCALE.get();
        return 0.70;
    }

    public static double interactionDurationScale() {
        if (SPEC != null && SPEC.isLoaded()) return INTERACTION_DURATION_SCALE.get();
        return 0.90;
    }

    public static double breakDurationScale() {
        if (SPEC != null && SPEC.isLoaded()) return BREAK_DURATION_SCALE.get();
        return 1.00;
    }

    public static double impactDurationScale() {
        if (SPEC != null && SPEC.isLoaded()) return IMPACT_DURATION_SCALE.get();
        return 1.10;
    }

    public static double voiceDurationScale() {
        if (SPEC != null && SPEC.isLoaded()) return VOICE_DURATION_SCALE.get();
        return 0.85;
    }

    public static double ambientDurationScale() {
        if (SPEC != null && SPEC.isLoaded()) return AMBIENT_DURATION_SCALE.get();
        return 0.80;
    }

    private OnomixelConfig() {}
}
