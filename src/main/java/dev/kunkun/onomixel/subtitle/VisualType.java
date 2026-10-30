package dev.kunkun.onomixel.subtitle;

/**
 * Encapsulates the visual identity, physics motion envelope, typography weight,
 * and palette colors for a category of onomatopoeia sound words.
 * <p>
 * Third-party mods can create and register custom VisualTypes via {@link VisualTypeRegistry}.
 */
public interface VisualType {
    record MotionEnvelope(float maxScaleX, float maxScaleY, float maxTiltDegrees,
                          double maxSide, double maxRise) {
        public MotionEnvelope {
            if (!Float.isFinite(maxScaleX) || maxScaleX < 1.0F || maxScaleX > 8.0F
                    || !Float.isFinite(maxScaleY) || maxScaleY < 1.0F || maxScaleY > 8.0F
                    || !Float.isFinite(maxTiltDegrees) || maxTiltDegrees < 0.0F || maxTiltDegrees > 45.0F
                    || !Double.isFinite(maxSide) || maxSide < 0.0 || maxSide > 2.0
                    || !Double.isFinite(maxRise) || maxRise < 0.0 || maxRise > 2.0) {
                throw new IllegalArgumentException("Motion envelope values are outside supported bounds");
            }
        }
    }

    MotionEnvelope DEFAULT_MOTION_ENVELOPE = new MotionEnvelope(2.0F, 2.0F, 30.0F, 0.5, 0.6);

    /** Unique identifier for this visual type, e.g. "onomixel:interaction" or "mymod:magic". */
    String id();

    /** Whether this visual type uses bold typography for heavy sound emphasis. */
    boolean isBold();

    /** Whether this visual type uses italic typography, e.g. for whispering rain atmosphere. */
    default boolean isItalic() { return false; }

    /** RGB color for high-contrast vibrant comic styling. */
    int vibrantColor();

    /** RGB color for soft pastel vanilla styling. */
    int pastelColor();

    /** Resolves the RGB color according to the active user configuration. */
    default int color(ColorStyle style) {
        return style == ColorStyle.PASTEL ? pastelColor() : vibrantColor();
    }

    /** Resolves material-aware RGB color according to sound ID and active configuration. */
    default int color(String soundId, ColorStyle style) {
        VisualColor c = VisualTypeRegistry.resolveColor(soundId, this);
        return c.resolve(style);
    }

