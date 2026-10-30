package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundSemantics;
import dev.kunkun.onomixel.sound.SoundDefinitions;

/**
 * Semantic motion envelopes and visual facades.
 * Delegates type resolution and sampling to {@link VisualTypeRegistry} and {@link VisualType}.
 */
public final class CaptionVisuals {
    public enum Kind { INTERACTION, BREAK, PICKUP, VOICE, IMPACT, AMBIENT }
    public record Effect(float scale, float tiltDegrees, double side, double rise, int rgb,
                         float stretchX, float stretchY) {
        public Effect(float scale, float tiltDegrees, double side, double rise, int rgb) {
            this(scale, tiltDegrees, side, rise, rgb, 1.0F, 1.0F);
        }

        public float scaleX() { return scale * stretchX; }
        public float scaleY() { return scale * stretchY; }

        /** Replaces malformed external animation values with a safe neutral effect and bounds extremes. */
        public Effect sanitized() {
            float safeScale = finiteClamp(scale, 1.0F, 0.05F, 4.0F);
            float safeTilt = finiteClamp(tiltDegrees, 0.0F, -90.0F, 90.0F);
            double safeSide = finiteClamp(side, 0.0, -4.0, 4.0);
            double safeRise = finiteClamp(rise, 0.0, -4.0, 4.0);
            float safeStretchX = finiteClamp(stretchX, 1.0F, 0.05F, 4.0F);
            float safeStretchY = finiteClamp(stretchY, 1.0F, 0.05F, 4.0F);
            return new Effect(safeScale, safeTilt, safeSide, safeRise, rgb, safeStretchX, safeStretchY);
        }

        private static float finiteClamp(float value, float fallback, float min, float max) {
            return Float.isFinite(value) ? Math.clamp(value, min, max) : fallback;
        }
        private static double finiteClamp(double value, double fallback, double min, double max) {
            return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback;
        }
    }

    private static volatile ColorStyle activeStyle = ColorStyle.VIBRANT;
    private static volatile DisplayMode activeDisplayMode = DisplayMode.LIGHTWEIGHT;
    private static volatile AestheticStyle activeAestheticStyle = AestheticStyle.SHONEN;
    private static volatile ColorblindMode activeColorblindMode = ColorblindMode.OFF;

    // Per-sound-type duration percentages (relative to base duration)
    private static volatile double stepDurationScale = 0.50;
    private static volatile double pickupDurationScale = 0.70;
    private static volatile double interactionDurationScale = 0.90;
    private static volatile double breakDurationScale = 1.00;
    private static volatile double impactDurationScale = 1.10;
    private static volatile double voiceDurationScale = 0.85;
    private static volatile double ambientDurationScale = 0.80;

    private CaptionVisuals() {}

    /** Cap angular size at close range, including first-person hand and nearby pickup effects. */
    public static double nearDistanceFactor(double distance) {
        return nearDistanceFactor(distance, false);
    }

    /** Exempts self-damage from angular downscaling, granting full combat impact. */
    public static double nearDistanceFactor(double distance, boolean selfHurt) {
        if (selfHurt) return 1.25;
        return Math.clamp(distance / 3.0, 0.0, 1.0);
    }

    /** Identifies player self-damage events at close range. */
    public static boolean isSelfHurt(String soundId, double distance) {
        if (soundId == null) return false;
        if (soundId.contains("player.hurt")) return true;
        return distance < 1.35 && (soundId.contains(".hurt") || soundId.contains(".fall"));
    }

    /** Resolves the semantic kind for a sound ID. */
    public static Kind kind(String soundId) {
        return VisualTypeRegistry.resolve(soundId).toKind();
    }

    /** Resolves the extensible {@link VisualType} for a sound ID. */
    public static VisualType type(String soundId) {
        return VisualTypeRegistry.resolve(soundId);
    }

    /** Gets the currently active color style palette. */
    public static ColorStyle activeStyle() {
        return activeStyle;
    }

    /** Sets the currently active color style palette. */
    public static void setActiveStyle(ColorStyle style) {
        if (style != null) {
            activeStyle = style;
        }
    }

    /** Gets the currently active display mode. */
    public static DisplayMode displayMode() {
        return activeDisplayMode;
    }

    /** Sets the currently active display mode. */
    public static void setDisplayMode(DisplayMode mode) {
        if (mode != null) {
            activeDisplayMode = mode;
        }
    }

    public static AestheticStyle activeAestheticStyle() { return activeAestheticStyle; }
    public static void setActiveAestheticStyle(AestheticStyle style) {
        if (style != null) activeAestheticStyle = style;
    }

    public static ColorblindMode activeColorblindMode() { return activeColorblindMode; }
    public static void setActiveColorblindMode(ColorblindMode mode) {
        if (mode != null) activeColorblindMode = mode;
    }

