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
import java.util.List;

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

    // Zero-allocation pre-allocated scratch pools for 60-144 FPS rendering
    private static final int MAX_CANDIDATES = 16;
    private static final int MAX_DISPLAYED = 4;

    private static final class CandidateSlot {
        String soundId;
        double soundX, soundY, soundZ;
        boolean isCritical;
        double distance;
        double maxDistance;

        void set(String soundId, double soundX, double soundY, double soundZ,
                 boolean isCritical, double distance, double maxDistance) {
            this.soundId = soundId;
            this.soundX = soundX;
            this.soundY = soundY;
            this.soundZ = soundZ;
            this.isCritical = isCritical;
            this.distance = distance;
            this.maxDistance = maxDistance;
        }
    }

    private final CandidateSlot[] candidatePool = new CandidateSlot[MAX_CANDIDATES];
    private final CandidateSlot[] selectedCandidates = new CandidateSlot[MAX_DISPLAYED];
    private final String[] displayTexts = new String[MAX_DISPLAYED];
    private final int[] textWidths = new int[MAX_DISPLAYED];
    private final int[] halfWidths = new int[MAX_DISPLAYED];
    private final int[] halfHeights = new int[MAX_DISPLAYED];
    private final ThreatRadarCalculator.EdgeTarget[] targets = new ThreatRadarCalculator.EdgeTarget[MAX_DISPLAYED];

    // Reusable list to interface with ScreenRadarLayout without per-frame ArrayList allocations
    private final List<ScreenRadarLayout.InputCandidate> reusableLayoutInputs = new ArrayList<>(MAX_DISPLAYED);

    public ScreenRadarRenderer(ClientSoundCapture capture) {
        this.capture = capture;
        for (int i = 0; i < MAX_CANDIDATES; i++) {
            this.candidatePool[i] = new CandidateSlot();
        }
        for (int i = 0; i < MAX_DISPLAYED; i++) {
            this.selectedCandidates[i] = new CandidateSlot();
        }
    }

    private static String formatDistance(double dist) {
        int tenths = (int) Math.round(dist * 10.0);
        int whole = tenths / 10;
        int frac = Math.abs(tenths % 10);
        return whole + "." + frac + "m";
    }

    private static int compareCandidates(CandidateSlot a, CandidateSlot b) {
        if (a.isCritical != b.isCritical) {
            return a.isCritical ? -1 : 1; // Critical threats first
        }
        return Double.compare(a.distance, b.distance); // Closer threats first
    }

    private static void sortCandidates(CandidateSlot[] pool, int count) {
        // Fast in-place insertion sort (count <= 16): zero lambda, comparator, or iterator allocation
        for (int i = 1; i < count; i++) {
            CandidateSlot key = pool[i];
            int j = i - 1;
            while (j >= 0 && compareCandidates(pool[j], key) > 0) {
                pool[j + 1] = pool[j];
                j--;
            }
            pool[j + 1] = key;
        }
    }

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

        int candidateCount = 0;

        for (SubtitlePool.Subtitle eventItem : pool.snapshot()) {
            long radarExpires = eventItem.radarExpiresNanos();
            if (radarExpires <= 0) {
                radarExpires = eventItem.triggeredNanos() + ThreatRegistry.radarDurationNanos(eventItem.soundId());
            }
            if (activeNow > 0 && activeNow >= radarExpires) continue;
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

            // Performance Optimization 3: Pure zero-allocation frustum cone check before string formatting
            boolean isOffScreen = ThreatRadarCalculator.isOffScreen(
                    camPos.x, camPos.y, camPos.z,
                    look.x, look.y, look.z,
                    up.x, up.y, up.z,
                    eventItem.x(), eventItem.y(), eventItem.z(),
                    camera.getFov(),
                    projectionAspect
            );

            // If threat is currently visible inside the player's 3D screen view, don't draw 2D radar badge
            if (!isOffScreen) continue;

            if (candidateCount < MAX_CANDIDATES) {
                candidatePool[candidateCount++].set(soundId, eventItem.x(), eventItem.y(), eventItem.z(),
                        isCritical, dist, maxDist);
            }
        }

        if (candidateCount == 0) return;

        // Prioritize in-place: critical first, then closest distance
        sortCandidates(candidatePool, candidateCount);

        // Select up to MAX_DISPLAYED highest-priority off-screen candidates
        int count = Math.min(MAX_DISPLAYED, candidateCount);
        reusableLayoutInputs.clear();

        for (int i = 0; i < count; i++) {
            CandidateSlot candidate = candidatePool[i];
            CandidateSlot targetSlot = selectedCandidates[i];
            targetSlot.set(candidate.soundId, candidate.soundX, candidate.soundY, candidate.soundZ,
                    candidate.isCritical, candidate.distance, candidate.maxDistance);

            String distStr = formatDistance(candidate.distance);
            String threatName = Component.translatable(ThreatRegistry.resolveThreatNameKey(candidate.soundId)).getString();
            String displayText = (candidate.isCritical ? "⚠ " : "◆ ") + threatName + " " + distStr;
            int textWidth = font.width(displayText);
            int halfWidth = textWidth / 2 + 8;
            int halfHeight = 7;
            ThreatRadarCalculator.EdgeTarget target = ThreatRadarCalculator.calculate(
                    camPos.x, camPos.y, camPos.z,
                    look.x, look.y, look.z,
                    up.x, up.y, up.z,
                    candidate.soundX, candidate.soundY, candidate.soundZ,
                    screenWidth, screenHeight,
                    halfWidth, halfHeight,
                    wallNow,
                    candidate.isCritical,
                    camera.getFov(),
                    projectionAspect
            );

            displayTexts[i] = displayText;
            textWidths[i] = textWidth;
            halfWidths[i] = halfWidth;
            halfHeights[i] = halfHeight;
            targets[i] = target;

            reusableLayoutInputs.add(new ScreenRadarLayout.InputCandidate(
                    target, halfWidth, halfHeight, candidate.distance, candidate.maxDistance
            ));
        }

        // Compute multi-edge dynamic flow positions and proximity alphas
        List<ScreenRadarLayout.PlannedBadge> plannedBadges = ScreenRadarLayout.layout(reusableLayoutInputs, screenWidth, screenHeight);

        float opacityMultiplier = (float) Math.clamp(settings.opacity(), 0.1, 1.0);
        var colorblindMode = settings.colorblindMode();

        for (int i = 0; i < plannedBadges.size(); i++) {
            ScreenRadarLayout.PlannedBadge planned = plannedBadges.get(i);
            CandidateSlot candidate = selectedCandidates[i];
            String displayText = displayTexts[i];
            int textWidth = textWidths[i];
            int halfWidth = halfWidths[i];
            int halfHeight = halfHeights[i];
            ThreatRadarCalculator.EdgeTarget target = targets[i];

            try {
                renderThreatBadge(extractor, font, displayText, textWidth, halfWidth, halfHeight,
                        candidate.isCritical, target, planned.renderX(), planned.renderY(),
                        planned.proximityAlpha(), opacityMultiplier, colorblindMode);
            } catch (Exception ignored) {
                // Safeguard GUI rendering.
            }
        }
    }

    private void renderThreatBadge(GuiGraphicsExtractor extractor, Font font, String text, int textWidth,
                                   int halfWidth, int halfHeight, boolean isCritical,
                                   ThreatRadarCalculator.EdgeTarget target,
                                   float renderX, float renderY,
                                   float proximityAlpha,
                                   float opacityMultiplier,
                                   dev.kunkun.onomixel.subtitle.ColorblindMode colorblindMode) {
        Matrix3x2fStack pose = extractor.pose();
        pose.pushMatrix();
        pose.translate(renderX, renderY);

        int halfW = halfWidth;
        int halfH = halfHeight;

        // Proximity Alpha combined with user Opacity slider setting
        float effectiveAlpha = Math.clamp(proximityAlpha, ScreenRadarLayout.MIN_PROXIMITY_ALPHA, ScreenRadarLayout.MAX_PROXIMITY_ALPHA) * opacityMultiplier;

        // Dark capsule background with distance-scaled alpha
        int bgAlpha = (int) (0xEE * effectiveAlpha);
        int bgColor = (bgAlpha << 24) | 0x090D16;

        // Border with distance-scaled pulse brightness and colorblind accessibility filtering
        int pulseAlpha = (int) (target.pulse() * 255 * effectiveAlpha);
        int borderAlphaClamped = Math.clamp(pulseAlpha, (int) (120 * effectiveAlpha), (int) (255 * effectiveAlpha));

        int rawBorderRgb = isCritical ? 0xEF4444 : 0xF59E0B;
        int filteredBorderRgb = dev.kunkun.onomixel.subtitle.CaptionVisuals.applyColorblindFilter(rawBorderRgb, colorblindMode);
        int borderColor = (borderAlphaClamped << 24) | (filteredBorderRgb & 0xFFFFFF);

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
