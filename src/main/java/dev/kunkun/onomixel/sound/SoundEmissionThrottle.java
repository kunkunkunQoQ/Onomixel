package dev.kunkun.onomixel.sound;

import dev.kunkun.onomixel.subtitle.CaptionVisuals;
import dev.kunkun.onomixel.subtitle.DisplayMode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Spatial sampling for high-rate ambient/step sources; it never changes audible playback. */
public final class SoundEmissionThrottle {
    private static final int CAPACITY = 256;
    private static final int STEP_HISTORY_CAPACITY = 32;
    private static final long MAX_STEP_HISTORY_AGE = 1_500_000_000L;

    private static final byte KIND_DEFAULT = 0;
    private static final byte KIND_VOICE = 1;
    private static final byte KIND_PISTON = 2;
    private static final byte KIND_DISPENSER = 3;
    private static final byte KIND_MINECART = 4;

    private static final class CellKey {
        byte kind;
        String id;
        int x;
        int y;
        int z;

        CellKey() {}

        CellKey(byte kind, String id, int x, int y, int z) {
            set(kind, id, x, y, z);
        }

        void set(byte kind, String id, int x, int y, int z) {
            this.kind = kind;
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof CellKey other)) return false;
            return kind == other.kind && x == other.x && y == other.y && z == other.z
                    && Objects.equals(id, other.id);
        }

        @Override
        public int hashCode() {
            int h = kind;
            h = 31 * h + Objects.hashCode(id);
            h = 31 * h + x;
            h = 31 * h + y;
            h = 31 * h + z;
            return h;
        }
    }

    private final Map<CellKey, Long> recent = new LinkedHashMap<>();
    private final Deque<StepRecord> recentSteps = new ArrayDeque<>(STEP_HISTORY_CAPACITY);
    private final CellKey lookupProbe = new CellKey();
    private long lastRain = Long.MIN_VALUE / 2;

    private record StepRecord(double x, double y, double z, long time) {}

    public boolean admit(String id, double x, double y, double z, long now) {
        return admit(id, SoundDefinitions.semantics(id), x, y, z, now, CaptionVisuals.displayMode());
    }

    public boolean admit(String id, double x, double y, double z, long now, DisplayMode mode) {
        return admit(id, SoundDefinitions.semantics(id), x, y, z, now, mode);
    }

    public boolean admit(String id, SoundSemantics semantics, double x, double y, double z, long now,
                         DisplayMode mode) {
        if (id == null) return false;
        if (mode == null) mode = DisplayMode.LIGHTWEIGHT;
        if (semantics == null) semantics = SoundDefinitions.semantics(id);
        if (!semantics.allowed(mode)) return false;

        boolean isStep = semantics == SoundSemantics.STEP;
        boolean isGallop = semantics == SoundSemantics.GALLOP;
        boolean isHeavyStep = semantics == SoundSemantics.HEAVY_STEP;
        boolean isLanding = semantics == SoundSemantics.LANDING;
        boolean isRain = SoundEventPolicy.isRain(id);
        boolean isIdleVoice = semantics == SoundSemantics.IDLE_VOICE;
        boolean isCampfire = id.contains("campfire") || id.contains("fire_crackle");
        boolean isAmbient = !isRain && semantics == SoundSemantics.AMBIENT;
        boolean isPiston = id.contains("piston");
        boolean isDispenser = id.contains("dispenser");
        boolean isMinecart = id.contains("minecart");

        // High-frequency mechanical redstone & machinery throttle (prevents machine clock subtitle flooding)
        if (isPiston) {
            return checkCell(KIND_PISTON, id, x, y, z, 1.5, 350_000_000L, now);
        }
        if (isDispenser) {
            return checkCell(KIND_DISPENSER, id, x, y, z, 1.5, 300_000_000L, now);
        }
        if (isMinecart) {
            return checkCell(KIND_MINECART, id, x, y, z, 2.0, 500_000_000L, now);
        }

        // 1. ACTION_ONLY mode: only heavy threat steps (warden/ravager) require throttling; actions pass through
        if (mode == DisplayMode.ACTION_ONLY) {
            if (isHeavyStep) return checkCell(KIND_DEFAULT, id, x, y, z, 3.0, 1_000_000_000L, now);
            return true;
        }

        // 2. LIGHTWEIGHT mode: no ordinary footsteps; galloping preserved at 1.0s cadence; deep mob voice reduction
        if (mode == DisplayMode.LIGHTWEIGHT) {
            if (isStep) return false;
            if (isGallop) {
                return checkFootstep(x, y, z, 3.5, 1_000_000_000L, now);
            }
            if (isHeavyStep) {
                return checkCell(KIND_DEFAULT, id, x, y, z, 3.0, 1_000_000_000L, now);
            }
            if (isLanding) {
                return checkCell(KIND_DEFAULT, id, x, y, z, 2.0, 350_000_000L, now);
            }
            if (isIdleVoice) {
                if (isChorusHordeMob(id)) {
                    return checkCell(KIND_VOICE, id, x, y, z, 0.75, 450_000_000L, now);
                }
                // Mob ambient voices in pens/caves throttled to 2.0s per 4.0m cell
                return checkCell(KIND_VOICE, id, x, y, z, 4.0, 2_000_000_000L, now);
            }
            if (isRain) {
                if (now - lastRain < 3_000_000_000L) return false;
                if (!checkCell(KIND_DEFAULT, "rain", x, y, z, 6.0, 3_000_000_000L, now)) return false;
                lastRain = now;
                return true;
            }
            if (isCampfire) {
                return checkCell(KIND_DEFAULT, id, x, y, z, 3.0, 3_500_000_000L, now);
            }
            if (isAmbient) {
                return checkCell(KIND_DEFAULT, id, x, y, z, 3.0, 1_500_000_000L, now);
            }
            return true;
        }

        // 3. BALANCED mode: footsteps with extended comfortable cadence (1500ms delay, 4.0m proximity)
        if (mode == DisplayMode.BALANCED) {
            if (isStep) {
                return checkFootstep(x, y, z, 4.0, 1_500_000_000L, now);
            }
            if (isGallop) {
                return checkFootstep(x, y, z, 3.5, 800_000_000L, now);
            }
            if (isHeavyStep) {
                return checkCell(KIND_DEFAULT, id, x, y, z, 2.5, 800_000_000L, now);
            }
            if (isLanding) return checkCell(KIND_DEFAULT, id, x, y, z, 1.5, 200_000_000L, now);
            if (isIdleVoice) {
                if (isChorusHordeMob(id)) {
                    return checkCell(KIND_VOICE, id, x, y, z, 0.75, 350_000_000L, now);
                }
                return checkCell(KIND_VOICE, id, x, y, z, 2.5, 1_000_000_000L, now);
            }
            if (isRain) {
                if (now - lastRain < 2_000_000_000L) return false;
                if (!checkCell(KIND_DEFAULT, "rain", x, y, z, 5.0, 2_000_000_000L, now)) return false;
                lastRain = now;
                return true;
            }
            if (isCampfire) {
                return checkCell(KIND_DEFAULT, id, x, y, z, 2.5, 2_500_000_000L, now);
            }
            if (isAmbient) return checkCell(KIND_DEFAULT, id, x, y, z, 2.0, 1_000_000_000L, now);
            return true;
        }

        // 4. FULL mode: maximum density (250ms cadence, 1.5m proximity)
        if (mode == DisplayMode.FULL) {
            if (isStep || isGallop) {
                return checkFootstep(x, y, z, 1.5, 250_000_000L, now);
            }
            if (isHeavyStep) {
                return checkCell(KIND_DEFAULT, id, x, y, z, 1.5, 300_000_000L, now);
            }
            if (isLanding) return checkCell(KIND_DEFAULT, id, x, y, z, 1.2, 120_000_000L, now);
            if (isIdleVoice) return checkCell(KIND_VOICE, id, x, y, z, 1.5, 400_000_000L, now);
            if (isRain) {
                if (now - lastRain < 1_200_000_000L) return false;
                if (!checkCell(KIND_DEFAULT, "rain", x, y, z, 4.0, 1_200_000_000L, now)) return false;
                lastRain = now;
                return true;
            }
            if (isAmbient) return checkCell(KIND_DEFAULT, id, x, y, z, 1.5, 600_000_000L, now);
            return true;
        }

        return true;
    }

    private boolean checkFootstep(double x, double y, double z, double radius, long cooldown, long now) {
        double radiusSq = radius * radius;
        while (!recentSteps.isEmpty()) {
            StepRecord first = recentSteps.peekFirst();
            if (now < first.time || now - first.time >= MAX_STEP_HISTORY_AGE) {
                recentSteps.pollFirst();
            } else {
                break;
            }
        }
        for (StepRecord record : recentSteps) {
            if (now - record.time < cooldown) {
                double dx = record.x - x;
                double dy = record.y - y;
                double dz = record.z - z;
                if (dx * dx + dy * dy + dz * dz < radiusSq) {
                    return false;
                }
            }
        }
        if (recentSteps.size() >= STEP_HISTORY_CAPACITY) {
            return false;
        }
        recentSteps.addLast(new StepRecord(x, y, z, now));
        return true;
    }

    private boolean checkCell(byte kind, String key, double x, double y, double z, double size, long cooldown, long now) {
        int fx = (int) Math.floor(x / size);
        int fy = (int) Math.floor(y / size);
        int fz = (int) Math.floor(z / size);
        lookupProbe.set(kind, key, fx, fy, fz);
        Long previous = recent.get(lookupProbe);
        if (previous != null && now - previous < cooldown) return false;
        if (previous != null) {
            recent.remove(lookupProbe);
        }
        recent.put(new CellKey(kind, key, fx, fy, fz), now);
        if (recent.size() > CAPACITY) {
            Iterator<CellKey> iterator = recent.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
        return true;
    }

    public void clear() {
        recent.clear();
        recentSteps.clear();
        lastRain = Long.MIN_VALUE / 2;
    }

    public int size() {
        return recent.size() + recentSteps.size();
    }

    private static boolean isChorusHordeMob(String id) {
        return id.contains("zombie") || id.contains("husk") || id.contains("drowned")
                || id.contains("zombified") || id.contains("spider") || id.contains("silverfish")
                || id.contains("endermite") || id.contains("skeleton") || id.contains("pillager");
    }
}
