package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.sound.ClientSoundCapture;
import dev.kunkun.onomixel.subtitle.SubtitlePool;
import dev.kunkun.onomixel.subtitle.ThreatRadarCalculator;
import dev.kunkun.onomixel.subtitle.ThreatRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.joml.Matrix3x2fStack;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 2D screen-edge threat radar HUD renderer.
 * Clamps off-screen and rear life-threatening sound cues onto the screen border with directional chevrons.
 *
 * Visual & UX enhancements:
 * 1. Proximity Alpha Scaling: Closer threats become increasingly opaque and prominent; distant warnings remain subtle.
 * 2. Multi-Edge Independent Flow: Dispatches badges independently across screen borders with horizontal/vertical spacing.
 * 3. Smart HUD Safety Margin: Safely lifts bottom badges to prevent overlapping with hotbar, hearts, and exp bars.
 * 4. Pure typography & geometric chevrons: Rejects cartoonish emojis in favor of crisp localized manga aesthetics.
 */
public final class ScreenRadarRenderer {
    private final ClientSoundCapture capture;

    public ScreenRadarRenderer(ClientSoundCapture capture) {
        this.capture = capture;
    }

    private record PreparedRadarItem(
            String soundId,
            double soundX,
            double soundY,
            double soundZ,
            boolean isCritical,
            double distance,
            double maxDistance
    ) {}

    private record SolvedBadge(
            PreparedRadarItem candidate,
            String displayText,
            int textWidth,
            int halfWidth,
            int halfHeight,
            ThreatRadarCalculator.EdgeTarget target,
            ScreenRadarLayout.PlannedBadge planned
    ) {}

    @SubscribeEvent
    public void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        var settings = capture.settings();
        if (!settings.enabled() || !settings.threatRadar()) return;

        Camera camera = mc.gameRenderer.mainCamera();
        Vec3 camPos = camera.position();
        Quaternionf orientation = camera.rotation();

        Vector3f look = new Vector3f(0, 0, -1).rotate(orientation);
        Vector3f up = new Vector3f(0, 1, 0).rotate(orientation);

        GuiGraphicsExtractor extractor = event.getGuiGraphics();
        int screenWidth = extractor.guiWidth();
        int screenHeight = extractor.guiHeight();
        long wallNow = System.nanoTime();
        long activeNow = capture.currentActiveNanos();

        Font font = mc.font;
        SubtitlePool pool = capture.subtitles();
        // Camera projection is built from framebuffer dimensions. GUI-scaled dimensions can differ slightly after ceil rounding.
        double projectionAspect = mc.getWindow().getHeight() > 0
                ? (double) mc.getWindow().getWidth() / mc.getWindow().getHeight() : 1.0;

        List<PreparedRadarItem> preparedList = new ArrayList<>(4);

        for (SubtitlePool.Subtitle eventItem : pool.snapshot()) {
            if (activeNow > 0 && activeNow >= eventItem.expiresNanos()) continue;
            if (eventItem.localPlayerShot()) continue;

            String soundId = eventItem.soundId();

            // Performance Optimization 1: Instant enum fast-path reject before any distance math
            ThreatRegistry.ThreatLevel level = ThreatRegistry.evaluate(soundId);
            if (level == ThreatRegistry.ThreatLevel.NONE) continue;

            double dx = eventItem.x() - camPos.x;
            double dy = eventItem.y() - camPos.y;
            double dz = eventItem.z() - camPos.z;
            double distSq = dx * dx + dy * dy + dz * dz;

            // Performance Optimization 2: Distance-squared reject before square root
            double maxDist = ThreatRegistry.maxRadarDistance(level);
            if (maxDist <= 0.0 || distSq > maxDist * maxDist) continue;
            // Reject sounds inside the local player's own hitbox (< 0.85m) to avoid self-sound false alarms
            if (distSq < 0.85 * 0.85) continue;

            double dist = Math.sqrt(distSq);
            boolean isCritical = level == ThreatRegistry.ThreatLevel.CRITICAL;

            // Performance Optimization 3: Pure math frustum check before string formatting
            ThreatRadarCalculator.EdgeTarget target = ThreatRadarCalculator.calculate(
                    camPos.x, camPos.y, camPos.z,
                    look.x, look.y, look.z,
                    up.x, up.y, up.z,
                    eventItem.x(), eventItem.y(), eventItem.z(),
                    screenWidth, screenHeight,
                    40, 7,
                    wallNow,
                    isCritical,
                    camera.getFov(),
                    projectionAspect
            );

            // If threat is currently visible inside the player's 3D screen view, don't draw 2D radar badge
            if (!target.isOffScreen()) continue;

            preparedList.add(new PreparedRadarItem(soundId, eventItem.x(), eventItem.y(), eventItem.z(),
                    isCritical, dist, maxDist));
        }

        if (preparedList.isEmpty()) return;

        // Prioritize: critical first, then closest distance
        preparedList.sort(Comparator.comparing((PreparedRadarItem item) -> !item.isCritical)
                .thenComparingDouble(PreparedRadarItem::distance));

