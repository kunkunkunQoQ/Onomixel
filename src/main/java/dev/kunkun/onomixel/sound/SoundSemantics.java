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
        String path = path(id);

        // High-signal actions must take precedence over broad mob/voice patterns.
        if (containsToken(path, "attack") || containsToken(path, "sonic_boom")
                || containsToken(path, "sonic_charge") || containsToken(path, "explode")
                || path.endsWith(".primed") || containsToken(path, "shoot")
                || path.startsWith("entity.") && (endsToken(path, "hit") || path.contains(".hit_"))
                || path.startsWith("entity.firework_rocket.") && path.contains(".blast")) return ATTACK;
        if (endsToken(path, "hurt") || path.contains(".hurt_") || endsToken(path, "death")
                || path.contains(".death_") || endsToken(path, "damage")) return DAMAGE;
        if (path.endsWith(".trade") || path.endsWith(".open") || path.endsWith(".close")
                || path.endsWith(".toggle") || path.endsWith(".activate") || path.endsWith(".deactivate")
                || endsAny(path, "click", "click_on", "click_off", "equip", "unequip", "eat", "drink", "burp",
                        "use", "block", "throw", "launch", "place", "insert", "eject", "interact")
                || containsToken(path, "eat") || containsToken(path, "drink") || containsToken(path, "equip")
                || containsToken(path, "unequip") || containsToken(path, "use")) {
            return INTERACTION;
        }
        if (path.endsWith(".break") || path.endsWith(".breaks") || path.contains(".break_")) return BREAK;
        if (path.endsWith(".pickup") || path.endsWith(".collect")) return PICKUP;
        if (isHeavyThreatStep(id)) return HEAVY_STEP;
        if (matchesGallop(id)) return GALLOP;
        if (matchesStep(id)) return STEP;
        if (matchesLanding(path)) return LANDING;
        if (isRainId(id)) return AMBIENT;
        if (path.startsWith("block.") && path.contains(".ambient")) return AMBIENT;
        if (path.startsWith("entity.") && (path.endsWith(".ambient") || path.contains(".ambient_"))) {
            return IDLE_VOICE;
        }
        // The catalog's non-break block events are mechanical interactions by default.
        if (path.startsWith("block.")) return INTERACTION;
        if (path.startsWith("entity.") && SoundPlacement.isMob(id)) return VOICE;
        if (path.contains(".ambient")) return AMBIENT;
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

    private static boolean matchesLanding(String path) {
        return path.endsWith(".fall") || path.endsWith("_fall") || path.endsWith(".land")
                || path.endsWith(".landing") || path.endsWith(".small_fall");
    }

    private static boolean isRainId(String id) {
        return "minecraft:weather.rain".equals(id) || "minecraft:weather.rain.above".equals(id);
    }

    private static String path(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? id : id.substring(separator + 1);
    }

    private static boolean containsToken(String path, String token) {
        return path.endsWith("." + token) || path.contains("." + token + ".")
                || path.contains("." + token + "_") || path.contains("_" + token + "_");
    }

    private static boolean endsToken(String path, String token) {
        return path.endsWith("." + token) || path.endsWith("_" + token);
    }

    private static boolean endsAny(String path, String... tokens) {
        for (String token : tokens) if (endsToken(path, token)) return true;
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
