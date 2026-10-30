package dev.kunkun.onomixel.gui;

import dev.kunkun.onomixel.OnomixelConfig;
import dev.kunkun.onomixel.custom.CustomWordRule;
import dev.kunkun.onomixel.custom.SoundCustomizationManager;
import dev.kunkun.onomixel.render.MangaHitstopEffect;
import dev.kunkun.onomixel.sound.SoundOverrideLoader;
import dev.kunkun.onomixel.subtitle.AestheticStyle;
import dev.kunkun.onomixel.subtitle.CaptionVisuals;
import dev.kunkun.onomixel.subtitle.ColorStyle;
import dev.kunkun.onomixel.subtitle.ColorblindMode;
import dev.kunkun.onomixel.subtitle.DisplayMode;
import dev.kunkun.onomixel.subtitle.ThreatRegistry;
import dev.kunkun.onomixel.subtitle.VisualType;
import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;
import dev.kunkun.onomixel.subtitle.VisualTypes;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix3x2fStack;

import java.util.Locale;

/**
 * Native in-game manga config screen with real-time 60 FPS sandbox preview and draggable sliders.
 * Fully localized with native Chinese and English translations.
 * Modifies settings atomically using draft state; discarded on cancel/ESC.
 */
public final class OnomixelConfigScreen extends Screen {
    private final Screen parent;

    // Draft local settings state
    private boolean draftEnabled;
    private DisplayMode draftMode;
    private ColorStyle draftColorStyle;
    private AestheticStyle draftAesthetic;
    private ColorblindMode draftColorblind;
    private boolean draftRadar;
    private boolean draftKinetic;
    private boolean draftSpeedOfSound;
    private double draftScale;
    private int draftDistance;
    private double draftOpacity;
    private double draftDuration;

    public OnomixelConfigScreen(Screen parent) {
        super(Component.translatable("onomixel.config.screen.title"));
        this.parent = parent;
        this.draftEnabled = OnomixelConfig.ENABLED.get();
        this.draftMode = OnomixelConfig.DISPLAY_MODE.get();
        this.draftColorStyle = OnomixelConfig.COLOR_STYLE.get();
        this.draftAesthetic = OnomixelConfig.AESTHETIC_STYLE.get();
        this.draftColorblind = OnomixelConfig.COLORBLIND_MODE.get();
        this.draftRadar = OnomixelConfig.THREAT_RADAR.get();
        this.draftKinetic = OnomixelConfig.KINETIC_TYPOGRAPHY.get();
        this.draftSpeedOfSound = OnomixelConfig.SPEED_OF_SOUND.get();
        this.draftScale = OnomixelConfig.TEXT_SCALE.get();
        this.draftDistance = OnomixelConfig.MAX_DISTANCE.get();
        this.draftOpacity = OnomixelConfig.OPACITY.get();
        this.draftDuration = OnomixelConfig.DURATION_SECONDS.get();
    }

    private int getStartY() {
        return height < 290 ? 24 : 32;
    }

    private int getRowGap() {
        return height < 290 ? 21 : 23;
    }

    private int getBoxY() {
        return getStartY() + getRowGap() * 6 + 2;
    }

    private int getBoxH() {
        return height < 290 ? 36 : 40;
    }

    private int getActionRowY() {
        return getBoxY() + getBoxH() + (height < 290 ? 3 : 5);
    }