    /** Sets category duration scaling percentages. */
    public static void setDurationScales(double step, double pickup, double interaction,
                                         double breakScale, double impact, double voice, double ambient) {
        stepDurationScale = step;
        pickupDurationScale = pickup;
        interactionDurationScale = interaction;
        breakDurationScale = breakScale;
        impactDurationScale = impact;
        voiceDurationScale = voice;
        ambientDurationScale = ambient;
    }

    public static double stepDurationScale() { return stepDurationScale; }
    public static double pickupDurationScale() { return pickupDurationScale; }
    public static double interactionDurationScale() { return interactionDurationScale; }
    public static double breakDurationScale() { return breakDurationScale; }
    public static double impactDurationScale() { return impactDurationScale; }
    public static double voiceDurationScale() { return voiceDurationScale; }
    public static double ambientDurationScale() { return ambientDurationScale; }

    /**
     * Calculates display duration by applying the category-specific duration percentage
     * to the base configured duration.
     */
    public static long duration(String soundId, long configuredNanos) {
        return duration(SoundDefinitions.semantics(soundId), configuredNanos);
    }

    public static long duration(SoundSemantics semantics, long configuredNanos) {
        double scale = durationScale(semantics);
        long scaled = Math.round(configuredNanos * scale);
        return Math.clamp(scaled, 200_000_000L, 5_000_000_000L);
    }

    /**
     * Resolves the configured duration multiplier for a sound ID.
     */
    public static double durationScale(String soundId) {
        return durationScale(SoundDefinitions.semantics(soundId));
    }

    public static double durationScale(SoundSemantics semantics) {
        if (semantics == null) semantics = SoundSemantics.OTHER;
        return switch (semantics) {
            case STEP, GALLOP -> stepDurationScale;
            case PICKUP -> pickupDurationScale;
            case INTERACTION -> interactionDurationScale;
            case BREAK -> breakDurationScale;
            case HEAVY_STEP, LANDING, ATTACK, DAMAGE, OTHER -> impactDurationScale;
            case IDLE_VOICE, VOICE -> voiceDurationScale;
            case AMBIENT -> ambientDurationScale;
        };
    }

    /** Samples effect using the active configured color style. */
    public static Effect sample(Kind kind, double ageSeconds, double sinceTriggerSeconds, long seed) {
        return sample(kind, ageSeconds, sinceTriggerSeconds, seed, activeStyle);
    }

    /** Samples effect with an explicit color style. */
    public static Effect sample(Kind kind, double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
        VisualType visualType = VisualTypes.fromKind(kind);
        return safeSample(visualType, null, ageSeconds, sinceTriggerSeconds, seed,
                style != null ? style : activeStyle);
    }

    /** Samples effect using an extensible {@link VisualType} and the active configured color style. */
    public static Effect sample(VisualType type, double ageSeconds, double sinceTriggerSeconds, long seed) {
        return sample(type, ageSeconds, sinceTriggerSeconds, seed, activeStyle);
    }

    /** Samples effect using an extensible {@link VisualType} and an explicit color style. */
    public static Effect sample(VisualType type, double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
        if (type == null) type = VisualTypes.IMPACT;
        return safeSample(type, null, ageSeconds, sinceTriggerSeconds, seed,
                style != null ? style : activeStyle);
    }

    /** Samples effect using an extensible {@link VisualType}, sound ID, and active color style. */
    public static Effect sample(VisualType type, String soundId, double ageSeconds, double sinceTriggerSeconds, long seed) {
        return sample(type, soundId, ageSeconds, sinceTriggerSeconds, seed, activeStyle);
    }

    /** Samples effect using an extensible {@link VisualType}, sound ID, and an explicit color style. */
    public static Effect sample(VisualType type, String soundId, double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
        if (type == null) type = VisualTypes.IMPACT;
        return safeSample(type, soundId, ageSeconds, sinceTriggerSeconds, seed,
                style != null ? style : activeStyle);
    }

    /** Samples an effect using only the supplied palette, aesthetic, and accessibility settings. */
    public static Effect sample(VisualType type, String soundId, double ageSeconds, double sinceTriggerSeconds,
                                long seed, ColorStyle style, AestheticStyle aestheticStyle,
                                ColorblindMode colorblindMode) {
        if (type == null) type = VisualTypes.IMPACT;
        return sampleWithStyles(type, soundId, ageSeconds, sinceTriggerSeconds, seed,
                style != null ? style : ColorStyle.VIBRANT,
                aestheticStyle != null ? aestheticStyle : AestheticStyle.SHONEN,
                colorblindMode != null ? colorblindMode : ColorblindMode.OFF);
    }

    private static Effect safeSample(VisualType type, String soundId, double ageSeconds,
                                    double sinceTriggerSeconds, long seed, ColorStyle style) {
        return sampleWithStyles(type, soundId, ageSeconds, sinceTriggerSeconds, seed,
                style, activeAestheticStyle, activeColorblindMode);
    }

