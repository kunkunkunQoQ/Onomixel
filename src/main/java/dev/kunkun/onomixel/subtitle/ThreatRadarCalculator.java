package dev.kunkun.onomixel.subtitle;

/**
 * Pure mathematical projection for off-screen and rear sound sources onto 2D HUD edge borders.
 * Accurately maps 3D spatial threat bearings onto screen perimeter safe zones.
 * Zero Minecraft runtime dependencies for pure JVM testability.
 */
public final class ThreatRadarCalculator {

    public record EdgeTarget(
            float screenX,
            float screenY,
            float rotationAngleRad,
            double distance,
            boolean isOffScreen,
            boolean isBehind,
            float pulse,
            EdgeSide edgeSide
    ) {}

    public enum EdgeSide {
        LEFT, RIGHT, TOP, BOTTOM
    }

    public static EdgeTarget calculate(
            double camX, double camY, double camZ,
            double lookX, double lookY, double lookZ,
            double upX, double upY, double upZ,
            double soundX, double soundY, double soundZ,
            int screenWidth, int screenHeight,
            int badgeHalfWidth, int badgeHalfHeight,
            long nowNano,
            boolean isCritical
    ) {
        return calculate(camX, camY, camZ, lookX, lookY, lookZ, upX, upY, upZ,
                soundX, soundY, soundZ, screenWidth, screenHeight, badgeHalfWidth,
                badgeHalfHeight, nowNano, isCritical, 70.0);
    }

    public static EdgeTarget calculate(
            double camX, double camY, double camZ,
            double lookX, double lookY, double lookZ,
            double upX, double upY, double upZ,
            double soundX, double soundY, double soundZ,
            int screenWidth, int screenHeight,
            int badgeHalfWidth, int badgeHalfHeight,
            long nowNano,
            boolean isCritical,
            double verticalFovDegrees
    ) {
        double viewportAspect = screenWidth > 0 && screenHeight > 0
                ? (double) screenWidth / screenHeight : 1.0;
        return calculate(camX, camY, camZ, lookX, lookY, lookZ, upX, upY, upZ,
                soundX, soundY, soundZ, screenWidth, screenHeight, badgeHalfWidth,
                badgeHalfHeight, nowNano, isCritical, verticalFovDegrees, viewportAspect);
    }

    public static EdgeTarget calculate(
            double camX, double camY, double camZ,
            double lookX, double lookY, double lookZ,
            double upX, double upY, double upZ,
            double soundX, double soundY, double soundZ,
            int screenWidth, int screenHeight,
            int badgeHalfWidth, int badgeHalfHeight,
            long nowNano,
            boolean isCritical,
            double verticalFovDegrees,
            double projectionAspectRatio
    ) {
        // 1. Right vector: R = Look x Up (normalized)
        double rx = lookY * upZ - lookZ * upY;
        double ry = lookZ * upX - lookX * upZ;
        double rz = lookX * upY - lookY * upX;
        double rLen = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rLen > 1e-6) {
            rx /= rLen; ry /= rLen; rz /= rLen;
        } else {
            rx = 1.0; ry = 0.0; rz = 0.0;
        }

        // 2. Relative displacement: D = S - C
        double dx = soundX - camX;
        double dy = soundY - camY;
        double dz = soundZ - camZ;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        // 3. Project onto camera local coordinate system:
        // xCam: positive = RIGHT of camera
        // yCam: positive = ABOVE camera
        // zCam: positive = IN FRONT of camera
        double zCam = dx * lookX + dy * lookY + dz * lookZ;
        double xCam = dx * rx + dy * ry + dz * rz;
        double yCam = dx * upX + dy * upY + dz * upZ;

        boolean isBehind = zCam <= 0.20;

