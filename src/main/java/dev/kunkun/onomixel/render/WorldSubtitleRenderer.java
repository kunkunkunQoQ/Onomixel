package dev.kunkun.onomixel.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.kunkun.onomixel.sound.ClientSoundCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.context.ContextKey;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ExtractLevelRenderStateEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;

import java.util.ArrayList;
import java.util.List;

/** Frame extraction reads live state; submission only reads the extracted frame. */
public final class WorldSubtitleRenderer {
    private static final ContextKey<List<Text>> FRAME = new ContextKey<>(Identifier.fromNamespaceAndPath("onomixel", "subtitles"));
    private static final int FULL_BRIGHT = 0xF000F0;
    private final ClientSoundCapture capture;
    private final CaptionTextCache texts = new CaptionTextCache();
    private final DynamicSubtitleLayout dynamicLayout = new DynamicSubtitleLayout(texts);

    private record Text(double x, double y, double z, float scaleX, float scaleY, float tilt,
                        CaptionTextCache.Text layout, int color, int outline, float starburstAlpha) {}

    public WorldSubtitleRenderer(ClientSoundCapture capture) { this.capture = capture; }

    public void onReloadListeners(AddClientReloadListenersEvent event) {
        event.addListener(Identifier.fromNamespaceAndPath("onomixel", "text_metrics"),
                (ResourceManagerReloadListener) ignored -> texts.invalidate());
    }

    @SubscribeEvent
    public void extract(ExtractLevelRenderStateEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = capture.pump(minecraft);
        var settings = capture.settings();
        if (!settings.enabled() || minecraft.level != event.getLevel()) {
            dynamicLayout.clear();
            event.getRenderState().setRenderData(FRAME, List.of());
            return;
        }
        CameraRenderState camera = event.getRenderState().cameraRenderState;
        List<Text> frame = new ArrayList<>();
        for (var placed : dynamicLayout.layout(minecraft, capture.subtitles(), camera, now, settings)) {
            var subtitle = placed.subtitle();
            double alpha = subtitle.alpha(now, settings.fade()) * settings.opacity() * placed.alphaMultiplier();
            int opacity = (int) Math.round(alpha * 255);
            if (opacity < 4) continue;
            var point = placed.point();
            float starburst = placed.transform().starburstAlpha();
            Text text = new Text(point.x(), point.y(), point.z(),
                    placed.baseScale() * placed.effect().scaleX(), placed.baseScale() * placed.effect().scaleY(),
                    placed.effect().tiltDegrees(), placed.text(),
                    (opacity << 24) | placed.effect().rgb(), (opacity << 24) | 0x191B24, starburst);
            frame.add(text);
        }
        event.getRenderState().setRenderData(FRAME, List.copyOf(frame));
    }

    @SubscribeEvent
    public void submit(SubmitCustomGeometryEvent event) {
        List<Text> frame = event.getLevelRenderState().getRenderData(FRAME);
        if (frame == null || frame.isEmpty()) return;
        CameraRenderState camera = event.getLevelRenderState().cameraRenderState;
        PoseStack poses = event.getPoseStack();
        for (Text text : frame) {
            poses.pushPose();
            poses.translate(text.x - camera.pos.x, text.y - camera.pos.y, text.z - camera.pos.z);
            poses.rotate(camera.orientation);
            poses.rotateDegrees(Axis.ZP, text.tilt);
            poses.scale(text.scaleX, -text.scaleY, Math.min(text.scaleX, text.scaleY));
            if (text.starburstAlpha > 0.01F) {
                renderMangaStarburstSpikes(event.getSubmitNodeCollector(), poses, text);
            }
            if (text.layout.glyphs().isEmpty()) {
                event.getSubmitNodeCollector().submitText(poses, -text.layout.halfWidth(), -text.layout.halfHeight(),
                        text.layout.text(), false, Font.DisplayMode.NORMAL, FULL_BRIGHT, text.color, 0, text.outline);
            } else {
                for (CaptionTextCache.GlyphDraw glyph : text.layout.glyphs()) {
                    var motion = glyph.motion();
                    poses.pushPose();
                    poses.translate(motion.centerX() + motion.offsetX(), motion.offsetY(), 0.0);
                    poses.rotateDegrees(Axis.ZP, motion.tiltDeg());
                    poses.scale(motion.scaleX(), motion.scaleY(), Math.min(motion.scaleX(), motion.scaleY()));
                    event.getSubmitNodeCollector().submitText(poses,
                            -motion.advance() / 2.0F, -text.layout.glyphHalfHeight(), glyph.text(),
                            false, Font.DisplayMode.NORMAL, FULL_BRIGHT, text.color, 0, text.outline);
                    poses.popPose();
                }
            }
            poses.popPose();
        }
    }

    private void renderMangaStarburstSpikes(OrderedSubmitNodeCollector collector, PoseStack poses, Text text) {
        float alpha = text.starburstAlpha;
        if (alpha <= 0.01F) return;

        int alphaInt = (int) Math.clamp(alpha * 220.0F, 0.0F, 255.0F);
        if (alphaInt < 4) return;

        int goldColor = (alphaInt << 24) | (MangaHitstopEffect.STARBURST_GOLD_COLOR & 0xFFFFFF);
        int blackColor = (alphaInt << 24) | (MangaHitstopEffect.STARBURST_BLACK_COLOR & 0xFFFFFF);

        float halfW = text.layout.halfWidth();
        float halfH = text.layout.halfHeight();
        float baseRadius = Math.max(halfW, halfH) + 2.0F;

        var spikes = MangaHitstopEffect.generateSpikes(MangaHitstopEffect.DEFAULT_SPIKE_COUNT, alpha, baseRadius);
        for (var spike : spikes) {
            poses.pushPose();
            poses.rotateDegrees(Axis.ZP, spike.angleDegrees());

            float halfThick = spike.thickness() / 2.0F;
            // High-contrast comic outline spike
            collector.submitTextBackground(poses, -halfThick - 0.6F, spike.innerRadius(),
                    halfThick + 0.6F, spike.outerRadius() + 1.2F, blackColor,
                    Font.DisplayMode.NORMAL, FULL_BRIGHT);

            // Core golden burst spike
            collector.submitTextBackground(poses, -halfThick, spike.innerRadius(),
                    halfThick, spike.outerRadius(), goldColor,
                    Font.DisplayMode.NORMAL, FULL_BRIGHT);

            poses.popPose();
        }
    }
}
