package dev.kunkun.onomixel.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-character kinetic layout engine: sine waves, explosive shockwave tapering, and white-noise jitter.
 * Zero Minecraft runtime dependencies for fast unit testing.
 */
public final class KineticTypography {
    public enum Mode { NONE, WAVE, EXPLOSIVE_TAPER, JITTER }

    public record Glyph(
            char character,
            float localX,
            float offsetX,
            float offsetY,
            float scaleX,
            float scaleY,
            float tiltDeg,
            int colorArgb
    ) {}

    /** A code point and its measured font advance in visual order. */
    public record GlyphInput(int codePoint, float advance) {}

    /** Per-code-point transform positioned around the centered caption origin. */
    public record AnimatedGlyph(int codePoint, float centerX, float advance,
                                float offsetX, float offsetY, float scaleX, float scaleY,
                                float tiltDeg, int colorArgb) {}

    /** Immutable per-frame glyph transforms and conservative centered bounds. */
    public record Layout(List<AnimatedGlyph> glyphs, float halfWidth, float halfHeight) {
        public Layout {
            glyphs = List.copyOf(glyphs);
        }
    }

    public static Mode resolveMode(String visualTypeId, String soundId) {
        if (visualTypeId == null) visualTypeId = "";
        if (soundId == null) soundId = "";

        if (visualTypeId.equals("onomixel:crit")
                || visualTypeId.equals("onomixel:damage_blunt")
                || visualTypeId.equals("onomixel:warden_sonic")
                || soundId.contains("explode")
                || soundId.contains("tnt.primed")) {
            return Mode.EXPLOSIVE_TAPER;
        }

        if (soundId.contains("creeper.primed") || soundId.contains("freeze")) {
            return Mode.JITTER;
        }

        if (visualTypeId.equals("onomixel:ambient")
                || visualTypeId.equals("onomixel:drift")
                || soundId.contains("water")
                || soundId.contains("whisper")
                || soundId.contains("sculk")) {
            return Mode.WAVE;
        }

        return Mode.NONE;
    }

