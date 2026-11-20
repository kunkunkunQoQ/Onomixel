package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.sound.SoundSemantics;
import dev.kunkun.onomixel.subtitle.CaptionVisuals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Persona 5 / Super Smash Bros inspired 2-frame Manga Hitstop & Radial Comic Starburst Spikes.
 * <p>
 * 1. 2-Frame Manga Hitstop (~35ms):
 *    - For critical strikes, blunt damage, and explosions:
 *    - Freezes vertical rise (rise = 0) and applies extreme physical squash & stretch
 *      (stretchX *= 1.35F, stretchY *= 0.75F) during the first 35ms.
 *    - Snaps back to normal motion curves after 35ms.
 * 2. Radial Comic Starburst Spikes (100ms):
 *    - Radiates 4~6 golden yellow / black speedline spikes outward from the text center.
 *    - Expands radially and fades out over the first 100ms.
 */
public final class MangaHitstopEffect {

    public static final long HITSTOP_NANOS = 35_000_000L; // 35ms (~2 frames)
    public static final long STARBURST_NANOS = 100_000_000L; // 100ms
    public static final float HITSTOP_STRETCH_X = 1.35F;
    public static final float HITSTOP_STRETCH_Y = 0.75F;
    public static final int DEFAULT_SPIKE_COUNT = 6;
    static final float SPIKE_OUTLINE_SIDE = 0.6F;
    static final float SPIKE_OUTLINE_TIP = 1.2F;

    public static final int STARBURST_GOLD_COLOR = 0xFFE040; // Vibrant comic golden yellow
    public static final int STARBURST_BLACK_COLOR = 0x191B24; // Comic ink outline black

    private MangaHitstopEffect() {}

    /**
     * Radial starburst spike geometry definition.
     */
    public record StarburstSpike(
            float angleDegrees,
            float innerRadius,
            float outerRadius,
            float thickness
    ) {
        public StarburstSpike {
            if (!Float.isFinite(angleDegrees) || !Float.isFinite(innerRadius)
                    || !Float.isFinite(outerRadius) || !Float.isFinite(thickness)) {
                throw new IllegalArgumentException("StarburstSpike angles and radii must be finite: angle="
                        + angleDegrees + ", inner=" + innerRadius + ", outer=" + outerRadius + ", thickness=" + thickness);
            }
        }
    }

    /**
     * Checks if a sound event qualifies for hitstop and starburst effects.
     */
    public static boolean isEligible(String soundId, SoundSemantics semantics) {
        if (soundId == null || soundId.isEmpty()) {
            return semantics == SoundSemantics.DAMAGE;
        }

        // Custom word kinetic motion override check
        CustomWordRule custom = SoundCustomizationManager.resolve(soundId);
        if (custom != null && custom.kineticMotion() != null) {
            if ("HITSTOP".equalsIgnoreCase(custom.kineticMotion())) return true;
            if ("NONE".equalsIgnoreCase(custom.kineticMotion()) || "STATIC".equalsIgnoreCase(custom.kineticMotion())) return false;
        }

        if (semantics == SoundSemantics.DAMAGE) {
            return true;
        }

        return soundId.contains(".crit")
                || soundId.contains("critical")
                || soundId.contains("damage")
                || soundId.contains("explode")
                || soundId.contains("explosion")
                || soundId.contains("tnt")
                || soundId.contains("creeper")
                || soundId.contains("mace.smash")
                || soundId.contains("mace.heavy")
                || soundId.contains("knockback")
                || soundId.contains("attack.strong")
                || soundId.contains("sweep");
    }

    /**
     * Checks whether hitstop is actively freezing motion.
     */
    public static boolean isHitstopActive(long elapsedNanos) {
        return elapsedNanos >= 0 && elapsedNanos <= HITSTOP_NANOS;
    }

    /**
     * Evaluates the starburst alpha (1.0 down to 0.0 over 100ms).
     */
    public static float calculateStarburstAlpha(long elapsedNanos) {
        if (elapsedNanos < 0 || elapsedNanos > STARBURST_NANOS) {
            return 0.0F;
        }
        double progress = (double) elapsedNanos / (double) STARBURST_NANOS;
        return (float) Math.clamp(1.0 - progress, 0.0, 1.0);
    }

    /**
     * Applies hitstop squish & stretch and anchors vertical rise to 0.
     */
    public static CaptionVisuals.Effect applyHitstopEffect(CaptionVisuals.Effect effect, boolean hitstopActive) {
        if (!hitstopActive || effect == null) {
            return effect;
        }
        return new CaptionVisuals.Effect(
                effect.scale(),
                effect.tiltDegrees(),
                effect.side(),
                0.0, // rise frozen at 0 (absolute anchor)
                effect.rgb(),
                effect.stretchX() * HITSTOP_STRETCH_X,
                effect.stretchY() * HITSTOP_STRETCH_Y
        );
    }

    static float baseRadius(float textHalfWidth, float textHalfHeight) {
        return Math.max(textHalfWidth, textHalfHeight) + 2.0F;
    }

    static int spikeOpacity(float alpha) {
        return (int) Math.clamp(alpha * 220.0F, 0.0F, 255.0F);
    }

    private static float spikeLength(float alpha) {
        return 12.0F + 16.0F * (1 - Math.clamp(alpha, 0.0F, 1.0F));
    }

    private static float spikeThickness(float alpha) {
        return 2.8F * (0.5F + 0.5F * Math.clamp(alpha, 0.0F, 1.0F));
    }

    /** Encloses the outer black corners after any spike rotation, in unscaled text units. */
    static float visibleBurstRadius(float alpha, float baseRadius) {
        if (spikeOpacity(alpha) < 4) return 0;
        return (float) Math.hypot(baseRadius + spikeLength(alpha) + SPIKE_OUTLINE_TIP,
                spikeThickness(alpha) * 0.5F + SPIKE_OUTLINE_SIDE);
    }

    /** Generates radial starburst speedline spikes. */
    public static List<StarburstSpike> generateSpikes(int count, float starburstAlpha, float baseRadius) {
        if (count <= 0 || !Float.isFinite(starburstAlpha) || !Float.isFinite(baseRadius) || starburstAlpha <= 0.01F) {
            return List.of();
        }
        int clampedCount = Math.min(count, 32);
        float length = spikeLength(starburstAlpha);
        float thickness = spikeThickness(starburstAlpha);
        float safeBaseRadius = Math.max(0.0F, baseRadius);

        List<StarburstSpike> list = new ArrayList<>(clampedCount);
        float step = 360.0F / clampedCount;
        for (int i = 0; i < clampedCount; i++) {
            float angle = i * step + 15.0F; // 15 deg offset for comic diagonal energy
            list.add(new StarburstSpike(angle, safeBaseRadius, safeBaseRadius + length, thickness));
        }
        return Collections.unmodifiableList(list);
    }
}
