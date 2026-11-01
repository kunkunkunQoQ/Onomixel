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
    public static final int BADGE_SLOT_SPACING_Y = 19;  // Vertical slot spacing for left/right edges
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
     */
    public record PlannedBadge(
            float renderX,
            float renderY,
            float proximityAlpha,
            ThreatRadarCalculator.EdgeSide edgeSide
    ) {}

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

        // Track badge count per edge side to offset subsequent badges
        int leftCount = 0;
        int rightCount = 0;
        int topCount = 0;
        int bottomCount = 0;

        for (InputCandidate candidate : candidates) {
            ThreatRadarCalculator.EdgeTarget target = candidate.target();
            ThreatRadarCalculator.EdgeSide side = target.edgeSide();
            int halfW = Math.max(0, candidate.halfWidth());
            int halfH = Math.max(0, candidate.halfHeight());

            float alpha = calculateProximityAlpha(candidate.distance(), candidate.maxDistance());

            float finalX;
            float finalY;

            float minY = halfH + SAFE_MARGIN_TOP;
            float maxY = screenHeight - halfH - SAFE_MARGIN_BOTTOM;
            float minX = halfW + SAFE_MARGIN_SIDES;
            float maxX = screenWidth - halfW - SAFE_MARGIN_SIDES;

            switch (side) {
                case LEFT -> {
                    finalX = minX > maxX ? screenWidth * 0.5F : Math.clamp(Math.max(minX, target.screenX()), minX, maxX);
                    // Stagger vertically downwards or upwards on collision
                    float baseY = target.screenY() + (leftCount * BADGE_SLOT_SPACING_Y);
                    // If overflowing bottom safe area, bounce upwards
                    if (baseY + halfH > screenHeight - SAFE_MARGIN_BOTTOM) {
                        baseY = target.screenY() - (leftCount * BADGE_SLOT_SPACING_Y);
                    }
                    finalY = minY > maxY ? screenHeight * 0.5F : Math.clamp(baseY, minY, maxY);
                    leftCount++;
                }
                case RIGHT -> {
                    finalX = minX > maxX ? screenWidth * 0.5F : Math.clamp(Math.min(maxX, target.screenX()), minX, maxX);
                    float baseY = target.screenY() + (rightCount * BADGE_SLOT_SPACING_Y);
                    if (baseY + halfH > screenHeight - SAFE_MARGIN_BOTTOM) {
                        baseY = target.screenY() - (rightCount * BADGE_SLOT_SPACING_Y);
                    }
                    finalY = minY > maxY ? screenHeight * 0.5F : Math.clamp(baseY, minY, maxY);
                    rightCount++;
                }
                case TOP -> {
                    finalY = minY > maxY ? screenHeight * 0.5F : minY;
                    // Horizontal flow expansion: stagger along X instead of dropping down to screen center!
                    int step = (halfW * 2) + BADGE_SLOT_SPACING_X;
                    float baseX = target.screenX() + (topCount * step);
                    if (baseX + halfW > screenWidth - SAFE_MARGIN_SIDES) {
                        baseX = target.screenX() - (topCount * step);
                    }
                    finalX = minX > maxX ? screenWidth * 0.5F : Math.clamp(baseX, minX, maxX);
                    topCount++;
                }
                case BOTTOM -> {
                    // Safe bottom lift to prevent overlapping with Minecraft hotbar & hearts
                    finalY = minY > maxY ? screenHeight * 0.5F : maxY;
                    // Horizontal flow expansion: stagger along X
                    int step = (halfW * 2) + BADGE_SLOT_SPACING_X;
                    float baseX = target.screenX() + (bottomCount * step);
                    if (baseX + halfW > screenWidth - SAFE_MARGIN_SIDES) {
                        baseX = target.screenX() - (bottomCount * step);
                    }
                    finalX = minX > maxX ? screenWidth * 0.5F : Math.clamp(baseX, minX, maxX);
                    bottomCount++;
                }
                default -> {
                    finalX = target.screenX();
                    finalY = target.screenY();
                }
            }

            results.add(new PlannedBadge(finalX, finalY, alpha, side));
        }

        return results;
    }
}
