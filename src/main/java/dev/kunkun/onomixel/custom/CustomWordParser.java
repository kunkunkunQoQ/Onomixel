package dev.kunkun.onomixel.custom;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Pure JSON parser for user and pack onomatopoeia customizations.
 * Zero Minecraft runtime dependencies for pure JVM testability.
 */
public final class CustomWordParser {

    private CustomWordParser() {}

    /**
     * Parses a JSON string containing custom onomatopoeia definitions into a list of rules.
     */
    public static List<CustomWordRule> parse(String jsonString) {
        if (jsonString == null || jsonString.isBlank()) {
            return List.of();
        }
        try {
            JsonElement rootElem = JsonParser.parseString(jsonString);
            if (!rootElem.isJsonObject()) return List.of();
            JsonObject root = rootElem.getAsJsonObject();

            int basePriority = root.has("priority") && root.get("priority").isJsonPrimitive()
                    ? root.get("priority").getAsInt() : 100;

            if (!root.has("overrides") || !root.get("overrides").isJsonArray()) {
                return List.of();
            }

            JsonArray array = root.getAsJsonArray("overrides");
            List<CustomWordRule> rules = new ArrayList<>(array.size());

            for (JsonElement element : array) {
                if (!element.isJsonObject()) continue;
                JsonObject obj = element.getAsJsonObject();
                CustomWordRule rule = parseSingleRule(obj, basePriority);
                if (rule != null) {
                    rules.add(rule);
                }
            }
            return List.copyOf(rules);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static CustomWordRule parseSingleRule(JsonObject obj, int basePriority) {
        String exactId = null;
        Pattern pattern = null;
        String patternString = null;

        if (obj.has("match_regex")) {
            patternString = obj.get("match_regex").getAsString().trim();
            try {
                pattern = Pattern.compile(patternString);
            } catch (PatternSyntaxException e) {
                return null; // Invalid regex
            }
        } else if (obj.has("match")) {
            String rawMatch = obj.get("match").getAsString().trim();
            if (rawMatch.contains("*") || rawMatch.contains("?")) {
                patternString = rawMatch;
                String regex = convertWildcardToRegex(rawMatch);
                try {
                    pattern = Pattern.compile(regex);
                } catch (PatternSyntaxException e) {
                    return null;
                }
            } else {
                exactId = rawMatch;
            }
        } else if (obj.has("sound_id")) {
            exactId = obj.get("sound_id").getAsString().trim();
        } else {
            return null; // No match key defined
        }

        int priority = obj.has("priority") && obj.get("priority").isJsonPrimitive()
                ? obj.get("priority").getAsInt() : basePriority;

        DisplayStrategy display = parseDisplayStrategy(obj);

        // Parse style block or top-level shortcuts
        JsonObject styleObj = obj.has("style") && obj.get("style").isJsonObject()
                ? obj.getAsJsonObject("style") : obj;

        String visualTypeId = styleObj.has("visual_type") ? styleObj.get("visual_type").getAsString().trim() : null;
        String kineticMotion = styleObj.has("kinetic_motion") ? styleObj.get("kinetic_motion").getAsString().trim().toUpperCase(Locale.ROOT) : null;

        Integer vibrant = parseColor(styleObj, "vibrant_color");
        if (vibrant == null) vibrant = parseColor(styleObj, "color");
        Integer pastel = parseColor(styleObj, "pastel_color");

        Boolean bold = styleObj.has("bold") ? styleObj.get("bold").getAsBoolean() : null;
        Boolean italic = styleObj.has("italic") ? styleObj.get("italic").getAsBoolean() : null;
        Float scaleMultiplier = styleObj.has("scale_multiplier") ? styleObj.get("scale_multiplier").getAsFloat() : null;

        // Parse physics block
        JsonObject physObj = obj.has("physics") && obj.get("physics").isJsonObject()
                ? obj.getAsJsonObject("physics") : obj;

        Float durationMultiplier = physObj.has("duration_multiplier") ? physObj.get("duration_multiplier").getAsFloat() : null;
        Double heightOffset = physObj.has("height_offset") ? physObj.get("height_offset").getAsDouble() : null;

        return new CustomWordRule(
                exactId, pattern, patternString, display, visualTypeId, kineticMotion,
                vibrant, pastel, bold, italic, scaleMultiplier, durationMultiplier, heightOffset, priority
        );
    }

    private static DisplayStrategy parseDisplayStrategy(JsonObject obj) {
        if (obj.has("display") && obj.get("display").isJsonObject()) {
            JsonObject disp = obj.getAsJsonObject("display");
            String mode = disp.has("mode") ? disp.get("mode").getAsString().toUpperCase(Locale.ROOT) : "SINGLE";

            switch (mode) {
                case "WEIGHTED_RANDOM" -> {
                    if (disp.has("variants") && disp.get("variants").isJsonArray()) {
                        JsonArray varArray = disp.getAsJsonArray("variants");
                        List<DisplayStrategy.Variant> variants = new ArrayList<>();
                        for (JsonElement vElem : varArray) {
                            if (!vElem.isJsonObject()) continue;
                            JsonObject vObj = vElem.getAsJsonObject();
                            if (!vObj.has("text")) continue;
                            String text = vObj.get("text").getAsString();
                            int weight = vObj.has("weight") ? Math.max(1, vObj.get("weight").getAsInt()) : 1;
                            variants.add(new DisplayStrategy.Variant(text, weight));
                        }
                        if (!variants.isEmpty()) {
                            return new DisplayStrategy.WeightedRandom(variants);
                        }
                    }
                }
                case "TRANSLATABLE" -> {
                    if (disp.has("key")) {
                        return new DisplayStrategy.Translatable(disp.get("key").getAsString());
                    }
                }
                default -> { // SINGLE or fallback
                    if (disp.has("text")) {
                        return new DisplayStrategy.SingleText(disp.get("text").getAsString());
                    }
                }
            }
        }

        // Direct shortcuts (if no structured display block)
        if (obj.has("text")) {
            return new DisplayStrategy.SingleText(obj.get("text").getAsString());
        }
        if (obj.has("translation_key")) {
            return new DisplayStrategy.Translatable(obj.get("translation_key").getAsString());
        }

        return null; // Preserve default onomatopoeia key
    }

    private static String convertWildcardToRegex(String wildcard) {
        if (wildcard == null) return "^.*$";
        String safeWildcard = wildcard.length() > 512 ? wildcard.substring(0, 512) : wildcard;

        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < safeWildcard.length(); i++) {
            char c = safeWildcard.charAt(i);
            switch (c) {
                case '*' -> {
                    // Collapse consecutive wildcards (e.g. "***") into single ".*" to eliminate ReDoS risk
                    while (i + 1 < safeWildcard.length() && safeWildcard.charAt(i + 1) == '*') {
                        i++;
                    }
                    sb.append(".*");
                }
                case '?' -> sb.append(".");
                case '.', '(', ')', '[', ']', '{', '}', '+', '^', '$', '|', '\\' -> {
                    sb.append('\\').append(c);
                }
                default -> sb.append(c);
            }
        }
        sb.append("$");
        return sb.toString();
    }

    private static Integer parseColor(JsonObject obj, String memberName) {
        if (!obj.has(memberName)) return null;
        try {
            JsonElement elem = obj.get(memberName);
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                return elem.getAsInt() & 0xFFFFFF;
            }
            String val = elem.getAsString().trim();
            if (val.startsWith("#")) val = val.substring(1);
            else if (val.startsWith("0x") || val.startsWith("0X")) val = val.substring(2);
            return (int) Long.parseLong(val, 16) & 0xFFFFFF;
        } catch (Exception ignored) {
            return null;
        }
    }
}
