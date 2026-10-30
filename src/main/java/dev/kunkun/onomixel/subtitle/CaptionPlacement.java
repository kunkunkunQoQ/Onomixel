package dev.kunkun.onomixel.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/** Bounded, creation-time local search. It never turns an occluded origin into a visible label. */
public final class CaptionPlacement {
    private CaptionPlacement() {}

    public record Point(double x, double y, double z) {
        public Point plus(Point direction, double distance) {
            return new Point(x + direction.x * distance, y + direction.y * distance, z + direction.z * distance);
        }
        public Point plus(Point other) {
            return new Point(x + other.x, y + other.y, z + other.z);
        }
        public Point plus(double dx, double dy, double dz) {
            return new Point(x + dx, y + dy, z + dz);
        }
        public double distanceSquared(Point other) {
            double dx = x - other.x, dy = y - other.y, dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    /** Camera basis vectors must be unit length; all nine candidates stay local to the preferred pivot. */
    public static List<Point> candidates(Point pivot, Point towardCamera, Point right, Point up,
                                         double push, double spacing) {
        Point front = pivot.plus(towardCamera, Math.clamp(push, 0, 0.85));
        double step = Math.clamp(spacing, 0.25, 0.55);
        List<Point> points = new ArrayList<>(9);
        points.add(front);
        points.add(front.plus(up, step));
        points.add(front.plus(right, step));
        points.add(front.plus(right, -step));
        points.add(front.plus(up, -step));
        for (int side : new int[]{-1, 1}) {
            points.add(front.plus(right, side * step).plus(up, step));
            points.add(front.plus(right, side * step).plus(up, -step));
        }
        return List.copyOf(points);
    }

    /** Slots surround the source silhouette instead of crowding its center. */
    public static List<Point> aroundSource(Point pivot, Point towardCamera, Point right, Point up,
                                          double push, double horizontal, double above, double below, double side) {
        Point front = pivot.plus(towardCamera, Math.clamp(push, 0, 1.15));
        double h = Math.clamp(horizontal, 0.35, 1.90);
        double top = Math.clamp(above, 0.28, 1.60);
        double bottom = Math.clamp(below, 0.28, 1.60);
        double sign = side < 0 ? -1 : 1;
        return List.of(front.plus(right, sign * h), front.plus(right, -sign * h),
                front.plus(up, top), front.plus(right, sign * h * 0.82).plus(up, top * 0.85),
                front.plus(right, -sign * h * 0.82).plus(up, top * 0.85), front.plus(up, -bottom),
                front.plus(right, sign * h * 0.82).plus(up, -bottom * 0.80),
                front.plus(right, -sign * h * 0.82).plus(up, -bottom * 0.80), front);
    }

    /** A hidden source keeps its ordinary depth-tested pivot; no search around the blocking wall. */
    public static Point choose(Point pivot, List<Point> candidates, boolean originVisible,
                               Predicate<Point> reachable, ToDoubleFunction<Point> score) {
        if (!originVisible) return pivot;
        Point selected = pivot;
        double best = Double.POSITIVE_INFINITY;
        for (Point point : candidates) {
            double value = score.applyAsDouble(point);
            if (Double.isFinite(value) && value < best && reachable.test(point)) {
                selected = point;
                best = value;
            }
        }
        return selected;
    }
}