    @Override
    protected void init() {
        int leftCol = width / 2 - 155;
        int rightCol = width / 2 + 5;
        int startY = getStartY();
        int rowGap = getRowGap();

        // Left Column: Core Modes & Art Styles (6 rows)
        addRenderableWidget(Button.builder(
                getMasterButtonText(),
                btn -> {
                    draftEnabled = !draftEnabled;
                    btn.setMessage(getMasterButtonText());
                }
        ).bounds(leftCol, startY, 150, 20).build());

        addRenderableWidget(Button.builder(
                getModeButtonText(),
                btn -> {
                    draftMode = cycleMode(draftMode);
                    btn.setMessage(getModeButtonText());
                }
        ).bounds(leftCol, startY + rowGap, 150, 20).build());

        addRenderableWidget(Button.builder(
                getAestheticButtonText(),
                btn -> {
                    draftAesthetic = cycleAesthetic(draftAesthetic);
                    btn.setMessage(getAestheticButtonText());
                }
        ).bounds(leftCol, startY + rowGap * 2, 150, 20).build());

        addRenderableWidget(Button.builder(
                getColorStyleButtonText(),
                btn -> {
                    draftColorStyle = draftColorStyle == ColorStyle.VIBRANT ? ColorStyle.PASTEL : ColorStyle.VIBRANT;
                    btn.setMessage(getColorStyleButtonText());
                }
        ).bounds(leftCol, startY + rowGap * 3, 150, 20).build());

        addRenderableWidget(Button.builder(
                getColorblindButtonText(),
                btn -> {
                    draftColorblind = cycleColorblind(draftColorblind);
                    btn.setMessage(getColorblindButtonText());
                }
        ).bounds(leftCol, startY + rowGap * 4, 150, 20).build());

        addRenderableWidget(Button.builder(
                getSpeedOfSoundButtonText(),
                btn -> {
                    draftSpeedOfSound = !draftSpeedOfSound;
                    btn.setMessage(getSpeedOfSoundButtonText());
                }
        ).bounds(leftCol, startY + rowGap * 5, 150, 20).build());

        // Right Column: Draggable Sliders & Tactical Toggles (6 rows)
        addRenderableWidget(new DraggableSlider(
                rightCol, startY, 150, 20,
                Component.translatable("onomixel.config.screen.text_scale"),
                draftScale, 0.50, 2.00, "%.2fx",
                val -> draftScale = val
        ));

        addRenderableWidget(new DraggableSlider(
                rightCol, startY + rowGap, 150, 20,
                Component.translatable("onomixel.config.screen.max_distance"),
                draftDistance, 8.0, 48.0, "%.0fm",
                val -> draftDistance = (int) Math.round(val)
        ));

        addRenderableWidget(new DraggableSlider(
                rightCol, startY + rowGap * 2, 150, 20,
                Component.translatable("onomixel.config.screen.opacity"),
                draftOpacity * 100.0, 20.0, 100.0, "%.0f%%",
                val -> draftOpacity = val / 100.0
        ));

        addRenderableWidget(new DraggableSlider(
                rightCol, startY + rowGap * 3, 150, 20,
                Component.translatable("onomixel.config.screen.duration"),
                draftDuration, 0.5, 3.0, "%.1fs",
                val -> draftDuration = val
        ));

        addRenderableWidget(Button.builder(
                getRadarButtonText(),
                btn -> {
                    draftRadar = !draftRadar;
                    btn.setMessage(getRadarButtonText());
                }
        ).bounds(rightCol, startY + rowGap * 4, 150, 20).build());

        addRenderableWidget(Button.builder(
                getKineticButtonText(),
                btn -> {
                    draftKinetic = !draftKinetic;
                    btn.setMessage(getKineticButtonText());
                }
        ).bounds(rightCol, startY + rowGap * 5, 150, 20).build());

        // Custom Words Action Row: Reload (left) and Open Folder (right)
        int actionRowY = getActionRowY();

        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.reload_custom_words"),
                btn -> SoundOverrideLoader.reloadAll()
        ).bounds(leftCol, actionRowY, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.open_custom_folder"),
                btn -> SoundOverrideLoader.openConfigFolder()
        ).bounds(rightCol, actionRowY, 150, 20).build());

        // Bottom Action Bar
        int bottomY = height - 26;
        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.save"),
                btn -> saveAndClose()
        ).bounds(leftCol, bottomY, 150, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.cancel"),
                btn -> onClose()
        ).bounds(rightCol, bottomY, 150, 20).build());
    }

    private Component getMasterButtonText() {
        return Component.translatable("onomixel.config.screen.master")
                .append(": ")
                .append(draftEnabled ? Component.translatable("onomixel.config.screen.on") : Component.translatable("onomixel.config.screen.off"));
    }

    private Component getModeButtonText() {
        return Component.translatable("onomixel.config.screen.display_mode")
                .append(": ")
                .append(Component.translatable("onomixel.config.mode." + draftMode.name().toLowerCase(Locale.ROOT)));
    }

    private Component getAestheticButtonText() {
        return Component.translatable("onomixel.config.screen.comic_style")
                .append(": ")
                .append(Component.translatable("onomixel.config.style." + draftAesthetic.name().toLowerCase(Locale.ROOT)));
    }

    private Component getColorStyleButtonText() {
        return Component.translatable("onomixel.config.screen.color_palette")
                .append(": ")
                .append(Component.translatable("onomixel.config.palette." + draftColorStyle.name().toLowerCase(Locale.ROOT)));
    }

    private Component getColorblindButtonText() {
        return Component.translatable("onomixel.config.screen.colorblind")
                .append(": ")
                .append(Component.translatable("onomixel.config.colorblind." + draftColorblind.name().toLowerCase(Locale.ROOT)));
    }

    private Component getRadarButtonText() {
        return Component.translatable("onomixel.config.screen.threat_radar")
                .append(": ")
                .append(draftRadar ? Component.translatable("onomixel.config.screen.on") : Component.translatable("onomixel.config.screen.off"));
    }

    private Component getKineticButtonText() {
        return Component.translatable("onomixel.config.screen.kinetic")
                .append(": ")
                .append(draftKinetic ? Component.translatable("onomixel.config.screen.on") : Component.translatable("onomixel.config.screen.off"));
    }

    private Component getSpeedOfSoundButtonText() {
        return Component.translatable("onomixel.config.screen.speed_of_sound")
                .append(": ")
                .append(draftSpeedOfSound ? Component.translatable("onomixel.config.screen.on") : Component.translatable("onomixel.config.screen.off"));
    }

    private void saveAndClose() {
        OnomixelConfig.ENABLED.set(draftEnabled);
        OnomixelConfig.DISPLAY_MODE.set(draftMode);
        OnomixelConfig.COLOR_STYLE.set(draftColorStyle);
        OnomixelConfig.AESTHETIC_STYLE.set(draftAesthetic);
        OnomixelConfig.COLORBLIND_MODE.set(draftColorblind);
        OnomixelConfig.THREAT_RADAR.set(draftRadar);
        OnomixelConfig.KINETIC_TYPOGRAPHY.set(draftKinetic);
        OnomixelConfig.SPEED_OF_SOUND.set(draftSpeedOfSound);
        OnomixelConfig.TEXT_SCALE.set(draftScale);
        OnomixelConfig.MAX_DISTANCE.set(draftDistance);
        OnomixelConfig.OPACITY.set(draftOpacity);
        OnomixelConfig.DURATION_SECONDS.set(draftDuration);
        if (OnomixelConfig.SPEC.isLoaded()) {
            OnomixelConfig.SPEC.save();
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            if (parent != null) {
                minecraft.gui.setScreen(parent);
            } else {
                super.onClose();
            }
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks) {
        extractMenuBackground(graphics);
        super.extractRenderState(graphics, mouseX, mouseY, partialTicks);

        // Header Title
        String titleText = Component.translatable("onomixel.config.screen.title").getString();
        int titleWidth = font.width(titleText);
        graphics.text(font, titleText, width / 2 - titleWidth / 2, height < 290 ? 8 : 12, 0xFFFFFFFF, true);

        // Live Dynamic Preview Sandbox Box in the lower section (aligned with double-column 310px width)
        int boxW = 310;
        int boxH = getBoxH();
        int boxX = width / 2 - 155;
        int boxY = getBoxY();

        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xAA090D16);
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 1, 0x8838BDF8);
        graphics.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0x8838BDF8);
        graphics.fill(boxX, boxY, boxX + 1, boxY + boxH, 0x8838BDF8);
        graphics.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, 0x8838BDF8);

        String sandboxLabel = Component.translatable("onomixel.config.screen.sandbox_title").getString();
        graphics.text(font, sandboxLabel, boxX + 8, boxY + 3, 0xFF94A3B8, false);

        // Render Active Custom Rules Count in the top-right of the sandbox box
        int activeRules = SoundCustomizationManager.activeRuleCount();
        String activeText = Component.translatable("onomixel.config.screen.active_rules", activeRules).getString();
        int activeTextW = font.width(activeText);
        int activeColor = activeRules > 0 ? 0xFF38BDF8 : 0xFF94A3B8;
        graphics.text(font, activeText, boxX + boxW - activeTextW - 8, boxY + 3, activeColor, false);

        // Real-time custom word introspection: prioritize attack.crit or sample first active custom rule
        String previewSound = "minecraft:entity.player.attack.crit";
        CustomWordRule customRule = SoundCustomizationManager.resolve(previewSound);
        if (customRule == null && SoundCustomizationManager.activeRuleCount() > 0) {
            customRule = SoundCustomizationManager.getFirstActiveRule();
            if (customRule != null && customRule.exactId() != null) {
                previewSound = customRule.exactId();
            }
        }

        // Compute simulated comic text motion at 60 FPS
        double age = (System.currentTimeMillis() % 1600) / 1000.0;
        VisualType previewVisualType = VisualTypes.CRITICAL_HIT;
        if (customRule != null && customRule.visualTypeId() != null) {
            VisualType vt = VisualTypeRegistry.getType(customRule.visualTypeId());
            if (vt != null) previewVisualType = vt;
        }

        var styled = CaptionVisuals.sample(previewVisualType, previewSound,
                age, age, 42, draftColorStyle, draftAesthetic, draftColorblind);

        // Apply custom colors if specified in custom rule
        int finalColor = styled.rgb();
        if (customRule != null) {
            if (draftColorStyle == ColorStyle.PASTEL && customRule.pastelColor() != null) {
                finalColor = CaptionVisuals.applyColorblindFilter(customRule.pastelColor(), draftColorblind);
            } else if (customRule.vibrantColor() != null) {
                finalColor = CaptionVisuals.applyColorblindFilter(customRule.vibrantColor(), draftColorblind);
            }
        }

        // Apply kinetic motion envelope if specified
        if (draftKinetic && customRule != null && customRule.kineticMotion() != null) {
            String km = customRule.kineticMotion();
            if ("HITSTOP".equalsIgnoreCase(km)) {
                long ageNanos = (long) (age * 1_000_000_000L);
                if (MangaHitstopEffect.isHitstopActive(ageNanos)) {
                    styled = MangaHitstopEffect.applyHitstopEffect(styled, true);
                }
            } else if ("FLOAT".equalsIgnoreCase(km)) {
                styled = new CaptionVisuals.Effect(styled.scale(), styled.tiltDegrees(), styled.side(),
                        styled.rise() - Math.sin(age * Math.PI * 2.0) * 0.15, styled.rgb(), styled.stretchX(), styled.stretchY());
            } else if ("SHAKE".equalsIgnoreCase(km)) {
                float shake = (float) (Math.sin(age * 50.0) * 5.0);
                styled = new CaptionVisuals.Effect(styled.scale(), styled.tiltDegrees() + shake,
                        styled.side() + shake * 0.04, styled.rise(), styled.rgb(), styled.stretchX(), styled.stretchY());
            } else if ("POP".equalsIgnoreCase(km) || "STARBURST".equalsIgnoreCase(km)) {
                float popScale = (float) Math.max(1.0, 1.6 - age * 2.5);
                styled = new CaptionVisuals.Effect(styled.scale() * popScale, styled.tiltDegrees(),
                        styled.side(), styled.rise(), styled.rgb(), styled.stretchX() * popScale, styled.stretchY() * popScale);
            }
        }

        // Resolve sample display text
        String sampleWord;
        if (customRule != null && customRule.display() != null) {
            String resolved = customRule.display().resolve(42L, previewSound);
            if (customRule.display().isLiteral()) {
                sampleWord = resolved;
            } else {
                sampleWord = Component.translatable(resolved).getString();
            }
        } else {
            sampleWord = Component.translatable("onomixel.config.screen.sandbox_sample").getString();
        }

        if (draftAesthetic == AestheticStyle.MEDIEVAL) {
            sampleWord = "✦ " + sampleWord + " ✦";
        }
        int sampleWidth = font.width(sampleWord);

        int alpha = (int) Math.round(draftOpacity * 255);
        int finalRgbWithAlpha = (alpha << 24) | (finalColor & 0x00FFFFFF);

        float scaleMultiplier = (customRule != null && customRule.scaleMultiplier() != null) ? customRule.scaleMultiplier() : 1.0F;

        // Position central comic text: if threat radar is active in sandbox, gently offset to left (0.38F) so both fit comfortably
        float textCenterX = draftRadar ? boxX + (boxW * 0.38F) : boxX + (boxW / 2.0F);

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(textCenterX, boxY + (boxH * 0.62F));
        pose.rotate((float) Math.toRadians(styled.tiltDegrees()));
        float s = (float) (styled.scale() * draftScale * 0.9F * scaleMultiplier);
        pose.scale(s * styled.stretchX(), s * styled.stretchY());
        graphics.text(font, sampleWord, -sampleWidth / 2, -4, finalRgbWithAlpha, true);
        pose.popMatrix();

        // Real-time Tactical Radar Capsule Preview inside the sandbox
        if (draftRadar) {
            String threatKey = ThreatRegistry.resolveThreatNameKey("minecraft:entity.creeper.primed");
            String threatName = Component.translatable(threatKey).getString();
            String radarText = "⚠ " + threatName + " 3.2m";
            int radarTextW = font.width(radarText);
            int badgeHalfW = radarTextW / 2 + 5;
            int badgeHalfH = 7;

            int badgeCenterX = boxX + boxW - badgeHalfW - 16;
            int badgeCenterY = boxY + (int) (boxH * 0.62F);

            // 4Hz tactical pulse: sin(age * 4 * 2 * PI) = sin(age * 8 * PI)
            float pulse = (float) (0.65 + 0.35 * Math.sin(age * 8.0 * Math.PI));
            int baseBorderRgb = CaptionVisuals.applyColorblindFilter(0xEF4444, draftColorblind);

            float effectiveAlpha = (float) Math.clamp(draftOpacity, 0.2, 1.0);
            int borderAlpha = (int) (pulse * 255 * effectiveAlpha);
            int borderColor = (borderAlpha << 24) | (baseBorderRgb & 0xFFFFFF);

            int bgAlpha = (int) (0xDD * effectiveAlpha);
            int bgColor = (bgAlpha << 24) | 0x090D16;

            int textAlpha = (int) (255 * effectiveAlpha);
            int textColor = (textAlpha << 24) | 0xFFF1F2;

            // 1. Dark capsule background
            graphics.fill(badgeCenterX - badgeHalfW, badgeCenterY - badgeHalfH,
                    badgeCenterX + badgeHalfW, badgeCenterY + badgeHalfH, bgColor);

            // 2. Glowing pulse border
            graphics.fill(badgeCenterX - badgeHalfW, badgeCenterY - badgeHalfH,
                    badgeCenterX + badgeHalfW, badgeCenterY - badgeHalfH + 1, borderColor);
            graphics.fill(badgeCenterX - badgeHalfW, badgeCenterY + badgeHalfH - 1,
                    badgeCenterX + badgeHalfW, badgeCenterY + badgeHalfH, borderColor);
            graphics.fill(badgeCenterX - badgeHalfW, badgeCenterY - badgeHalfH,
                    badgeCenterX - badgeHalfW + 1, badgeCenterY + badgeHalfH, borderColor);
            graphics.fill(badgeCenterX + badgeHalfW - 1, badgeCenterY - badgeHalfH,
                    badgeCenterX + badgeHalfW, badgeCenterY + badgeHalfH, borderColor);

            // 3. Directional pointer chevron pointing right
            graphics.text(font, "▶", badgeCenterX + badgeHalfW + 3, badgeCenterY - 4, borderColor, true);

            // 4. Localized text label
            graphics.text(font, radarText, badgeCenterX - radarTextW / 2, badgeCenterY - 4, textColor, true);
        } else {
            // Standby indicator when radar is turned off
            String offLabel = Component.translatable("onomixel.config.screen.radar_off_standby").getString();
            int offW = font.width(offLabel);
            int offX = boxX + boxW - offW - 12;
            int offY = boxY + (int) (boxH * 0.62F) - 4;
            graphics.text(font, offLabel, offX, offY, 0x4494A3B8, false);
        }
    }

    private static DisplayMode cycleMode(DisplayMode current) {
        DisplayMode[] modes = DisplayMode.values();
        return modes[(current.ordinal() + 1) % modes.length];
    }

    private static AestheticStyle cycleAesthetic(AestheticStyle current) {
        AestheticStyle[] styles = AestheticStyle.values();
        return styles[(current.ordinal() + 1) % styles.length];
    }

    private static ColorblindMode cycleColorblind(ColorblindMode current) {
        ColorblindMode[] modes = ColorblindMode.values();
        return modes[(current.ordinal() + 1) % modes.length];
    }

    /**
     * Draggable slider widget supporting smooth mouse dragging and instant value callback.
     */
    private static class DraggableSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final Component labelPrefix;
        private final String unitFormat;
        private final java.util.function.DoubleConsumer onValueChange;

        public DraggableSlider(int x, int y, int width, int height, Component labelPrefix,
                               double current, double min, double max, String unitFormat,
                               java.util.function.DoubleConsumer onValueChange) {
            super(x, y, width, height, Component.empty(), (Math.clamp(current, min, max) - min) / (max - min));
            this.min = min;
            this.max = max;
            this.labelPrefix = labelPrefix;
            this.unitFormat = unitFormat;
            this.onValueChange = onValueChange;
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            double actual = min + value * (max - min);
            String valStr = String.format(Locale.ROOT, unitFormat, actual);
            setMessage(Component.empty().append(labelPrefix).append(": ").append(valStr));
        }

        @Override
        protected void applyValue() {
            double actual = min + value * (max - min);
            onValueChange.accept(actual);
        }
    }
}
