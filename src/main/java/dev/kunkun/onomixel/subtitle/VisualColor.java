package dev.kunkun.onomixel.subtitle;

/**
 * Immutable pair of vibrant comic RGB and soft pastel RGB colors.
 */
public record VisualColor(int vibrant, int pastel) {
    public int resolve(ColorStyle style) {
        return style == ColorStyle.PASTEL ? pastel : vibrant;
    }
}
