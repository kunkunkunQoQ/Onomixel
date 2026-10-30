package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.VisualType;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable client-side admission, source and appearance contract for one sound event. */
public record SoundDefinition(String soundId, String translationKey, SoundSemantics semantics,
                              Source source, VisualType visualType) {
    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9/._-]+");

    public enum SourceKind { DEFAULT, POINT, BLOCK, ENTITY, GROUND, RAIN }

    public record Source(SourceKind kind, double height, double width) {
        public static final Source DEFAULT = new Source(SourceKind.DEFAULT, 0, 0);
        public static final Source POINT = new Source(SourceKind.POINT, 0, 0.28);
        public static final Source BLOCK = new Source(SourceKind.BLOCK, 0, 1);
        public static final Source GROUND = new Source(SourceKind.GROUND, 0, 0.36);
        public static final Source RAIN = new Source(SourceKind.RAIN, 0, 1);

        public Source {
            Objects.requireNonNull(kind, "source kind");
            if (!Double.isFinite(height) || !Double.isFinite(width) || height < 0 || width < 0
                    || height > 32 || width > 32) throw new IllegalArgumentException("Invalid source dimensions");
            if (kind == SourceKind.ENTITY && (height <= 0 || width <= 0))
                throw new IllegalArgumentException("Entity sources need positive height and width");
        }

        public static Source entity(double height, double width) {
            return new Source(SourceKind.ENTITY, height, width);
        }
    }

    public SoundDefinition {
        if (soundId == null || !ID.matcher(soundId).matches()) throw new IllegalArgumentException("Invalid sound ID");
        if (translationKey == null || translationKey.isBlank()) throw new IllegalArgumentException("Missing onomatopoeia key");
        Objects.requireNonNull(semantics, "semantics");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(visualType, "visualType");
        if (visualType.id() == null || !ID.matcher(visualType.id()).matches())
            throw new IllegalArgumentException("Invalid visual type ID");
        Objects.requireNonNull(visualType.motionEnvelope(), "motionEnvelope");
    }

    public String mergeGroup() { return semantics.mergeGroup(soundId); }
    public int importance() { return semantics.importance(); }
}
