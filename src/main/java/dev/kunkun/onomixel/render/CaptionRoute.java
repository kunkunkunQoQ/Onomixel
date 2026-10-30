package dev.kunkun.onomixel.render;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Finds a short sequence of projected caption slots that avoids screen-space obstacles. */
public final class CaptionRoute {
    private CaptionRoute() {}

    /**
     * Returns the first slot on a clear route from {@code current} to {@code targetSlot}.
     * Obstacles are expanded by a conservative caption half-size before testing segment intersections.
     * If no route exists, the requested target is returned so callers always retain a bounded fallback.
     */
    public static int nextSlot(BillboardProjection.Bounds current,
                               List<BillboardProjection.Bounds> slots,
                               int targetSlot,
                               List<BillboardProjection.Bounds> obstacles) {
        if (slots == null || slots.isEmpty()) return -1;
        int fallback = Math.max(0, Math.min(targetSlot, slots.size() - 1));
        if (current == null || targetSlot < 0 || targetSlot >= slots.size() || slots.get(targetSlot) == null) {
            return fallback;
        }
        BillboardProjection.Bounds target = slots.get(targetSlot);
        List<BillboardProjection.Bounds> validSlots = new ArrayList<>();
        List<Integer> originalIndices = new ArrayList<>();
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) != null) {
                validSlots.add(slots.get(i));
                originalIndices.add(i);
            }
        }
        int goal = originalIndices.indexOf(targetSlot) + 1;
        int nodeCount = validSlots.size() + 1;
        double[] xs = new double[nodeCount];
        double[] ys = new double[nodeCount];
        xs[0] = centerX(current);
        ys[0] = centerY(current);
        double halfWidth = width(current) / 2;
        double halfHeight = height(current) / 2;
        for (int i = 0; i < validSlots.size(); i++) {
            BillboardProjection.Bounds slot = validSlots.get(i);
            xs[i + 1] = centerX(slot);
            ys[i + 1] = centerY(slot);
            halfWidth = Math.max(halfWidth, width(slot) / 2);
            halfHeight = Math.max(halfHeight, height(slot) / 2);
        }

        List<BillboardProjection.Bounds> expanded = new ArrayList<>();
        if (obstacles != null) {
            for (BillboardProjection.Bounds obstacle : obstacles) {
                if (obstacle != null && finite(obstacle)) {
                    expanded.add(new BillboardProjection.Bounds(
                            (float) (obstacle.minX() - halfWidth), (float) (obstacle.minY() - halfHeight),
                            (float) (obstacle.maxX() + halfWidth), (float) (obstacle.maxY() + halfHeight)));
                }
            }
        }
        boolean[] insideObstacle = new boolean[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            for (BillboardProjection.Bounds obstacle : expanded) {
                if (obstacle.contains((float) xs[node], (float) ys[node])) {
                    insideObstacle[node] = true;
                    break;
                }
            }
        }
        if (clear(xs[0], ys[0], xs[goal], ys[goal], expanded,
                insideObstacle[0], insideObstacle[goal])) return targetSlot;

        double epsilon = Math.max(0.015, Math.min(width(current), height(current)) * 0.25);
        double[] distances = new double[nodeCount];
        int[] firstHop = new int[nodeCount];
        boolean[] visited = new boolean[nodeCount];
        Arrays.fill(distances, Double.POSITIVE_INFINITY);
        Arrays.fill(firstHop, -1);
        distances[0] = 0;
        for (int iteration = 0; iteration < nodeCount; iteration++) {
            int from = -1;
            for (int i = 0; i < nodeCount; i++) {
                if (!visited[i] && (from < 0 || distances[i] < distances[from])) from = i;
            }
            if (from < 0 || !Double.isFinite(distances[from])) break;
            if (from == goal) break;
            visited[from] = true;
            for (int to = 1; to < nodeCount; to++) {
                if (to == from || visited[to]) continue;
                // A waypoint embedded in a model/reticle/caption obstacle cannot serve as a tunnel.
                // Only the real current and requested final target may overlap an obstacle.
                if (to != goal && insideObstacle[to]) continue;
                if (from != 0 && from != goal && insideObstacle[from]) continue;
                double dx = xs[to] - xs[from], dy = ys[to] - ys[from];
                double length = Math.hypot(dx, dy);
                // Do not select an effectively stationary waypoint: layout calls this every frame.
                if (from == 0 && to != goal && length <= epsilon) continue;
                if (!clear(xs[from], ys[from], xs[to], ys[to], expanded,
                        from == 0 && insideObstacle[0], to == goal && insideObstacle[goal])) continue;
                double next = distances[from] + length;
                if (next < distances[to]) {
                    distances[to] = next;
                    firstHop[to] = from == 0 ? to : firstHop[from];
                }
            }
        }
        int hop = firstHop[goal];
        return hop > 0 ? originalIndices.get(hop - 1) : fallback;
    }

    private static boolean clear(double x0, double y0, double x1, double y1,
                                 List<BillboardProjection.Bounds> obstacles) {
        return clear(x0, y0, x1, y1, obstacles, false, false);
    }

    private static boolean clear(double x0, double y0, double x1, double y1,
                                 List<BillboardProjection.Bounds> obstacles,
                                 boolean allowStartInside, boolean allowEndInside) {
        for (BillboardProjection.Bounds obstacle : obstacles) {
            boolean startsInside = obstacle.contains((float) x0, (float) y0);
            boolean endsInside = obstacle.contains((float) x1, (float) y1);
            if (segmentIntersects(x0, y0, x1, y1, obstacle)
                    && !(startsInside && allowStartInside) && !(endsInside && allowEndInside)) return false;
        }
        return true;
    }

    private static boolean segmentIntersects(double x0, double y0, double x1, double y1,
                                             BillboardProjection.Bounds box) {
        double[] interval = {0, 1};
        return clip(- (x1 - x0), x0 - box.minX(), interval)
                && clip(x1 - x0, box.maxX() - x0, interval)
                && clip(- (y1 - y0), y0 - box.minY(), interval)
                && clip(y1 - y0, box.maxY() - y0, interval)
                && interval[0] <= interval[1];
    }

    private static boolean clip(double p, double q, double[] interval) {
        if (Math.abs(p) < 1.0e-12) return q >= 0;
        double t = q / p;
        if (p < 0) {
            if (t > interval[1]) return false;
            interval[0] = Math.max(interval[0], t);
        } else {
            if (t < interval[0]) return false;
            interval[1] = Math.min(interval[1], t);
        }
        return true;
    }

    private static boolean finite(BillboardProjection.Bounds box) {
        return Float.isFinite(box.minX()) && Float.isFinite(box.minY())
                && Float.isFinite(box.maxX()) && Float.isFinite(box.maxY());
    }
    private static double centerX(BillboardProjection.Bounds box) { return ((double) box.minX() + box.maxX()) / 2; }
    private static double centerY(BillboardProjection.Bounds box) { return ((double) box.minY() + box.maxY()) / 2; }
    private static double width(BillboardProjection.Bounds box) { return Math.max(0, (double) box.maxX() - box.minX()); }
    private static double height(BillboardProjection.Bounds box) { return Math.max(0, (double) box.maxY() - box.minY()); }
}