    /**
     * Lays out measured code points without assuming equal character widths. Inputs must be in the same
     * visual order used by the font renderer; advances come from the active font and preserve style widths.
     */
    public static Layout layoutGlyphs(List<GlyphInput> inputs, int textHash, Mode mode,
                                     double ageSeconds, float lineHeight, int baseColorArgb) {
        if (inputs == null || inputs.isEmpty() || mode == null || mode == Mode.NONE) {
            return new Layout(List.of(), 0.0F, 0.0F);
        }

        float totalWidth = 0.0F;
        for (GlyphInput input : inputs) {
            if (input != null && Float.isFinite(input.advance()) && input.advance() > 0.0F) {
                totalWidth += input.advance();
            }
        }
        float cursor = -totalWidth / 2.0F;
        float safeHalfHeight = Float.isFinite(lineHeight) ? Math.max(0.0F, lineHeight) / 2.0F : 0.0F;
        double safeAge = Double.isFinite(ageSeconds) ? ageSeconds : 0.0;
        int jitterStep = (int) Math.floor(safeAge * 24.0);
        List<AnimatedGlyph> result = new ArrayList<>(inputs.size());
        float reservedHalfWidth = 0.0F;
        float reservedHalfHeight = 0.0F;

        for (int i = 0; i < inputs.size(); i++) {
            GlyphInput input = inputs.get(i);
            if (input == null) continue;
            float advance = Float.isFinite(input.advance()) ? Math.max(0.0F, input.advance()) : 0.0F;
            float centerX = cursor + advance / 2.0F;
            cursor += advance;
            float offsetX = 0.0F;
            float offsetY = 0.0F;
            float scaleX = 1.0F;
            float scaleY = 1.0F;
            float tiltDeg = 0.0F;

            switch (mode) {
                case WAVE -> {
                    double phase = 5.5 * safeAge - i * 0.60;
                    offsetY = (float) (2.2 * Math.sin(phase));
                    tiltDeg = (float) (4.0 * Math.cos(phase));
                }
                case EXPLOSIVE_TAPER -> {
                    float factor = (float) (1.0 + 0.85 * Math.exp(-0.40 * i));
                    scaleX = factor;
                    scaleY = factor;
                    if (i == 0) tiltDeg = -15.0F;
                }
                case JITTER -> {
                    int hashX = ((textHash ^ (i * 31) ^ (jitterStep * 17)) & 0x7FFF);
                    int hashY = (((textHash + 77) ^ (i * 37) ^ (jitterStep * 23)) & 0x7FFF);
                    offsetX = ((hashX % 7) - 3) * 0.45F;
                    offsetY = ((hashY % 7) - 3) * 0.45F;
                    tiltDeg = ((hashX % 9) - 4) * 1.5F;
                }
                case NONE -> { }
            }

            result.add(new AnimatedGlyph(input.codePoint(), centerX, advance, offsetX, offsetY,
                    scaleX, scaleY, tiltDeg, baseColorArgb));

            double halfW = advance / 2.0 * Math.abs(scaleX);
            double halfH = safeHalfHeight * Math.abs(scaleY);
            double radians = Math.toRadians(tiltDeg);
            double cos = Math.abs(Math.cos(radians));
            double sin = Math.abs(Math.sin(radians));
            double rotatedHalfW = halfW * cos + halfH * sin;
            double rotatedHalfH = halfH * cos + halfW * sin;
            reservedHalfWidth = Math.max(reservedHalfWidth,
                    (float) (Math.abs(centerX + offsetX) + rotatedHalfW));
            reservedHalfHeight = Math.max(reservedHalfHeight,
                    (float) (Math.abs(offsetY) + rotatedHalfH));
        }

        return new Layout(result, reservedHalfWidth, reservedHalfHeight);
    }

    public static List<Glyph> layout(String text, Mode mode, double ageSeconds, int baseColorArgb, float charWidth) {
        if (text == null || text.isEmpty()) return List.of();
        List<Glyph> glyphs = new ArrayList<>(text.length());

        float totalWidth = text.length() * charWidth;
        float startX = -totalWidth / 2.0f;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            float localX = startX + i * charWidth + charWidth / 2.0f;
            float offsetX = 0.0f;
            float offsetY = 0.0f;
            float scaleX = 1.0f;
            float scaleY = 1.0f;
            float tiltDeg = 0.0f;
            int color = baseColorArgb;

            switch (mode) {
                case WAVE -> {
                    offsetY = (float) (2.2 * Math.sin(5.5 * ageSeconds - i * 0.60));
                    tiltDeg = (float) (4.0 * Math.cos(5.5 * ageSeconds - i * 0.60));
                }
                case EXPLOSIVE_TAPER -> {
                    float factor = (float) (1.0 + 0.85 * Math.exp(-0.40 * i));
                    scaleX = factor;
                    scaleY = factor;
                    if (i == 0) {
                        tiltDeg = -15.0f;
                    }
                }
                case JITTER -> {
                    int step = (int) Math.floor(ageSeconds * 24.0);
                    int hashX = ((text.hashCode() ^ (i * 31) ^ (step * 17)) & 0x7FFF);
                    int hashY = (((text.hashCode() + 77) ^ (i * 37) ^ (step * 23)) & 0x7FFF);
                    offsetX = ((hashX % 7) - 3) * 0.45f;
                    offsetY = ((hashY % 7) - 3) * 0.45f;
                    tiltDeg = ((hashX % 9) - 4) * 1.5f;
                }
                case NONE -> {
                    // Standard identity
                }
            }

            glyphs.add(new Glyph(c, localX, offsetX, offsetY, scaleX, scaleY, tiltDeg, color));
        }

        return glyphs;
    }
}
