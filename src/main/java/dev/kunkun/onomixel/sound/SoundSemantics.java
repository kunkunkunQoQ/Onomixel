package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.DisplayMode;

/** Shared semantic classification for admission, prioritization, and nearby-source merging. */
public enum SoundSemantics {
    STEP(2),
    GALLOP(3),
    HEAVY_STEP(7),
    LANDING(6),
    IDLE_VOICE(1),
    VOICE(3),
    ATTACK(8),
    DAMAGE(9),
    INTERACTION(6),
    BREAK(6),
    PICKUP(4),
    AMBIENT(2),
    OTHER(3);

    private static final BoundedLookupCache<String, SoundSemantics> CLASSIFICATION_CACHE =
            new BoundedLookupCache<>(4096, 512);
    private static final String[] INTERACTION_END_TOKENS = {
            "click", "click_on", "click_off", "equip", "unequip", "eat", "drink", "burp",
            "use", "block", "throw", "launch", "place", "insert", "eject", "interact"
    };

    private final int importance;

    SoundSemantics(int importance) {
        this.importance = importance;
    }

    public int importance() {
        return importance;
    }

    public boolean allowed(DisplayMode mode) {
        if (mode == null) mode = DisplayMode.LIGHTWEIGHT;
        if (mode == DisplayMode.LIGHTWEIGHT) return this != STEP;
        if (mode == DisplayMode.ACTION_ONLY) {
            return switch (this) {
                case LANDING, ATTACK, DAMAGE, INTERACTION, BREAK, PICKUP, HEAVY_STEP, OTHER -> true;
                case STEP, GALLOP, IDLE_VOICE, VOICE, AMBIENT -> false;
            };
        }
        return true;
    }

    /** Returns the event-specific merge key; spatial source matching is applied by the pool. */
    public String mergeGroup(String id) {
        if (id == null) return "null";
        if (this == STEP || this == GALLOP || this == HEAVY_STEP) return "step";
        if (this == INTERACTION) {
            String family = interactionFamily(id);
            if (family != null) return family;
        }
        return id;
    }

    public static SoundSemantics classify(String id) {
        if (id == null) return OTHER;
        SoundSemantics cached = CLASSIFICATION_CACHE.get(id);
        if (cached != null) return cached;
        SoundSemantics result = computeClassify(id);
        CLASSIFICATION_CACHE.putIfAbsent(id, result);
        return result;
    }

    private static SoundSemantics computeClassify(String id) {
        int pathStart = pathStart(id);

        // High-signal actions must take precedence over broad mob/voice patterns.
        if (containsToken(id, pathStart, "attack") || containsToken(id, pathStart, "sonic_boom")
                || containsToken(id, pathStart, "sonic_charge") || containsToken(id, pathStart, "explode")
                || pathEndsWith(id, pathStart, ".primed") || containsToken(id, pathStart, "shoot")
                || pathStartsWith(id, pathStart, "entity.") && (endsToken(id, pathStart, "hit") || pathContains(id, pathStart, ".hit_"))
                || pathStartsWith(id, pathStart, "entity.firework_rocket.") && pathContains(id, pathStart, ".blast")) return ATTACK;
        if (endsToken(id, pathStart, "hurt") || pathContains(id, pathStart, ".hurt_") || endsToken(id, pathStart, "death")
                || pathContains(id, pathStart, ".death_") || endsToken(id, pathStart, "damage")) return DAMAGE;
        if (pathEndsWith(id, pathStart, ".trade") || pathEndsWith(id, pathStart, ".open") || pathEndsWith(id, pathStart, ".close")
                || pathEndsWith(id, pathStart, ".toggle") || pathEndsWith(id, pathStart, ".activate") || pathEndsWith(id, pathStart, ".deactivate")
                || endsAny(id, pathStart, INTERACTION_END_TOKENS)
                || containsToken(id, pathStart, "eat") || containsToken(id, pathStart, "drink") || containsToken(id, pathStart, "equip")
                || containsToken(id, pathStart, "unequip") || containsToken(id, pathStart, "use")) {
            return INTERACTION;
        }
        if (pathEndsWith(id, pathStart, ".break") || pathEndsWith(id, pathStart, ".breaks") || pathContains(id, pathStart, ".break_")) return BREAK;
        if (pathEndsWith(id, pathStart, ".pickup") || pathEndsWith(id, pathStart, ".collect")) return PICKUP;
        if (isHeavyThreatStep(id)) return HEAVY_STEP;
        if (matchesGallop(id)) return GALLOP;
        if (matchesStep(id)) return STEP;
        if (matchesLanding(id, pathStart)) return LANDING;
        if (isRainId(id)) return AMBIENT;
        if (pathStartsWith(id, pathStart, "block.") && pathContains(id, pathStart, ".ambient")) return AMBIENT;
        if (pathStartsWith(id, pathStart, "entity.") && (pathEndsWith(id, pathStart, ".ambient") || pathContains(id, pathStart, ".ambient_"))) {
            return IDLE_VOICE;
        }
        // The catalog's non-break block events are mechanical interactions by default.
        if (pathStartsWith(id, pathStart, "block.")) return INTERACTION;
        if (pathStartsWith(id, pathStart, "entity.") && SoundPlacement.isMob(id)) return VOICE;
        if (pathContains(id, pathStart, ".ambient")) return AMBIENT;
        return OTHER;
    }

