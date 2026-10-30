package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.subtitle.KineticTypography;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded shared metrics/text cache; language and resource reloads invalidate both together. */
final class CaptionTextCache {
    record GlyphDraw(FormattedCharSequence text, KineticTypography.AnimatedGlyph motion) {}
    record GlyphMetric(FormattedCharSequence text, KineticTypography.GlyphInput input) {}
    record Text(FormattedCharSequence text, float halfWidth, float halfHeight,
                List<GlyphMetric> sourceGlyphs, List<KineticTypography.GlyphInput> kineticInputs,
                List<GlyphDraw> glyphs, int textHash, float glyphHalfHeight) {
        Text {
            sourceGlyphs = List.copyOf(sourceGlyphs);
            kineticInputs = List.copyOf(kineticInputs);
            glyphs = List.copyOf(glyphs);
        }
    }
    private record Key(String translationKey, boolean bold, boolean italic) {}
    private final Map<Key, Text> entries = new LinkedHashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private long cachedGeneration = -1;
    private Language language;

    void invalidate() { generation.incrementAndGet(); }

    Text get(Minecraft minecraft, String translationKey, boolean bold) {
        return get(minecraft, translationKey, bold, false);
    }

    Text get(Minecraft minecraft, String translationKey, boolean bold, boolean italic) {
        return get(minecraft, translationKey, bold, italic, null);
    }

    Text get(Minecraft minecraft, String translationKey, boolean bold, boolean italic, String prefix) {
        long currentGeneration = generation.get();
        if (cachedGeneration != currentGeneration || language != Language.getInstance()) {
            entries.clear();
            language = Language.getInstance();
            cachedGeneration = currentGeneration;
        }
        Key key = new Key(prefix != null ? prefix + translationKey : translationKey, bold, italic);
        Text cached = entries.get(key);
        if (cached != null) return cached;
        Component baseText = translationKey.startsWith("literal:")
                ? Component.literal(translationKey.substring(8))
                : Component.translatable(translationKey);
        Component component = prefix != null
                ? Component.literal(prefix).append(baseText)
                : baseText;
        if (bold && italic) {
            component = component.copy().withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC);
        } else if (bold) {
            component = component.copy().withStyle(ChatFormatting.BOLD);
        } else if (italic) {
            component = component.copy().withStyle(ChatFormatting.ITALIC);
        }
        var text = component.getVisualOrderText();
        List<GlyphMetric> sourceGlyphs = new ArrayList<>();
        List<KineticTypography.GlyphInput> kineticInputs = new ArrayList<>();
        StringBuilder codePoints = new StringBuilder();
        text.accept((position, style, codePoint) -> {
            FormattedCharSequence glyphText = FormattedCharSequence.codepoint(codePoint, style);
            float advance = minecraft.font.getSplitter().stringWidth(glyphText);
            if (!Float.isFinite(advance) || advance < 0.0F) advance = 0.0F;
            var input = new KineticTypography.GlyphInput(codePoint, advance);
            sourceGlyphs.add(new GlyphMetric(glyphText, input));
            kineticInputs.add(input);
            codePoints.appendCodePoint(codePoint);
            return true;
        });
        float width = 0.0F;
        for (KineticTypography.GlyphInput input : kineticInputs) width += input.advance();
        Text value = new Text(text, width / 2.0F, minecraft.font.lineHeight / 2.0F,
                sourceGlyphs, kineticInputs, List.of(), codePoints.toString().hashCode(),
                minecraft.font.lineHeight / 2.0F);
        if (entries.size() == 256) entries.remove(entries.keySet().iterator().next());
        entries.put(key, value);
        return value;
    }

    Text animate(Text base, KineticTypography.Mode mode, double ageSeconds) {
        if (base == null || mode == null || mode == KineticTypography.Mode.NONE
                || base.sourceGlyphs().isEmpty()) return base;

        var layout = KineticTypography.layoutGlyphs(
                base.kineticInputs(), base.textHash(), mode, ageSeconds, base.glyphHalfHeight() * 2.0F, 0xFFFFFFFF);
        List<GlyphDraw> glyphs = new ArrayList<>(layout.glyphs().size());
        for (int i = 0; i < layout.glyphs().size(); i++) {
            glyphs.add(new GlyphDraw(base.sourceGlyphs().get(i).text(), layout.glyphs().get(i)));
        }
        return new Text(base.text(), layout.halfWidth(), layout.halfHeight(), base.sourceGlyphs(),
                base.kineticInputs(), glyphs, base.textHash(), base.glyphHalfHeight());
    }
}
