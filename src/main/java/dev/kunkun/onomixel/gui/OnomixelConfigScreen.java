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
import net.minecraft.client.gui.components.Tooltip;
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
    private boolean draftSpatialWords;
    private boolean draftRadar;
    private boolean draftKinetic;
    private boolean draftSpeedOfSound;
    private double draftScale;
    private int draftDistance;
    private double draftOpacity;
    private double draftDuration;
    private double draftRadarMergeRange;

    // Zero-allocation cached preview text holders
    private String cachedRadarText;
    private int cachedRadarTextW = -1;
    private String cachedOffLabel;
    private int cachedOffW = -1;
    private String cachedWordsOffLabel;
    private int cachedWordsOffW = -1;
    private String cachedTitle;
    private int cachedTitleW = -1;
    private String cachedSandboxLabel;
    private int cachedActiveRulesCount = -1;
    private String cachedActiveRulesStr;
    private int cachedActiveRulesW = -1;
    private String cachedSampleWord;
    private int cachedSampleWordW = -1;
    private AestheticStyle cachedSampleAesthetic;

    public OnomixelConfigScreen(Screen parent) {
        super(Component.translatable("onomixel.config.screen.title"));
        this.parent = parent;
        this.draftEnabled = OnomixelConfig.ENABLED.get();
        this.draftMode = OnomixelConfig.DISPLAY_MODE.get();
        this.draftColorStyle = OnomixelConfig.COLOR_STYLE.get();
        this.draftAesthetic = OnomixelConfig.AESTHETIC_STYLE.get();
        this.draftColorblind = OnomixelConfig.COLORBLIND_MODE.get();
        this.draftSpatialWords = OnomixelConfig.SPATIAL_WORDS.get();
        this.draftRadar = OnomixelConfig.THREAT_RADAR.get();
        this.draftKinetic = OnomixelConfig.KINETIC_TYPOGRAPHY.get();
        this.draftSpeedOfSound = OnomixelConfig.SPEED_OF_SOUND.get();
        this.draftScale = OnomixelConfig.TEXT_SCALE.get();
        this.draftDistance = OnomixelConfig.MAX_DISTANCE.get();
        this.draftOpacity = OnomixelConfig.OPACITY.get();
        this.draftDuration = OnomixelConfig.DURATION_SECONDS.get();
        this.draftRadarMergeRange = OnomixelConfig.RADAR_MERGE_RANGE.get();
    }

    private int getStartY() {
        if (height < 265) return 20;
        return height < 310 ? 24 : 34;
    }

    private int getRowGap() {
        return height < 265 ? 22 : 24;
    }

    private int getButtonHeight() {
        return height < 265 ? 19 : 20;
    }

    private int getGridColsGap() {
        return width >= 470 ? 8 : 5;
    }

    private int getColWidth() {
        int colGap = getGridColsGap();
        return Math.clamp((width - 20 - colGap * 2) / 3, 120, 156);
    }

    private int getTotalGridWidth() {
        return getColWidth() * 3 + getGridColsGap() * 2;
    }

    private int getBoxY() {
        return getStartY() + getRowGap() * 4 + getButtonHeight() + (height < 265 ? 6 : 8);
    }

    private int getBoxH() {
        if (height < 265) return 44;
        return height < 310 ? 52 : 66;
    }

    @Override
    protected void init() {
        cachedRadarText = null;
        cachedRadarTextW = -1;
        cachedOffLabel = null;
        cachedOffW = -1;
        cachedWordsOffLabel = null;
        cachedWordsOffW = -1;
        cachedTitle = null;
        cachedTitleW = -1;
        cachedSandboxLabel = null;
        cachedActiveRulesCount = -1;
        cachedActiveRulesStr = null;
        cachedActiveRulesW = -1;
        cachedSampleWord = null;
        cachedSampleWordW = -1;
        cachedSampleAesthetic = null;

        int colW = getColWidth();
        int colGap = getGridColsGap();
        int totalW = getTotalGridWidth();
        int gridLeft = (width - totalW) / 2;
        int col0 = gridLeft;
        int col1 = gridLeft + colW + colGap;
        int col2 = gridLeft + (colW + colGap) * 2;
        int startY = getStartY();
        int rowGap = getRowGap();
        int btnH = getButtonHeight();

        // Column 0: Core Switches & Acoustic Features (5 rows)
        addRenderableWidget(Button.builder(
                getMasterButtonText(),
                btn -> {
                    draftEnabled = !draftEnabled;
                    btn.setMessage(getMasterButtonText());
                }
        ).bounds(col0, startY, colW, btnH).build());

        addRenderableWidget(Button.builder(
                getModeButtonText(),
                btn -> {
                    draftMode = cycleMode(draftMode);
                    btn.setMessage(getModeButtonText());
                }
        ).bounds(col0, startY + rowGap, colW, btnH).build());

        int halfGap = 4;
        int leftHalfW = (colW - halfGap) / 2;
        int rightHalfW = colW - halfGap - leftHalfW;
        addRenderableWidget(Button.builder(
                getSpatialWordsButtonText(),
                btn -> {
                    draftSpatialWords = !draftSpatialWords;
                    btn.setMessage(getSpatialWordsButtonText());
                }
        ).tooltip(Tooltip.create(Component.translatable("onomixel.config.spatialWords.tooltip")))
        .bounds(col0, startY + rowGap * 2, leftHalfW, btnH).build());

        addRenderableWidget(Button.builder(
                getRadarButtonText(),
                btn -> {
                    draftRadar = !draftRadar;
                    btn.setMessage(getRadarButtonText());
                }
        ).tooltip(Tooltip.create(Component.translatable("onomixel.config.threatRadar.tooltip")))
        .bounds(col0 + leftHalfW + halfGap, startY + rowGap * 2, rightHalfW, btnH).build());

        addRenderableWidget(Button.builder(
                getKineticButtonText(),
                btn -> {
                    draftKinetic = !draftKinetic;
                    btn.setMessage(getKineticButtonText());
                }
        ).bounds(col0, startY + rowGap * 3, colW, btnH).build());

        addRenderableWidget(Button.builder(
                getSpeedOfSoundButtonText(),
                btn -> {
                    draftSpeedOfSound = !draftSpeedOfSound;
                    btn.setMessage(getSpeedOfSoundButtonText());
                }
        ).tooltip(Tooltip.create(Component.translatable("onomixel.config.speedOfSound.tooltip")))
        .bounds(col0, startY + rowGap * 4, colW, btnH).build());

        // Column 1: Visual Aesthetics & Custom Words Pack Actions (5 rows)
        addRenderableWidget(Button.builder(
                getAestheticButtonText(),
                btn -> {
                    draftAesthetic = cycleAesthetic(draftAesthetic);
                    cachedSampleWord = null;
                    btn.setMessage(getAestheticButtonText());
                }
        ).bounds(col1, startY, colW, btnH).build());

        addRenderableWidget(Button.builder(
                getColorStyleButtonText(),
                btn -> {
                    draftColorStyle = draftColorStyle == ColorStyle.VIBRANT ? ColorStyle.PASTEL : ColorStyle.VIBRANT;
                    btn.setMessage(getColorStyleButtonText());
                }
        ).bounds(col1, startY + rowGap, colW, btnH).build());

        addRenderableWidget(Button.builder(
                getColorblindButtonText(),
                btn -> {
                    draftColorblind = cycleColorblind(draftColorblind);
                    btn.setMessage(getColorblindButtonText());
                }
        ).bounds(col1, startY + rowGap * 2, colW, btnH).build());

        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.reload_custom_words"),
                btn -> {
                    SoundOverrideLoader.reloadAll();
                    cachedSampleWord = null;
                    cachedActiveRulesCount = -1;
                }
        ).bounds(col1, startY + rowGap * 3, colW, btnH).build());

        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.open_custom_folder"),
                btn -> SoundOverrideLoader.openConfigFolder()
        ).bounds(col1, startY + rowGap * 4, colW, btnH).build());

        // Column 2: Draggable Sliders (5 rows)
        addRenderableWidget(new DraggableSlider(
                col2, startY, colW, btnH,
                Component.translatable("onomixel.config.screen.text_scale"),
                draftScale, 0.50, 2.00, 0.05, "%.2fx",
                val -> draftScale = Math.round(val * 100.0) / 100.0
        ));

        addRenderableWidget(new DraggableSlider(
                col2, startY + rowGap, colW, btnH,
                Component.translatable("onomixel.config.screen.max_distance"),
                draftDistance, 8.0, 48.0, 1.0, "%.0fm",
                val -> draftDistance = (int) Math.round(val)
        ));

        addRenderableWidget(new DraggableSlider(
                col2, startY + rowGap * 2, colW, btnH,
                Component.translatable("onomixel.config.screen.opacity"),
                draftOpacity * 100.0, 20.0, 100.0, 5.0, "%.0f%%",
                val -> draftOpacity = Math.round(val) / 100.0
        ));

        addRenderableWidget(new DraggableSlider(
                col2, startY + rowGap * 3, colW, btnH,
                Component.translatable("onomixel.config.screen.duration"),
                draftDuration, 0.5, 3.0, 0.1, "%.1fs",
                val -> draftDuration = Math.round(val * 10.0) / 10.0
        ));

        DraggableSlider mergeSlider = new DraggableSlider(
                col2, startY + rowGap * 4, colW, btnH,
                Component.translatable("onomixel.config.screen.radar_merge_range"),
                draftRadarMergeRange, 0.0, 24.0, 0.5, "%.1fm",
                Component.translatable("onomixel.config.screen.merge_off"),
                val -> draftRadarMergeRange = Math.round(val * 2.0) / 2.0
        );
        mergeSlider.setTooltip(Tooltip.create(Component.translatable("onomixel.config.radarMergeRange.tooltip")));
        addRenderableWidget(mergeSlider);

        // Bottom Action Bar
        int bottomY = height - (height < 265 ? 24 : 28);
        int actionW = (totalW - colGap) / 2;
        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.save"),
                btn -> saveAndClose()
        ).bounds(gridLeft, bottomY, actionW, 20).build());

        addRenderableWidget(Button.builder(
                Component.translatable("onomixel.config.screen.cancel"),
                btn -> onClose()
        ).bounds(gridLeft + actionW + colGap, bottomY, totalW - actionW - colGap, 20).build());
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

    private Component getSpatialWordsButtonText() {
        return Component.translatable("onomixel.config.screen.spatial_words_btn")
                .append(": ")
                .append(draftSpatialWords ? Component.translatable("onomixel.config.screen.on") : Component.translatable("onomixel.config.screen.off"));
    }

    private Component getRadarButtonText() {
        return Component.translatable("onomixel.config.screen.threat_radar_btn")
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
        OnomixelConfig.SPATIAL_WORDS.set(draftSpatialWords);
        OnomixelConfig.THREAT_RADAR.set(draftRadar);
        OnomixelConfig.KINETIC_TYPOGRAPHY.set(draftKinetic);
        OnomixelConfig.SPEED_OF_SOUND.set(draftSpeedOfSound);
        OnomixelConfig.TEXT_SCALE.set(draftScale);
        OnomixelConfig.MAX_DISTANCE.set(draftDistance);
        OnomixelConfig.OPACITY.set(draftOpacity);
        OnomixelConfig.DURATION_SECONDS.set(draftDuration);
        OnomixelConfig.RADAR_MERGE_RANGE.set(draftRadarMergeRange);
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

        // Header Title (Zero-allocation cached label)
        if (cachedTitle == null) {
            cachedTitle = Component.translatable("onomixel.config.screen.title").getString();
            cachedTitleW = font.width(cachedTitle);
        }
        graphics.text(font, cachedTitle, width / 2 - cachedTitleW / 2, height < 290 ? 8 : 12, 0xFFFFFFFF, true);

        // Live Dynamic Preview Sandbox Box matching full 3-column grid width
        int boxW = getTotalGridWidth();
        int boxH = getBoxH();
        int boxX = (width - boxW) / 2;
        int boxY = getBoxY();

        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, 0xAA090D16);
        graphics.fill(boxX, boxY, boxX + boxW, boxY + 1, 0x8838BDF8);
        graphics.fill(boxX, boxY + boxH - 1, boxX + boxW, boxY + boxH, 0x8838BDF8);
        graphics.fill(boxX, boxY, boxX + 1, boxY + boxH, 0x8838BDF8);
        graphics.fill(boxX + boxW - 1, boxY, boxX + boxW, boxY + boxH, 0x8838BDF8);

        if (cachedSandboxLabel == null) {
            cachedSandboxLabel = Component.translatable("onomixel.config.screen.sandbox_title").getString();
        }
        graphics.text(font, cachedSandboxLabel, boxX + 8, boxY + 3, 0xFF94A3B8, false);

        // Render Active Custom Rules Count in the top-right of the sandbox box
        int activeRules = SoundCustomizationManager.activeRuleCount();
        if (cachedActiveRulesCount != activeRules || cachedActiveRulesStr == null) {
            cachedActiveRulesCount = activeRules;
            cachedActiveRulesStr = Component.translatable("onomixel.config.screen.active_rules", activeRules).getString();
            cachedActiveRulesW = font.width(cachedActiveRulesStr);
        }
        int activeColor = activeRules > 0 ? 0xFF38BDF8 : 0xFF94A3B8;
        graphics.text(font, cachedActiveRulesStr, boxX + boxW - cachedActiveRulesW - 8, boxY + 3, activeColor, false);

        // Compute simulated motion clock at 60 FPS
        double age = (System.currentTimeMillis() % 1600) / 1000.0;

        // Position central comic text: if threat radar is active in sandbox or words off, offset to left (0.38F) so both fit comfortably
        float textCenterX = (draftRadar || !draftSpatialWords) ? boxX + (boxW * 0.38F) : boxX + (boxW / 2.0F);

        if (draftSpatialWords) {
            // Real-time custom word introspection: prioritize attack.crit or sample first active custom rule
            String previewSound = "minecraft:entity.player.attack.crit";
            CustomWordRule customRule = SoundCustomizationManager.resolve(previewSound);
            if (customRule == null && SoundCustomizationManager.activeRuleCount() > 0) {
                customRule = SoundCustomizationManager.getFirstActiveRule();
                if (customRule != null && customRule.exactId() != null) {
                    previewSound = customRule.exactId();
                }
            }

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

            // Resolve sample display text (Zero-allocation cached label)
            if (cachedSampleWord == null || cachedSampleAesthetic != draftAesthetic) {
                cachedSampleAesthetic = draftAesthetic;
                String word;
                if (customRule != null && customRule.display() != null) {
                    String resolved = customRule.display().resolve(42L, previewSound);
                    if (customRule.display().isLiteral()) {
                        word = resolved;
                    } else {
                        word = Component.translatable(resolved).getString();
                    }
                } else {
                    word = Component.translatable("onomixel.config.screen.sandbox_sample").getString();
                }

                if (draftAesthetic == AestheticStyle.MEDIEVAL) {
                    word = "✦ " + word + " ✦";
                }
                cachedSampleWord = word;
                cachedSampleWordW = font.width(word);
            }
            String sampleWord = cachedSampleWord;
            int sampleWidth = cachedSampleWordW;

            int alpha = (int) Math.round(draftOpacity * 255);
            int finalRgbWithAlpha = (alpha << 24) | (finalColor & 0x00FFFFFF);

            float scaleMultiplier = (customRule != null && customRule.scaleMultiplier() != null) ? customRule.scaleMultiplier() : 1.0F;

            Matrix3x2fStack pose = graphics.pose();
            pose.pushMatrix();
            pose.translate(textCenterX, boxY + (boxH * 0.62F));
            pose.rotate((float) Math.toRadians(styled.tiltDegrees()));
            float s = (float) (styled.scale() * draftScale * 0.9F * scaleMultiplier);
            pose.scale(s * styled.stretchX(), s * styled.stretchY());
            graphics.text(font, sampleWord, -sampleWidth / 2, -4, finalRgbWithAlpha, true);
            pose.popMatrix();
        } else {
            // Standby indicator when spatial words are turned off (Zero-allocation cached label)
            if (cachedWordsOffLabel == null) {
                cachedWordsOffLabel = Component.translatable("onomixel.config.screen.words_off_standby").getString();
                cachedWordsOffW = font.width(cachedWordsOffLabel);
            }
            int offX = (int) (textCenterX - cachedWordsOffW / 2.0F);
            int offY = boxY + (int) (boxH * 0.62F) - 4;
            graphics.text(font, cachedWordsOffLabel, offX, offY, 0x4494A3B8, false);
        }

        // Real-time Tactical Radar Capsule Preview inside the sandbox
        if (draftRadar) {
            if (cachedRadarText == null) {
                String threatKey = ThreatRegistry.resolveThreatNameKey("minecraft:entity.creeper.primed");
                String threatName = Component.translatable(threatKey).getString();
                cachedRadarText = "⚠ " + threatName + " 3.2m";
                cachedRadarTextW = font.width(cachedRadarText);
            }
            String radarText = cachedRadarText;
            int radarTextW = cachedRadarTextW;
            int badgeHalfW = radarTextW / 2 + 5;
            int badgeHalfH = 7;

            int badgeCenterX = boxX + boxW - badgeHalfW - 16;
            int badgeCenterY = boxY + (int) (boxH * 0.62F);

            // 4Hz tactical pulse: sin(age * 4 * 2 * PI) = sin(age * 8 * PI), synchronized with ThreatRadarCalculator [0.50, 1.00]
            float pulse = (float) (0.75 + 0.25 * Math.sin(age * 8.0 * Math.PI));
            int baseBorderRgb = CaptionVisuals.applyColorblindFilter(0xEF4444, draftColorblind);

            // Independent threat radar lifecycle for creeper primed: 1500ms (1.5s)
            // age in [0.0, 1.6s]: active for 1.5s, smooth fadeout from 1.3s to 1.5s
            float lifecycleAlpha = 1.0F;
            if (age > 1.3) {
                lifecycleAlpha = (float) Math.clamp((1.5 - age) / 0.2, 0.0, 1.0);
            }

            float effectiveAlpha = (float) Math.clamp(draftOpacity, 0.2, 1.0) * lifecycleAlpha;
            int borderAlpha = (int) (pulse * 255 * effectiveAlpha);
            int borderColor = (borderAlpha << 24) | (baseBorderRgb & 0xFFFFFF);

            int bgAlpha = (int) (0xEE * effectiveAlpha);
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

            // 3. Directional pointer chevron pointing right with 0.8px micro-motion
            int chevronOffset = (int) (Math.sin(age * 8.0 * Math.PI) * 0.8F);
            graphics.text(font, "▶", badgeCenterX + badgeHalfW + 3 + chevronOffset, badgeCenterY - 4, borderColor, true);

            // 4. Localized text label
            graphics.text(font, radarText, badgeCenterX - radarTextW / 2, badgeCenterY - 4, textColor, true);
        } else {
            // Standby indicator when radar is turned off
            if (cachedOffLabel == null) {
                cachedOffLabel = Component.translatable("onomixel.config.screen.radar_off_standby").getString();
                cachedOffW = font.width(cachedOffLabel);
            }
            int offX = boxX + boxW - cachedOffW - 12;
            int offY = boxY + (int) (boxH * 0.62F) - 4;
            graphics.text(font, cachedOffLabel, offX, offY, 0x4494A3B8, false);
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
     * Draggable slider widget supporting smooth mouse dragging, step snapping, and instant value callback.
     */
    private static class DraggableSlider extends AbstractSliderButton {
        private final double min;
        private final double max;
        private final double step;
        private final Component labelPrefix;
        private final String unitFormat;
        private final Component zeroOverride;
        private final java.util.function.DoubleConsumer onValueChange;

        public DraggableSlider(int x, int y, int width, int height, Component labelPrefix,
                               double current, double min, double max, double step, String unitFormat,
                               java.util.function.DoubleConsumer onValueChange) {
            this(x, y, width, height, labelPrefix, current, min, max, step, unitFormat, null, onValueChange);
        }

        public DraggableSlider(int x, int y, int width, int height, Component labelPrefix,
                               double current, double min, double max, double step, String unitFormat,
                               Component zeroOverride,
                               java.util.function.DoubleConsumer onValueChange) {
            super(x, y, width, height, Component.empty(), (Math.clamp(current, min, max) - min) / (max - min));
            this.min = min;
            this.max = max;
            this.step = step > 0 ? step : 1.0;
            this.labelPrefix = labelPrefix;
            this.unitFormat = unitFormat;
            this.zeroOverride = zeroOverride;
            this.onValueChange = onValueChange;
            this.updateMessage();
        }

        private double snappedValue() {
            double snapped = min + Math.round((value * (max - min)) / step) * step;
            return Math.clamp(snapped, min, max);
        }

        @Override
        protected void updateMessage() {
            double actual = snappedValue();
            if (zeroOverride != null && actual <= 1e-6) {
                setMessage(Component.empty().append(labelPrefix).append(": ").append(zeroOverride));
                return;
            }
            String valStr = String.format(Locale.ROOT, unitFormat, actual);
            setMessage(Component.empty().append(labelPrefix).append(": ").append(valStr));
        }

        @Override
        protected void applyValue() {
            double actual = snappedValue();
            onValueChange.accept(actual);
        }
    }
}