    private static Effect sampleWithStyles(VisualType type, String soundId, double ageSeconds,
                                           double sinceTriggerSeconds, long seed, ColorStyle style,
                                           AestheticStyle aestheticStyle, ColorblindMode colorblindMode) {
        Effect effect = null;
        try {
            effect = soundId == null
                    ? type.sample(ageSeconds, sinceTriggerSeconds, seed, style)
                    : type.sample(soundId, ageSeconds, sinceTriggerSeconds, seed, style);
        } catch (RuntimeException ignored) {
            // Broken third-party samplers are treated as neutral motion for this frame.
        }
        Effect bounded = type.bound(effect);
        if (aestheticStyle == AestheticStyle.SHONEN && colorblindMode == ColorblindMode.OFF) {
            return bounded;
        }
        Effect styled = applyAestheticStyle(bounded, aestheticStyle);
        int accessibleRgb = applyColorblindFilter(styled.rgb(), colorblindMode);
        return new Effect(styled.scale(), styled.tiltDegrees(), styled.side(), styled.rise(),
                accessibleRgb, styled.stretchX(), styled.stretchY());
    }

    public static Effect applyAestheticStyle(Effect effect, AestheticStyle style) {
        if (style == null || style == AestheticStyle.SHONEN) return effect;
        return switch (style) {
            case POP_ART -> new Effect(effect.scale() * 1.05F, effect.tiltDegrees() * 0.7F,
                    effect.side(), effect.rise(), effect.rgb(), effect.stretchX() * 1.08F, effect.stretchY());
            case MEDIEVAL -> {
                int r = (effect.rgb() >> 16) & 0xFF;
                int g = (effect.rgb() >> 8) & 0xFF;
                int b = effect.rgb() & 0xFF;
                int medievalR = Math.min(255, (int) (r * 0.95 + 40));
                int medievalG = Math.min(255, (int) (g * 0.85 + 20));
                int medievalB = Math.max(0, (int) (b * 0.70 - 15));
                int medievalRgb = (medievalR << 16) | (medievalG << 8) | medievalB;
                yield new Effect(effect.scale(), effect.tiltDegrees() - 5.0F,
                        effect.side(), effect.rise(), medievalRgb, effect.stretchX() * 0.95F, effect.stretchY() * 1.05F);
            }
            case CYBERPUNK -> {
                int r = (effect.rgb() >> 16) & 0xFF;
                int g = (effect.rgb() >> 8) & 0xFF;
                int b = effect.rgb() & 0xFF;
                int neonR = r > 128 ? 0xEC : 0x06;
                int neonG = g > 128 ? 0x48 : 0xB6;
                int neonB = b > 128 ? 0x99 : 0xD4;
                int neonRgb = (neonR << 16) | (neonG << 8) | neonB;
                yield new Effect(effect.scale() * 1.02F, effect.tiltDegrees() * 1.1F,
                        effect.side(), effect.rise(), neonRgb, effect.stretchX() * 1.10F, effect.stretchY() * 0.90F);
            }
            default -> effect;
        };
    }

    public static int applyColorblindFilter(int rgb, ColorblindMode mode) {
        if (mode == null || mode == ColorblindMode.OFF) return rgb;
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return switch (mode) {
            case PROTANOPIA -> {
                if (r > 160 && g < 120) {
                    yield (0xFD << 16) | (0xE0 << 8) | 0x47; // Bright yellow #FDE047
                }
                yield rgb;
            }
            case DEUTERANOPIA -> {
                if (g > 160 && r < 140) {
                    yield (0x0E << 16) | (0xA5 << 8) | 0xE9; // Vivid cyan #0EA5E9
                }
                yield rgb;
            }
            case TRITANOPIA -> {
                if (b > 160 && r < 120) {
                    yield (0xF4 << 16) | (0x3F << 8) | 0x5E; // Vivid rose #F43F5E
                }
                yield rgb;
            }
            default -> rgb;
        };
    }

    /** Formats altitude indicators (removed per user decision, returns clean text). */
    public static String formatAltitudeBadge(String text, double soundY, double camY) {
        return text != null ? text : "";
    }

    /** Formats text with brackets to convey acoustic occlusion behind walls. */
    public static String formatMuffled(String text) {
        if (text == null) return "";
        return "[ " + text + " ]";
    }

    /** Desaturates and lowers luminance to represent acoustic muffling through solid blocks. */
    public static int muffledColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int gray = (int) (0.299 * r + 0.587 * g + 0.114 * b);
        r = (int) ((r * 0.5 + gray * 0.5) * 0.60);
        g = (int) ((g * 0.5 + gray * 0.5) * 0.60);
        b = (int) ((b * 0.5 + gray * 0.5) * 0.60);
        return (r << 16) | (g << 8) | b;
    }
}