    public static boolean isStep(String id) {
        SoundSemantics s = classify(id);
        return s == STEP || s == GALLOP || s == HEAVY_STEP;
    }

    public static boolean isLanding(String id) {
        return classify(id) == LANDING;
    }

    public static boolean isHeavyThreatStep(String id) {
        if (id == null) return false;
        return "minecraft:entity.warden.step".equals(id)
                || "minecraft:entity.ravager.step".equals(id)
                || "minecraft:entity.iron_golem.step".equals(id);
    }

    public static boolean matchesGallop(String id) {
        if (id == null) return false;
        return id.endsWith(".gallop_water") || id.endsWith(".gallop")
                || id.contains(".gallop.") || id.endsWith(".dash") || id.contains(".saddle");
    }

    private static boolean matchesStep(String id) {
        if (id == null) return false;
        return id.endsWith(".step") || id.contains(".step_") || id.contains(".step.");
    }

    private static boolean matchesLanding(String id, int pathStart) {
        return pathEndsWith(id, pathStart, ".fall") || pathEndsWith(id, pathStart, "_fall")
                || pathEndsWith(id, pathStart, ".land") || pathEndsWith(id, pathStart, ".landing")
                || pathEndsWith(id, pathStart, ".small_fall");
    }

    private static boolean isRainId(String id) {
        return "minecraft:weather.rain".equals(id) || "minecraft:weather.rain.above".equals(id);
    }

    private static int pathStart(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? 0 : separator + 1;
    }

    private static String path(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }

    private static boolean pathStartsWith(String id, int pathStart, String prefix) {
        return id.regionMatches(pathStart, prefix, 0, prefix.length());
    }

    private static boolean pathEndsWith(String id, int pathStart, String suffix) {
        return id.length() - pathStart >= suffix.length() && id.endsWith(suffix);
    }

    private static boolean pathContains(String id, int pathStart, String sub) {
        return id.indexOf(sub, pathStart) >= 0;
    }

    private static boolean containsToken(String id, int pathStart, String token) {
        int tokenLen = token.length();
        int len = id.length();
        int idx = id.indexOf(token, pathStart);
        while (idx >= 0) {
            if (idx > pathStart) {
                char prev = id.charAt(idx - 1);
                int end = idx + tokenLen;
                if (prev == '.' && (end == len || id.charAt(end) == '.' || id.charAt(end) == '_')) {
                    return true;
                }
                if (prev == '_' && end < len && id.charAt(end) == '_') {
                    return true;
                }
            }
            idx = id.indexOf(token, idx + 1);
        }
        return false;
    }

    private static boolean endsToken(String id, int pathStart, String token) {
        int tokenLen = token.length();
        int sepIdx = id.length() - tokenLen - 1;
        if (sepIdx < pathStart) return false;
        char sep = id.charAt(sepIdx);
        return (sep == '.' || sep == '_') && id.regionMatches(sepIdx + 1, token, 0, tokenLen);
    }

    private static boolean endsAny(String id, int pathStart, String[] tokens) {
        for (String token : tokens) {
            if (endsToken(id, pathStart, token)) return true;
        }
        return false;
    }

    private static String interactionFamily(String id) {
        String path = path(id);
        String[] actions = {".open", ".close"};
        for (String action : actions) {
            if (!path.endsWith(action)) continue;
            String device = path.substring(0, path.length() - action.length());
            if (device.endsWith("chest") || device.endsWith("door") || device.endsWith("trapdoor")
                    || device.endsWith("fence_gate")) {
                return id.substring(0, id.length() - path.length()) + device + ".open_close";
            }
        }
        return null;
    }
}
