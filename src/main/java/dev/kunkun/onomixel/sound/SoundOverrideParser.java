package dev.kunkun.onomixel.sound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.kunkun.onomixel.subtitle.VisualType;
import dev.kunkun.onomixel.subtitle.VisualTypeRegistry;
import dev.kunkun.onomixel.subtitle.VisualTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure JSON parser for sound overrides; zero Minecraft dependencies for fast JVM testing.
 */
public final class SoundOverrideParser {
    private SoundOverrideParser() {}

    public record OverrideEntry(
            String soundId,
            String translationKey,
            SoundSemantics semantics,
            String visualTypeId,
            Integer vibrantColor,
            Integer pastelColor
    ) {}

    public static List<OverrideEntry> parse(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) return List.of();
        List<OverrideEntry> result = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(jsonString).getAsJsonObject();
            if (!root.has("overrides") || !root.get("overrides").isJsonArray()) return List.of();
            JsonArray array = root.getAsJsonArray("overrides");
            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                if (!obj.has("sound_id")) continue;
                String soundId = obj.get("sound_id").getAsString();
                String key = obj.has("translation_key")
                        ? obj.get("translation_key").getAsString()
                        : "onomixel.subtitle." + soundId.replace(':', '.');

                SoundSemantics semantics = SoundSemantics.OTHER;
                if (obj.has("semantic")) {
                    try {
                        semantics = SoundSemantics.valueOf(obj.get("semantic").getAsString().toUpperCase(Locale.ROOT));
                    } catch (IllegalArgumentException ignored) {}
                }

                String visualTypeId = obj.has("visual_type") ? obj.get("visual_type").getAsString() : null;
                Integer vibrant = parseColor(obj, "vibrant_color");
                if (vibrant == null) vibrant = parseColor(obj, "color");
                Integer pastel = parseColor(obj, "pastel_color");

                result.add(new OverrideEntry(soundId, key, semantics, visualTypeId, vibrant, pastel));
            }
        } catch (Exception ignored) {}
        return List.copyOf(result);
    }

    private static Integer parseColor(JsonObject obj, String memberName) {
        if (!obj.has(memberName)) return null;
        String val = obj.get(memberName).getAsString().trim();
        if (val.startsWith("#")) val = val.substring(1);
        else if (val.startsWith("0x") || val.startsWith("0X")) val = val.substring(2);
        try {
            return (int) Long.parseLong(val, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static void apply(List<OverrideEntry> entries) {
        for (OverrideEntry entry : entries) {
            VisualType vt = entry.visualTypeId() != null
                    ? VisualTypeRegistry.getType(entry.visualTypeId())
                    : null;
            if (vt == null) vt = VisualTypes.IMPACT;

            SoundDefinition def = new SoundDefinition(
                    entry.soundId(),
                    entry.translationKey(),
                    entry.semantics(),
                    SoundDefinition.Source.DEFAULT,
                    vt
            );
            SoundDefinitions.registerOrOverride(def);

            if (entry.vibrantColor() != null) {
                int pastel = entry.pastelColor() != null ? entry.pastelColor() : entry.vibrantColor();
                VisualTypeRegistry.registerSoundColor(entry.soundId(), entry.vibrantColor(), pastel);
            }
        }
    }
}
