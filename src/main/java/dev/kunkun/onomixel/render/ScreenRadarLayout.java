package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.subtitle.ThreatRadarCalculator;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure mathematical layout and anti-collision slotting engine for 2D screen-edge radar.
 * Zero Minecraft runtime dependencies for pure JVM testability.
 *
 * Implements:
 * 1. Proximity Alpha Scaling: Alpha increases as distance decreases (closer = more opaque, 0.35 -> 1.00).
 * 2. Multi-Edge Independent Flow: Dispatches items to their respective edge buckets (LEFT, RIGHT, TOP, BOTTOM).
 * 3. Smart HUD Safety Margins: Lifts bottom items by 52px to prevent overlapping with hotbar, hearts, and exp bars.
 * 4. Horizontal / Vertical Anti-Collision Spacing: Prevents stacked badge occlusions on shared edges.
 */
public final class ScreenRadarLayout {

    public static final float MIN_PROXIMITY_ALPHA = 0.35f;
    public static final float MAX_PROXIMITY_ALPHA = 1.00f;
    public static final double MIN_DANGER_DISTANCE = 3.0; // 3 meters or less is maximum danger

    public static final int SAFE_MARGIN_TOP = 18;       // Clearance for boss health bar
    public static final int SAFE_MARGIN_BOTTOM = 52;    // Clearance for hotbar, health, armor, exp bar
    public static final int SAFE_MARGIN_SIDES = 8;      // Clearance for screen bezels
    private static final int BADGE_GAP_Y = 5;
    public static final int BADGE_SLOT_SPACING_Y = 14 + BADGE_GAP_Y; // Standard 14px badge plus clearance
    public static final int BADGE_SLOT_SPACING_X = 8;   // Horizontal slot margin for top/bottom edges

    /**
     * Planned 2D layout descriptor for an edge-anchored tactical radar warning badge.
     * <p>
     * <b>Coordinate System:</b> Screen pixel coordinates in Minecraft UI window space:
     * <ul>
     *   <li>{@code renderX}: Horizontal center position in pixels, spanning {@code [0, screenWidth]}.
     *       Origin (0) is the left screen edge.</li>
     *   <li>{@code renderY}: Vertical center position in pixels, spanning {@code [0, screenHeight]}.
     *       Origin (0) is the top screen edge. Automatically adjusted for top Boss bars (+18px)
     *       and bottom hotbar/hearts (+52px) safety clearance.</li>
     * </ul>
     *
     * @param renderX Screen-space X center coordinate in UI scaled pixels [0, screenWidth]
     * @param renderY Screen-space Y center coordinate in UI scaled pixels [0, screenHeight]
     * @param proximityAlpha Normalized distance-adaptive opacity multiplier in {@code [0.35, 1.00]}
     * @param edgeSide Clamped screen edge side ({@link ThreatRadarCalculator.EdgeSide#LEFT},
     *                 {@link ThreatRadarCalculator.EdgeSide#RIGHT}, {@link ThreatRadarCalculator.EdgeSide#TOP},
     *                 or {@link ThreatRadarCalculator.EdgeSide#BOTTOM})
     * @param candidateIndex index in the input list so skipped badges do not shift the labels of later candidates
     */
    public record PlannedBadge(
            float renderX,
            float renderY,
            float proximityAlpha,
            ThreatRadarCalculator.EdgeSide edgeSide,
            int candidateIndex
    ) {
        public PlannedBadge(float renderX, float renderY, float proximityAlpha, ThreatRadarCalculator.EdgeSide edgeSide) {
            this(renderX, renderY, proximityAlpha, edgeSide, -1);
        }
    }

    private ScreenRadarLayout() {}

