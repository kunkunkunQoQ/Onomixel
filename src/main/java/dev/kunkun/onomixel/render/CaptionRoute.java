package dev.kunkun.onomixel.render;

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
        int slotListSize = slots.size();
        int fallback = Math.max(0, Math.min(targetSlot, slotListSize - 1));
        if (current == null || targetSlot < 0 || targetSlot >= slotListSize || slots.get(targetSlot) == null) {
            return fallback;
        }
        int obsCount = 0;
        if (obstacles != null) {
            for (int i = 0, n = obstacles.size(); i < n; i++) {
                BillboardProjection.Bounds obstacle = obstacles.get(i);
                if (obstacle != null && finite(obstacle)) {
                    obsCount++;
                }
            }
        }
        if (obsCount == 0) {
            return targetSlot;
        }

        int validCount = 0;
        for (int i = 0; i < slotListSize; i++) {
            if (slots.get(i) != null) {
                validCount++;
            }
        }
        int nodeCount = validCount + 1;
        double[] xs = new double[nodeCount];
        double[] ys = new double[nodeCount];
        int[] originalIndices = new int[validCount];
        xs[0] = centerX(current);
        ys[0] = centerY(current);
        double halfWidth = width(current) / 2;
        double halfHeight = height(current) / 2;
        int goal = -1;
        int writeIdx = 0;
        for (int i = 0; i < slotListSize; i++) {
            BillboardProjection.Bounds slot = slots.get(i);
            if (slot != null) {
                int node = writeIdx + 1;
                xs[node] = centerX(slot);
                ys[node] = centerY(slot);
                originalIndices[writeIdx] = i;
                if (i == targetSlot) {
                    goal = node;
                }
                halfWidth = Math.max(halfWidth, width(slot) / 2);
                halfHeight = Math.max(halfHeight, height(slot) / 2);
                writeIdx++;
            }
        }

        float[] expanded = new float[obsCount * 4];
        int expIdx = 0;
        for (int i = 0, n = obstacles.size(); i < n; i++) {
            BillboardProjection.Bounds obstacle = obstacles.get(i);
            if (obstacle != null && finite(obstacle)) {
                expanded[expIdx] = (float) (obstacle.minX() - halfWidth);
                expanded[expIdx + 1] = (float) (obstacle.minY() - halfHeight);
                expanded[expIdx + 2] = (float) (obstacle.maxX() + halfWidth);
                expanded[expIdx + 3] = (float) (obstacle.maxY() + halfHeight);
                expIdx += 4;
            }
        }

        boolean[] insideObstacle = new boolean[nodeCount];
        for (int node = 0; node < nodeCount; node++) {
            float px = (float) xs[node];
            float py = (float) ys[node];
            for (int base = 0; base < expanded.length; base += 4) {
                if (containsBox(expanded, base, px, py)) {
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
        return hop > 0 ? originalIndices[hop - 1] : fallback;
    }

    private static boolean containsBox(float[] boxes, int base, float x, float y) {
        return x >= boxes[base] && x <= boxes[base + 2] && y >= boxes[base + 1] && y <= boxes[base + 3];
    }

    private static boolean clear(double x0, double y0, double x1, double y1,
                                 float[] expandedObstacles,
                                 boolean allowStartInside, boolean allowEndInside) {
        float fx0 = (float) x0;
        float fy0 = (float) y0;
        float fx1 = (float) x1;
        float fy1 = (float) y1;
        for (int base = 0; base < expandedObstacles.length; base += 4) {
            boolean startsInside = containsBox(expandedObstacles, base, fx0, fy0);
            boolean endsInside = containsBox(expandedObstacles, base, fx1, fy1);
            if (segmentIntersects(x0, y0, x1, y1,
                    expandedObstacles[base], expandedObstacles[base + 1],
                    expandedObstacles[base + 2], expandedObstacles[base + 3])
                    && !(startsInside && allowStartInside) && !(endsInside && allowEndInside)) return false;
        }
        return true;
    }

    private static boolean segmentIntersects(double x0, double y0, double x1, double y1,
                                             float minX, float minY, float maxX, float maxY) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double t0 = 0.0;
        double t1 = 1.0;

        // Clip against X bounds (-dx, x0 - minX) and (dx, maxX - x0)
        if (Math.abs(dx) < 1.0e-12) {
            if (x0 - minX < 0 || maxX - x0 < 0) return false;
        } else {
            double tLeft = (x0 - minX) / (-dx);
            double tRight = (maxX - x0) / dx;
            if (-dx < 0) {
                if (tLeft > t1) return false;
                t0 = Math.max(t0, tLeft);
                if (tRight < t0) return false;
                t1 = Math.min(t1, tRight);
            } else {
                if (tLeft < t0) return false;
                t1 = Math.min(t1, tLeft);
                if (tRight > t1) return false;
                t0 = Math.max(t0, tRight);
            }
        }

        // Clip against Y bounds (-dy, y0 - minY) and (dy, maxY - y0)
        if (Math.abs(dy) < 1.0e-12) {
            if (y0 - minY < 0 || maxY - y0 < 0) return false;
        } else {
            double tBottom = (y0 - minY) / (-dy);
            double tTop = (maxY - y0) / dy;
            if (-dy < 0) {
                if (tBottom > t1) return false;
                t0 = Math.max(t0, tBottom);
                if (tTop < t0) return false;
                t1 = Math.min(t1, tTop);
            } else {
                if (tBottom < t0) return false;
                t1 = Math.min(t1, tBottom);
                if (tTop > t1) return false;
                t0 = Math.max(t0, tTop);
            }
        }

        return t0 <= t1;
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
