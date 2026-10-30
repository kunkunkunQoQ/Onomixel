package dev.kunkun.onomixel.render;

import java.util.List;

/** Screen-space costs use the complete caption, a padded reticle and a physical-pixel safe frame. */
public final class CaptionReadability {
    public record Viewport(float marginX, float marginY, BillboardProjection.Bounds reticle) {
        public static Viewport pixels(int width, int height, int guiScale) {
            float px = 2F / Math.max(1, width), py = 2F / Math.max(1, height);
            float unit = Math.max(1, guiScale);
            float rx = 12 * unit * px, ry = 12 * unit * py;
            return new Viewport(10 * unit * px, 10 * unit * py,
                    new BillboardProjection.Bounds(-rx, -ry, rx, ry));
        }
    }

    private CaptionReadability() {}

    public static double score(BillboardProjection.Bounds text, BillboardProjection.Bounds source,
                               List<BillboardProjection.Bounds> occupied, double displacementSquared) {
        return score(text, source == null ? List.of() : List.of(source), occupied,
                Viewport.pixels(1000, 1000, 3), displacementSquared);
    }

    public static double score(BillboardProjection.Bounds text, List<BillboardProjection.Bounds> silhouettes,
                               List<BillboardProjection.Bounds> occupied, Viewport viewport, double displacementSquared) {
        if (text == null || !text.intersectsScreen()) return Double.POSITIVE_INFINITY;
        double area = Math.max(0.000001, text.area());
        double model = 0, overlap = 0;
        for (var shape : silhouettes) model += text.overlap(shape) / area;
        for (var other : occupied) overlap += text.overlap(other) / area;
        double edge = edgeIntrusion(text, viewport);
        double reticle = text.overlap(viewport.reticle) / Math.max(0.000001, Math.min(area, viewport.reticle.area()));
        return Math.min(1, model) * 50 + Math.min(3, overlap) * 32 + edge * 100
                + reticle * 55 + displacementSquared * 0.28;
    }

    public static double edgeIntrusion(BillboardProjection.Bounds text, Viewport viewport) {
        if (text == null) return Double.POSITIVE_INFINITY;
        return Math.max(0, -1 + viewport.marginX - text.minX()) + Math.max(0, text.maxX() - 1 + viewport.marginX)
                + Math.max(0, -1 + viewport.marginY - text.minY()) + Math.max(0, text.maxY() - 1 + viewport.marginY);
    }

    public static boolean needsEscape(BillboardProjection.Bounds text, Viewport viewport) {
        return text == null || !text.intersectsScreen() || edgeIntrusion(text, viewport) > 0.005
                || text.overlap(viewport.reticle) > 0;
    }

    public static boolean fullyOnScreen(BillboardProjection.Bounds text) {
        return text != null && text.minX() >= -1 && text.maxX() <= 1 && text.minY() >= -1 && text.maxY() <= 1;
    }
}