    /**
     * Calculates the distance-adaptive opacity scaling factor.
     * Closer distances produce higher opacity (approaching 1.0), while distant warnings remain subtle (0.35).
     *
     * @param distance current Euclidean distance in meters
     * @param maxDistance maximum alert threshold distance in meters
     * @return alpha in [0.35, 1.00]
     */
    public static float calculateProximityAlpha(double distance, double maxDistance) {
        if (!Double.isFinite(distance) || distance <= MIN_DANGER_DISTANCE) {
            return MAX_PROXIMITY_ALPHA;
        }
        if (!Double.isFinite(maxDistance) || maxDistance <= MIN_DANGER_DISTANCE) {
            return MAX_PROXIMITY_ALPHA;
        }
        if (distance >= maxDistance) {
            return MIN_PROXIMITY_ALPHA;
        }

        double normalizedFactor = 1.0 - (distance - MIN_DANGER_DISTANCE) / (maxDistance - MIN_DANGER_DISTANCE);
        double clampedFactor = Math.clamp(normalizedFactor, 0.0, 1.0);
        return (float) (MIN_PROXIMITY_ALPHA + (MAX_PROXIMITY_ALPHA - MIN_PROXIMITY_ALPHA) * clampedFactor);
    }

    public record InputCandidate(
            ThreatRadarCalculator.EdgeTarget target,
            int halfWidth,
            int halfHeight,
            double distance,
            double maxDistance
    ) {}

    /**
     * Solves multi-edge slot positions and anti-collision for up to N radar candidates.
     */
    public static List<PlannedBadge> layout(
            List<InputCandidate> candidates,
            int screenWidth,
            int screenHeight
    ) {
        if (candidates == null || candidates.isEmpty() || screenWidth <= 0 || screenHeight <= 0) {
            return List.of();
        }

        List<PlannedBadge> results = new ArrayList<>(candidates.size());

        for (int index = 0; index < candidates.size(); index++) {
            InputCandidate candidate = candidates.get(index);
            ThreatRadarCalculator.EdgeTarget target = candidate.target();
            ThreatRadarCalculator.EdgeSide side = target.edgeSide();
            int halfW = Math.max(0, candidate.halfWidth());
            int halfH = Math.max(0, candidate.halfHeight());

            float alpha = calculateProximityAlpha(candidate.distance(), candidate.maxDistance());

            float minY = halfH + SAFE_MARGIN_TOP;
            float maxY = screenHeight - halfH - SAFE_MARGIN_BOTTOM;
            float minX = halfW + SAFE_MARGIN_SIDES;
            float maxX = screenWidth - halfW - SAFE_MARGIN_SIDES;

            if (minX > maxX) minX = maxX = screenWidth * 0.5f;
            if (minY > maxY) minY = maxY = screenHeight * 0.5f;
            boolean horizontal = side == ThreatRadarCalculator.EdgeSide.TOP || side == ThreatRadarCalculator.EdgeSide.BOTTOM;
            float min = horizontal ? minX : minY;
            float max = horizontal ? maxX : maxY;
            float preferred = Math.clamp(horizontal ? target.screenX() : target.screenY(), min, max);
            int halfSize = horizontal ? halfW : halfH;
            float position = openPosition(candidates, results, side, horizontal, halfSize, preferred, 1);
            if (position != preferred) {
                float before = openPosition(candidates, results, side, horizontal, halfSize, preferred, -1);
                if (before >= min && (position > max || preferred - before < position - preferred)) position = before;
            }
            if (position > max) continue; // The higher-priority badges already fill this edge.
            float x = horizontal ? position : Math.clamp(target.screenX(), minX, maxX);
            float y = horizontal ? (side == ThreatRadarCalculator.EdgeSide.TOP ? minY : maxY) : position;
            results.add(new PlannedBadge(x, y, alpha, side, index));
        }

        return results;
    }

    private static float openPosition(List<InputCandidate> candidates, List<PlannedBadge> placed,
                                      ThreatRadarCalculator.EdgeSide side, boolean horizontal, int halfSize,
                                      float preferred, int direction) {
        float position = preferred;
        // Each move clears one whole occupied interval in the requested direction.
        for (int pass = 0; pass < placed.size(); pass++) {
            float previous = position;
            for (PlannedBadge badge : placed) {
                if (badge.edgeSide() != side) continue;
                InputCandidate occupied = candidates.get(badge.candidateIndex());
                float center = horizontal ? badge.renderX() : badge.renderY();
                float clearance = halfSize + (horizontal ? occupied.halfWidth() + BADGE_SLOT_SPACING_X
                        : occupied.halfHeight() + BADGE_GAP_Y);
                if (position > center - clearance && position < center + clearance) {
                    position = center + direction * clearance;
                }
            }
            if (position == previous) break;
        }
        return position;
    }
}