    /** Samples the scale, tilt, lateral displacement, vertical rise, and semantic color. */
    CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style);

    /** Maximum geometry reserved for this animation throughout its supported lifetime. */
    default MotionEnvelope motionEnvelope() { return DEFAULT_MOTION_ENVELOPE; }

    /** Sanitizes and constrains a sampled effect to the geometry this type declared. */
    default CaptionVisuals.Effect bound(CaptionVisuals.Effect effect) {
        MotionEnvelope envelope;
        try {
            envelope = motionEnvelope();
        } catch (RuntimeException ignored) {
            envelope = DEFAULT_MOTION_ENVELOPE;
        }
        if (envelope == null) envelope = DEFAULT_MOTION_ENVELOPE;
        int fallbackRgb;
        try {
            fallbackRgb = vibrantColor();
        } catch (RuntimeException ignored) {
            fallbackRgb = 0xFFFFFF;
        }
        CaptionVisuals.Effect safe = (effect == null
                ? new CaptionVisuals.Effect(1.0F, 0.0F, 0.0, 0.0, fallbackRgb)
                : effect).sanitized();
        float scale = Math.clamp(safe.scale(), 0.1F, Math.min(envelope.maxScaleX(), envelope.maxScaleY()));
        float scaleX = Math.clamp(safe.scaleX(), 0.1F, Math.min(envelope.maxScaleX(), scale * 4.0F));
        float scaleY = Math.clamp(safe.scaleY(), 0.1F, Math.min(envelope.maxScaleY(), scale * 4.0F));
        float stretchX = Math.clamp(scaleX / scale, 0.05F, 4.0F);
        float stretchY = Math.clamp(scaleY / scale, 0.05F, 4.0F);
        return new CaptionVisuals.Effect(scale,
                Math.clamp(safe.tiltDegrees(), -envelope.maxTiltDegrees(), envelope.maxTiltDegrees()),
                Math.clamp(safe.side(), -envelope.maxSide(), envelope.maxSide()),
                Math.clamp(safe.rise(), -envelope.maxRise(), envelope.maxRise()),
                safe.rgb(), stretchX, stretchY).sanitized();
    }

    /** Samples effect with material-aware semantic color for a specific sound ID. */
    default CaptionVisuals.Effect sample(String soundId, double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
        CaptionVisuals.Effect base;
        try {
            base = sample(ageSeconds, sinceTriggerSeconds, seed, style);
        } catch (RuntimeException ignored) {
            base = null;
        }
        base = bound(base);
        int resolvedRgb = base.rgb();
        try {
            resolvedRgb = color(soundId, style);
        } catch (RuntimeException ignored) {
            // Keep the safe base color if a third-party color provider fails.
        }
        return bound(new CaptionVisuals.Effect(base.scale(), base.tiltDegrees(), base.side(), base.rise(), resolvedRgb,
                base.stretchX(), base.stretchY()));
    }

    /** Ambient styling hint. Admission and throttling are controlled by SoundDefinition.semantics. */
    default boolean isAmbient() {
        return false;
    }

    /** Readability slot bias penalty or bonus. Return negative for bonus, positive for penalty. */
    default double slotScoreBonus(int slot) {
        return 0.0;
    }

    /** Maps to core Kind enum if applicable, or IMPACT as fallback. */
    default CaptionVisuals.Kind toKind() {
        return CaptionVisuals.Kind.IMPACT;
    }

    @FunctionalInterface
    interface MotionSampler {
        record Motion(float scale, float tiltDegrees, double side, double rise, float stretchX, float stretchY) {
            public Motion(float scale, float tiltDegrees, double side, double rise) {
                this(scale, tiltDegrees, side, rise, 1.0F, 1.0F);
            }
        }

        Motion sample(double ageSeconds, double sinceTriggerSeconds, long seed);
    }

    static Builder builder(String id) {
        return new Builder(id);
    }

    class Builder {
        private final String id;
        private boolean bold = false;
        private boolean italic = false;
        private int vibrantColor = 0xFFFFFF;
        private int pastelColor = 0xFFFFFF;
        private boolean ambient = false;
        private double slotBonus = 0.0;
        private int preferredSlot = -1;
        private CaptionVisuals.Kind kind = CaptionVisuals.Kind.IMPACT;
        private MotionSampler motionSampler;
        private MotionEnvelope motionEnvelope = DEFAULT_MOTION_ENVELOPE;

        public Builder(String id) {
            this.id = id;
        }

        public Builder bold(boolean bold) {
            this.bold = bold;
            return this;
        }

        public Builder italic(boolean italic) {
            this.italic = italic;
            return this;
        }

        public Builder colors(int vibrantColor, int pastelColor) {
            this.vibrantColor = vibrantColor;
            this.pastelColor = pastelColor;
            return this;
        }

        public Builder ambient(boolean ambient) {
            this.ambient = ambient;
            return this;
        }

        public Builder kind(CaptionVisuals.Kind kind) {
            this.kind = kind;
            return this;
        }

        public Builder slotPreference(int slot, double bonus) {
            this.preferredSlot = slot;
            this.slotBonus = bonus;
            return this;
        }

        public Builder motion(MotionSampler motionSampler) {
            this.motionSampler = motionSampler;
            return this;
        }

        public Builder motionEnvelope(MotionEnvelope motionEnvelope) {
            if (motionEnvelope == null) throw new IllegalArgumentException("motionEnvelope cannot be null");
            this.motionEnvelope = motionEnvelope;
            return this;
        }

        public VisualType build() {
            final String finalId = this.id;
            final boolean finalBold = this.bold;
            final boolean finalItalic = this.italic;
            final int finalVibrant = this.vibrantColor;
            final int finalPastel = this.pastelColor;
            final boolean finalAmbient = this.ambient;
            final CaptionVisuals.Kind finalKind = this.kind;
            final int finalSlot = this.preferredSlot;
            final double finalBonus = this.slotBonus;
            final MotionEnvelope envelope = this.motionEnvelope;
            final MotionSampler sampler = this.motionSampler != null ? this.motionSampler : (age, trigger, seed) ->
                    new MotionSampler.Motion(1.0F, 0.0F, 0.0, 0.0);

            return new VisualType() {
                @Override public String id() { return finalId; }
                @Override public boolean isBold() { return finalBold; }
                @Override public boolean isItalic() { return finalItalic; }
                @Override public int vibrantColor() { return finalVibrant; }
                @Override public int pastelColor() { return finalPastel; }
                @Override public boolean isAmbient() { return finalAmbient; }
                @Override public CaptionVisuals.Kind toKind() { return finalKind; }
                @Override public MotionEnvelope motionEnvelope() { return envelope; }

                @Override
                public double slotScoreBonus(int slot) {
                    if (finalSlot >= 0 && slot == finalSlot) return finalBonus;
                    return 0.0;
                }

                @Override
                public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
                    MotionSampler.Motion m;
                    try {
                        m = sampler.sample(ageSeconds, sinceTriggerSeconds, seed);
                    } catch (RuntimeException ignored) {
                        m = null;
                    }
                    if (m == null) m = new MotionSampler.Motion(1.0F, 0.0F, 0.0, 0.0);
                    return bound(new CaptionVisuals.Effect(m.scale(), m.tiltDegrees(), m.side(),
                            m.rise(), color(style), m.stretchX(), m.stretchY()));
                }
            };
        }

    }
}
