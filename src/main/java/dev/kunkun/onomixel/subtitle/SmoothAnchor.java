package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.subtitle.CaptionPlacement.Point;

/** World-space smoothing plus hysteresis for switching between local layout slots. */
public final class SmoothAnchor {
    private Point position;

    public SmoothAnchor(Point initial) { position = initial; }
    public Point position() { return position; }
    public void reset(Point point) { position = point; }

    public Point advance(Point target, double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0) return position;
        double dt = Math.min(seconds, 0.10);
        double fraction = 1 - Math.exp(-dt / 0.115);
        double distance = Math.sqrt(position.distanceSquared(target));
        if (distance > 0) fraction = Math.min(fraction, 5.0 * dt / distance);
        position = new Point(position.x() + (target.x() - position.x()) * fraction,
                position.y() + (target.y() - position.y()) * fraction,
                position.z() + (target.z() - position.z()) * fraction);
        return position;
    }

    public static int stableSlot(int current, int best, double currentScore, double bestScore, double heldSeconds) {
        if (current < 0 || !Double.isFinite(currentScore)) return best;
        if (best < 0 || best == current || heldSeconds < 0.24 || bestScore + 0.70 >= currentScore) return current;
        return best;
    }
}
