package dev.kunkun.onomixel.render;

import dev.kunkun.onomixel.subtitle.KineticTypography;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded shared metrics/text cache; language and resource reloads invalidate both together. */
final class CaptionTextCache {
    private static final int MAX_ENTRIES = 256;

    record GlyphDraw(FormattedCharSequence text, KineticTypography.AnimatedGlyph motion) {}
    record GlyphMetric(FormattedCharSequence text, KineticTypography.GlyphInput input) {}
    record Text(FormattedCharSequence text, float halfWidth, float halfHeight,
                List<GlyphMetric> sourceGlyphs, List<KineticTypography.GlyphInput> kineticInputs,
                List<GlyphDraw> glyphs, int textHash, float glyphHalfHeight) {
        Text {
            sourceGlyphs = sourceGlyphs instanceof java.util.RandomAccess ? Collections.unmodifiableList(sourceGlyphs) : List.copyOf(sourceGlyphs);
            kineticInputs = kineticInputs instanceof java.util.RandomAccess ? Collections.unmodifiableList(kineticInputs) : List.copyOf(kineticInputs);
            glyphs = glyphs instanceof java.util.RandomAccess ? Collections.unmodifiableList(glyphs) : List.copyOf(glyphs);
        }
    }

    private static final class CacheKey {
        private String translationKey;
        private String prefix;
        private String suffix;
        private boolean bold;
        private boolean italic;
        private int hash;

        CacheKey() {}

        CacheKey(String translationKey, String prefix, String suffix, boolean bold, boolean italic, int hash) {
            this.translationKey = translationKey;
            this.prefix = prefix;
            this.suffix = suffix;
            this.bold = bold;
            this.italic = italic;
            this.hash = hash;
        }

        void set(String translationKey, String prefix, String suffix, boolean bold, boolean italic) {
            this.translationKey = translationKey;
            this.prefix = prefix;
            this.suffix = suffix;
            this.bold = bold;
            this.italic = italic;
            int h = Objects.hashCode(translationKey);
            h = 31 * h + Objects.hashCode(prefix);
            h = 31 * h + Objects.hashCode(suffix);
            h = 31 * h + (bold ? 1231 : 1237);
            h = 31 * h + (italic ? 1231 : 1237);
            this.hash = h;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CacheKey other)) return false;
            return bold == other.bold
                    && italic == other.italic
                    && hash == other.hash
                    && Objects.equals(translationKey, other.translationKey)
                    && Objects.equals(prefix, other.prefix)
                    && Objects.equals(suffix, other.suffix);
        }
    }

    private final Map<CacheKey, Text> entries = new LinkedHashMap<>(MAX_ENTRIES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, Text> eldest) {
            return size() > MAX_ENTRIES;
        }
    };
    private final CacheKey lookupProbe = new CacheKey();
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
        return get(minecraft, translationKey, bold, italic, prefix, null);
    }

    Text get(Minecraft minecraft, String translationKey, boolean bold, boolean italic, String prefix, String suffix) {
        long currentGeneration = generation.get();
        if (cachedGeneration != currentGeneration || language != Language.getInstance()) {
            entries.clear();
            language = Language.getInstance();
            cachedGeneration = currentGeneration;
        }
        String normPrefix = (prefix != null && !prefix.isEmpty()) ? prefix : null;
        String normSuffix = (suffix != null && !suffix.isEmpty()) ? suffix : null;
        lookupProbe.set(translationKey, normPrefix, normSuffix, bold, italic);
        Text cached = entries.get(lookupProbe);
        if (cached != null) return cached;
        Component baseText = translationKey.startsWith("literal:")
                ? Component.literal(translationKey.substring(8))
                : Component.translatable(translationKey);
        Component component = normPrefix != null
                ? Component.literal(normPrefix).append(baseText)
                : baseText;
        if (normSuffix != null) {
            component = component.copy().append(normSuffix);
        }
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
        entries.put(new CacheKey(translationKey, normPrefix, normSuffix, bold, italic, lookupProbe.hash), value);
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