        // Select the four highest-priority off-screen candidates
        int count = Math.min(4, preparedList.size());
        List<ScreenRadarLayout.InputCandidate> layoutInputs = new ArrayList<>(count);
        List<PreparedRadarItem> selectedCandidates = new ArrayList<>(count);
        List<String> displayTexts = new ArrayList<>(count);
        List<Integer> textWidths = new ArrayList<>(count);
        List<Integer> halfWidths = new ArrayList<>(count);
        List<Integer> halfHeights = new ArrayList<>(count);
        List<ThreatRadarCalculator.EdgeTarget> targets = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            PreparedRadarItem candidate = preparedList.get(i);
            String distStr = String.format(Locale.ROOT, "%.1fm", candidate.distance());
            String threatName = Component.translatable(ThreatRegistry.resolveThreatNameKey(candidate.soundId())).getString();
            String displayText = (candidate.isCritical() ? "⚠ " : "◆ ") + threatName + " " + distStr;
            int textWidth = font.width(displayText);
            int halfWidth = textWidth / 2 + 8;
            int halfHeight = 7;
            ThreatRadarCalculator.EdgeTarget target = ThreatRadarCalculator.calculate(
                    camPos.x, camPos.y, camPos.z,
                    look.x, look.y, look.z,
                    up.x, up.y, up.z,
                    candidate.soundX(), candidate.soundY(), candidate.soundZ(),
                    screenWidth, screenHeight,
                    halfWidth, halfHeight,
                    wallNow,
                    candidate.isCritical(),
                    camera.getFov(),
                    projectionAspect
            );

            selectedCandidates.add(candidate);
            displayTexts.add(displayText);
            textWidths.add(textWidth);
            halfWidths.add(halfWidth);
            halfHeights.add(halfHeight);
            targets.add(target);

            layoutInputs.add(new ScreenRadarLayout.InputCandidate(
                    target, halfWidth, halfHeight, candidate.distance(), candidate.maxDistance()
            ));
        }

        // Compute multi-edge dynamic flow positions and proximity alphas
        List<ScreenRadarLayout.PlannedBadge> plannedBadges = ScreenRadarLayout.layout(layoutInputs, screenWidth, screenHeight);

        for (int i = 0; i < plannedBadges.size(); i++) {
            ScreenRadarLayout.PlannedBadge planned = plannedBadges.get(i);
            PreparedRadarItem candidate = selectedCandidates.get(i);
            String displayText = displayTexts.get(i);
            int textWidth = textWidths.get(i);
            int halfWidth = halfWidths.get(i);
            int halfHeight = halfHeights.get(i);
            ThreatRadarCalculator.EdgeTarget target = targets.get(i);

            try {
                renderThreatBadge(extractor, font, displayText, textWidth, halfWidth, halfHeight,
                        candidate.isCritical(), target, planned.renderX(), planned.renderY(),
                        planned.proximityAlpha());
            } catch (Exception ignored) {
                // Safeguard GUI rendering.
            }
        }
    }

    private void renderThreatBadge(GuiGraphicsExtractor extractor, Font font, String text, int textWidth,
                                   int halfWidth, int halfHeight, boolean isCritical,
                                   ThreatRadarCalculator.EdgeTarget target,
                                   float renderX, float renderY,
                                   float proximityAlpha) {
        Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(renderX, renderY);

        int halfW = halfWidth;
        int halfH = halfHeight;

        // Proximity Alpha: Closer threats become increasingly opaque and prominent
        float effectiveAlpha = Math.clamp(proximityAlpha, ScreenRadarLayout.MIN_PROXIMITY_ALPHA, ScreenRadarLayout.MAX_PROXIMITY_ALPHA);

        // Dark capsule background with distance-scaled alpha
        int bgAlpha = (int) (0xEE * effectiveAlpha);
        int bgColor = (bgAlpha << 24) | 0x090D16;

        // Border with distance-scaled pulse brightness
        int pulseAlpha = (int) (target.pulse() * 255 * effectiveAlpha);
        int borderAlphaClamped = Math.clamp(pulseAlpha, (int) (120 * effectiveAlpha), (int) (255 * effectiveAlpha));

        int borderColor = isCritical
                ? ((borderAlphaClamped << 24) | 0xEF4444)
                : ((borderAlphaClamped << 24) | 0xF59E0B);

        // High-contrast localized label with distance-scaled alpha
        int textAlpha = (int) (255 * effectiveAlpha);
        int textRgb = isCritical ? 0xFFF1F2 : 0xFEF3C7;
        int textColor = (textAlpha << 24) | textRgb;

        // 1. Dark capsule background with comic border
        extractor.fill(-halfW, -halfH, halfW, halfH, bgColor);

        // 2. Glowing border
        extractor.fill(-halfW, -halfH, halfW, -halfH + 1, borderColor);
        extractor.fill(-halfW, halfH - 1, halfW, halfH, borderColor);
        extractor.fill(-halfW, -halfH, -halfW + 1, halfH, borderColor);
        extractor.fill(halfW - 1, -halfH, halfW, halfH, borderColor);

        // 3. Directional pointer chevron
        ThreatRadarCalculator.EdgeSide side = target.edgeSide();
        String arrow = switch (side) {
            case RIGHT -> "▶";
            case LEFT -> "◀";
            case TOP -> "▲";
            case BOTTOM -> "▼";
        };

        int arrowX = switch (side) {
            case RIGHT -> halfW + 4;
            case LEFT -> -halfW - 8;
            default -> -3;
        };
        int arrowY = switch (side) {
            case TOP -> -halfH - 9;
            case BOTTOM -> halfH + 2;
            default -> -4;
        };

        extractor.text(font, arrow, arrowX, arrowY, borderColor, true);

        // 4. Human-readable localized text label (drawn with dropShadow = true for max contrast)
        extractor.text(font, text, -textWidth / 2, -4, textColor, true);

        pose.popMatrix();
    }
}