        // Match Camera's perspective projection: vertical FOV plus the viewport aspect ratio.
        double fov = Double.isFinite(verticalFovDegrees)
                ? Math.clamp(verticalFovDegrees, 1.0, 179.0) : 70.0;
        double verticalTangent = Math.tan(Math.toRadians(fov) * 0.5);
        double aspect = Double.isFinite(projectionAspectRatio) && projectionAspectRatio > 0.0
                ? projectionAspectRatio : 1.0;
        boolean isOffScreen = isBehind
                || (zCam > 0.0 && (Math.abs(xCam) > zCam * verticalTangent * aspect
                || Math.abs(yCam) > zCam * verticalTangent));

        // 5. Compute bearing angle on screen perimeter:
        // We want the indicator to point in the direction the player needs to look/turn!
        // When threat is to the RIGHT (xCam > 0): bearing must point towards the RIGHT edge!
        // When threat is to the LEFT (xCam < 0): bearing must point towards the LEFT edge!
        double dirX;
        double dirY;

        if (!isBehind) {
            // In front, but outside view frustum:
            dirX = xCam;
            dirY = -yCam; // Screen coordinate Y is downwards
        } else {
            // Behind camera plane:
            // Lateral direction MUST be preserved: xCam > 0 is right, xCam < 0 is left!
            dirX = xCam;

            // For rear threats: bias towards bottom edge of the screen (+Y in screen coordinates)
            // If threat is directly behind (xCam ≈ 0): point directly to bottom border
            double rearWeight = Math.min(1.0, Math.abs(zCam) / Math.max(0.1, dist));
            double yOffset = -yCam; // preserve elevation
            dirY = 0.85 * rearWeight + 0.35 * yOffset;

            // If directly centered behind with tiny x/y: force bottom border
            if (Math.abs(dirX) < 1e-4 && Math.abs(dirY) < 1e-4) {
                dirX = 0.0;
                dirY = 1.0;
            }
        }

        // Angle in radians from screen center (0 = right, pi/2 = bottom, pi = left, -pi/2 = top)
        double angle = Math.atan2(dirY, dirX);

        // 6. Clamp to screen perimeter taking badge dimensions into account:
        // Safe inner boundary margins
        int marginX = Math.max(badgeHalfWidth + 6, 32);
        int marginY = Math.max(badgeHalfHeight + 6, 20);

        float cx = screenWidth / 2.0f;
        float cy = screenHeight / 2.0f;
        float halfW = cx - marginX;
        float halfH = cy - marginY;

        // Intersect ray with screen rectangle:
        // x = cx + t * cos(angle), y = cy + t * sin(angle)
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);

        float edgeX;
        float edgeY;
        EdgeSide side;

        float tX = cos > 1e-5 ? halfW / cos : (cos < -1e-5 ? -halfW / cos : Float.MAX_VALUE);
        float tY = sin > 1e-5 ? halfH / sin : (sin < -1e-5 ? -halfH / sin : Float.MAX_VALUE);

        if (tX < tY) {
            edgeX = cx + tX * cos;
            edgeY = cy + tX * sin;
            side = cos > 0 ? EdgeSide.RIGHT : EdgeSide.LEFT;
        } else {
            edgeX = cx + tY * cos;
            edgeY = cy + tY * sin;
            side = sin > 0 ? EdgeSide.BOTTOM : EdgeSide.TOP;
        }

        // Absolute safety clamp
        edgeX = Math.clamp(edgeX, (float) marginX, (float) (screenWidth - marginX));
        edgeY = Math.clamp(edgeY, (float) marginY, (float) (screenHeight - marginY));

        // 7. Dynamic pulse factor
        float pulse = 1.0f;
        if (isCritical) {
            double seconds = (nowNano % 10_000_000_000L) / 1_000_000_000.0;
            // 4Hz urgent comic warning pulse
            pulse = (float) (0.75 + 0.25 * Math.sin(seconds * 8.0 * Math.PI));
        }

        return new EdgeTarget(edgeX, edgeY, (float) angle, dist, isOffScreen, isBehind, pulse, side);
    }
}
