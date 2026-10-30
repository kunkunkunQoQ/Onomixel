package dev.kunkun.onomixel.subtitle;

import dev.kunkun.onomixel.sound.SoundPlacement;

/**
 * Standard built-in visual types for Onomixel.
 */
public final class VisualTypes {
    private VisualTypes() {}

    /** Mechanical interactions (chests, doors, buttons, levers). */
    public static final VisualType INTERACTION = new VisualType() {
        @Override public String id() { return "onomixel:interaction"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xFFE79A; }
        @Override public int pastelColor() { return 0xFFF1D5; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.INTERACTION; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.6F, 1.6F, 6.0F, 0.0, 0.08); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 12) * Math.sin(age * 24);
            double start = Math.exp(-age * 24);
            double beat = age > 0.30 ? 0.10 * Math.exp(-trigger * 15) * Math.sin(trigger * 28) : 0;
            float scale = (float) (1 + 0.55 * pop - 0.28 * start + beat);
            float tilt = (float) (sign * 6 * Math.exp(-age * 10));
            float stretchX = (float) (1.0 + 0.10 * pop + 0.035 * beat);
            float stretchY = (float) (1.0 - 0.06 * pop - 0.02 * beat);
            double side = 0;
            double rise = 0.08 * (1 - Math.exp(-age * 8));
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Block breaks and shattering. */
    public static final VisualType BREAK = new VisualType() {
        @Override public String id() { return "onomixel:break"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xFFAA44; }
        @Override public int pastelColor() { return 0xFFE2AF; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.BREAK; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.8F, 1.8F, 14.0F, 0.14, 0.22); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 9) * Math.sin(age * 20);
            double start = Math.exp(-age * 20);
            double beat = age > 0.30 ? 0.14 * Math.exp(-trigger * 12) * Math.sin(trigger * 24) : 0;
            float scale = (float) (1 + 0.85 * pop - 0.35 * start + beat);
            float tilt = (float) (sign * (9.0 * Math.exp(-age * 6) + 1.6 * Math.sin(trigger * 7) * Math.exp(-trigger * 2)));
            float stretchX = (float) (1.0 + 0.12 * pop + 0.04 * beat);
            float stretchY = (float) (1.0 - 0.07 * pop - 0.025 * beat);
            double side = sign * 0.14 * (1 - Math.exp(-age * 5));
            double rise = 0.22 * (1 - Math.exp(-age * 5));
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Item and experience orb pickups. */
    public static final VisualType PICKUP = new VisualType() {
        @Override public String id() { return "onomixel:pickup"; }
        @Override public boolean isBold() { return false; }
        @Override public int vibrantColor() { return 0xE2FF54; }
        @Override public int pastelColor() { return 0xFFF598; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.PICKUP; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.55F, 1.55F, 5.0F, 0.05, 0.36); }

        @Override
        public double slotScoreBonus(int slot) {
            return slot != 2 ? 0.8 : 0.0;
        }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 10) * Math.sin(age * 22);
            double start = Math.exp(-age * 22);
            double beat = age > 0.30 ? 0.09 * Math.exp(-trigger * 14) * Math.sin(trigger * 26) : 0;
            float scale = (float) (1 + 0.65 * pop - 0.32 * start + beat);
            float tilt = (float) (sign * 5 * Math.exp(-age * 6));
            float stretchX = (float) (1.0 + 0.11 * pop + 0.035 * beat);
            float stretchY = (float) (1.0 - 0.065 * pop - 0.02 * beat);
            double side = sign * 0.05 * Math.sin(Math.min(age * 4, Math.PI));
            double rise = 0.36 * (1 - Math.exp(-age * 5));
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Entity vocalizations (mobs, animals, villagers). */
    public static final VisualType VOICE = new VisualType() {
        @Override public String id() { return "onomixel:voice"; }
        @Override public boolean isBold() { return false; }
        @Override public int vibrantColor() { return 0x7AD7FF; }
        @Override public int pastelColor() { return 0xEAF3FF; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.VOICE; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.4F, 1.4F, 4.0F, 0.08, 0.20); }

        @Override
        public double slotScoreBonus(int slot) {
            return slot == 2 ? 2.0 : 0.0;
        }

        @Override
        public CaptionVisuals.Effect sample(String soundId, double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            CaptionVisuals.Effect base = sample(ageSeconds, sinceTriggerSeconds, seed, style);
            double age = Math.max(0, ageSeconds);
            if (SoundPlacement.isBaby(soundId)) {
                double bounce = Math.max(0, Math.sin(age * 20) * Math.exp(-age * 8)) * 0.12;
                float babyScale = base.scale() * 0.65F;
                return bound(new CaptionVisuals.Effect(babyScale, base.tiltDegrees(), base.side(),
                        base.rise() + bounce, base.rgb(), base.stretchX(), base.stretchY()));
            }
            if (soundId != null && soundId.contains("spider.ambient")) {
                double chitter = Math.sin(age * 45) * 0.02;
                int toxicPurple = style == ColorStyle.PASTEL ? 0xD8B4FE : 0xA855F7;
                return bound(new CaptionVisuals.Effect(base.scale(), base.tiltDegrees(), base.side() + chitter,
                        base.rise(), toxicPurple, 1.25F, 0.80F));
            }
            return VisualType.super.sample(soundId, ageSeconds, sinceTriggerSeconds, seed, style);
        }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double start = Math.exp(-age * 20);
            double beat = age > 0.30 ? 0.08 * Math.exp(-trigger * 12) * Math.sin(trigger * 24) : 0;
            float scale = (float) (1 - 0.18 * start + 0.032 * Math.sin(age * 5) + beat);
            float tilt = (float) (sign * 2.5 * Math.sin(age * 3));
            float stretchX = (float) (1.0 + 0.025 * Math.sin(age * 5) + 0.025 * beat);
            float stretchY = (float) (1.0 - 0.02 * Math.sin(age * 5) - 0.015 * beat);
            double side = 0.03 * Math.sin(age * 2);
            double rise = 0.04 * Math.sin(age * 2.5);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Hits, damage, explosions, footsteps, fall impacts. */
    public static final VisualType IMPACT = new VisualType() {
        @Override public String id() { return "onomixel:impact"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xFF6B5E; }
        @Override public int pastelColor() { return 0xFFE0CC; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.8F, 1.8F, 10.0F, 0.08, 0.18); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 11) * Math.sin(age * 25);
            double start = Math.exp(-age * 24);
            double beat = age > 0.30 ? 0.12 * Math.exp(-trigger * 14) * Math.sin(trigger * 28) : 0;
            double jitter = age < 0.12 ? 0.03 * Math.sin(age * 70) : 0;
            float scale = (float) (1 + 0.88 * pop - 0.28 * start + beat + jitter);
            float tilt = (float) (sign * (8.0 * Math.exp(-age * 7) + 1.4 * Math.sin(trigger * 8) * Math.exp(-trigger * 2.2)));
            float stretchX = (float) (1.0 + 0.12 * pop + 0.04 * beat + 0.02 * jitter);
            float stretchY = (float) (1.0 - 0.07 * pop - 0.025 * beat - 0.015 * jitter);
            double side = sign * 0.08 * Math.exp(-age * 6);
            double rise = 0.18 * (1 - Math.exp(-age * 6));
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Ambient environmental sounds and weather rain. */
    public static final VisualType AMBIENT = new VisualType() {
        @Override public String id() { return "onomixel:ambient"; }
        @Override public boolean isBold() { return false; }
        @Override public int vibrantColor() { return 0x6EE7B7; }
        @Override public int pastelColor() { return 0xDFF5F0; }
        @Override public boolean isAmbient() { return true; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.AMBIENT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.12F, 1.12F, 1.5F, 0.03, 0.08); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double beat = age > 0.35 ? 0.04 * Math.exp(-trigger * 10) * Math.sin(trigger * 20) : 0;
            float scale = (float) (0.98 + 0.035 * Math.sin(age * 3) + beat);
            float tilt = (float) (sign * 1.5 * Math.sin(age * 2));
            float stretchX = (float) (1.0 + 0.018 * Math.sin(age * 3) + 0.015 * beat);
            float stretchY = (float) (1.0 - 0.014 * Math.sin(age * 3) - 0.01 * beat);
            double side = 0.03 * Math.sin(age * 2);
            double rise = 0.08 * Math.sin(age * 2);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Ambient weather rain with misty downward drift and wind-slanted threads. */
    public static final VisualType RAIN = new VisualType() {
        @Override public String id() { return "onomixel:rain"; }
        @Override public boolean isBold() { return false; }
        @Override public boolean isItalic() { return true; }
        @Override public int vibrantColor() { return 0x38BDF8; }
        @Override public int pastelColor() { return 0xBAE6FD; }
        @Override public boolean isAmbient() { return true; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.AMBIENT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.3F, 1.3F, 15.0F, 0.15, 0.45); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            float tilt = (float) (-7.0 + sign * 1.5 * Math.sin(age * 2.5));
            double side = -0.06 * (1.0 - Math.exp(-age * 2.2));
            double rise = -0.35 * (1.0 - Math.exp(-age * 2.2));
            float stretchX = (float) (1.0 + 0.10 * Math.min(1.0, age / 1.5));
            float stretchY = (float) (1.0 - 0.05 * Math.min(1.0, age / 1.5));
            float scale = (float) (0.95 + 0.04 * Math.sin(age * 2.5));
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Hearth and campfire embers with spark rise and gentle thermal sway. */
    public static final VisualType CAMPFIRE = new VisualType() {
        @Override public String id() { return "onomixel:campfire"; }
        @Override public boolean isBold() { return false; }
        @Override public int vibrantColor() { return 0xF59E0B; }
        @Override public int pastelColor() { return 0xFCD34D; }
        @Override public boolean isAmbient() { return true; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.AMBIENT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.3F, 1.3F, 8.0F, 0.12, 0.45); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double rise = 0.35 * (1.0 - Math.exp(-age * 2.2));
            double side = sign * 0.05 * Math.sin(age * 4.0);
            float tilt = (float) (sign * 3.0 * Math.sin(age * 3.0));
            float scale = (float) (1.0 - 0.18 * Math.min(1.0, age / 1.5));
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), 1.0F, 1.0F);
        }
    };

    /** High-threat creeper fuse with escalating countdown swell and violent jitter. */
    public static final VisualType CREEPER_PRIMED = new VisualType() {
        @Override public String id() { return "onomixel:creeper_primed"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xFBBF24; }
        @Override public int pastelColor() { return 0xFDE68A; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.5F, 2.5F, 15.0F, 0.15, 0.25); }

        @Override
        public CaptionVisuals.Effect sample(String soundId, double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            return sample(ageSeconds, sinceTriggerSeconds, seed, style);
        }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            float scale;
            float tilt;
            double side;
            double rise;
            int rgb;

            if (age < 0.5) {
                scale = (float) (1.0 + 0.08 * Math.sin(age * 30));
                tilt = (float) (sign * 3.0 * Math.sin(age * 35));
                side = 0.02 * Math.sin(age * 30);
                rise = 0.04 * Math.sin(age * 20);
                rgb = style == ColorStyle.PASTEL ? 0xFDE68A : 0xFBBF24;
            } else if (age < 1.0) {
                double progress = (age - 0.5) / 0.5;
                scale = (float) (1.0 + 0.50 * progress + 0.04 * Math.sin(age * 45));
                tilt = (float) (sign * (3.0 + 4.0 * progress) * Math.sin(age * 50));
                side = sign * 0.04 * Math.sin(age * 50);
                rise = 0.08 * progress;
                boolean flash = Math.sin(age * 40) > 0;
                rgb = flash ? (style == ColorStyle.PASTEL ? 0xFCA5A5 : 0xEF4444)
                            : (style == ColorStyle.PASTEL ? 0xFDE68A : 0xF97316);
            } else {
                double progress = Math.min(1.0, (age - 1.0) / 0.5);
                float jitter = (float) (Math.sin(age * 90) * 0.04);
                scale = (float) (1.50 + 0.40 * progress + jitter);
                tilt = (float) (sign * (7.0 + 3.0 * progress) * Math.sin(age * 70));
                side = sign * 0.06 * Math.sin(age * 70);
                rise = 0.12 * progress;
                rgb = style == ColorStyle.PASTEL ? 0xF87171 : 0xDC2626;
            }
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, 1.0F, 1.0F);
        }
    };

    /** Player critical hit with gold starburst and diagonal cutting slash. */
    public static final VisualType CRITICAL_HIT = new VisualType() {
        @Override public String id() { return "onomixel:crit"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xFBBF24; }
        @Override public int pastelColor() { return 0xFEF08A; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.4F, 2.4F, 30.0F, 0.20, 0.30); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double hitstop = (age >= 0.02 && age < 0.07) ? 0.02 * Math.sin(age * 120) : 0;
            double pop = Math.exp(-age * 10) * Math.sin(age * 22);
            double beat = age > 0.25 ? 0.12 * Math.exp(-trigger * 14) * Math.sin(trigger * 26) : 0;
            float scale = (float) (1.0 + 0.95 * pop + hitstop + beat);
            float tilt = (float) (-22.0 * Math.exp(-age * 6) + sign * 1.5 * Math.sin(age * 4));
            float stretchX = (float) (1.0 + 0.15 * pop);
            float stretchY = (float) (1.0 - 0.08 * pop);
            double side = sign * 0.10 * (1 - Math.exp(-age * 6));
            double rise = 0.18 * (1 - Math.exp(-age * 6));
            int rgb = age < 0.03 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, stretchX, stretchY);
        }
    };

    /** Sweep attack with wide horizontal blade stretch and panoramic air-slice. */
    public static final VisualType SWEEP_ATTACK = new VisualType() {
        @Override public String id() { return "onomixel:sweep"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0x38BDF8; }
        @Override public int pastelColor() { return 0xBAE6FD; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.2F, 2.2F, 15.0F, 0.35, 0.15); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 9) * Math.sin(age * 18);
            float scale = (float) (1.0 + 0.65 * pop);
            float tilt = (float) (sign * 6.0 * Math.exp(-age * 5));
            float stretchX = (float) (1.60 - 0.50 * (1.0 - Math.exp(-age * 6)));
            float stretchY = (float) (0.75 + 0.20 * (1.0 - Math.exp(-age * 6)));
            double side = sign * 0.25 * (1.0 - Math.exp(-age * 8));
            double rise = 0.06 * Math.sin(age * 3);
            int rgb = age < 0.03 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, stretchX, stretchY);
        }
    };

    /** Shield block and parry with solid metallic recoil and spark deflection. */
    public static final VisualType SHIELD_BLOCK = new VisualType() {
        @Override public String id() { return "onomixel:shield_block"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0x94A3B8; }
        @Override public int pastelColor() { return 0xCBD5E1; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.8F, 1.8F, 15.0F, 0.15, 0.22); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 14) * Math.sin(age * 28);
            float scale = (float) (1.0 + 0.55 * pop);
            float tilt = (float) (sign * 8.0 * Math.exp(-age * 8));
            float stretchX = (float) (1.0 + 0.10 * pop);
            float stretchY = (float) (1.0 - 0.05 * pop);
            double side = sign * 0.06 * Math.exp(-age * 8);
            double rise = 0.14 * (1.0 - Math.exp(-age * 8));
            int rgb = age < 0.03 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, stretchX, stretchY);
        }
    };

    /** Shield break with shattered collapse and lost-balance downward tilt. */
    public static final VisualType SHIELD_BREAK = new VisualType() {
        @Override public String id() { return "onomixel:shield_break"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xEF4444; }
        @Override public int pastelColor() { return 0xFCA5A5; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.0F, 2.0F, 35.0F, 0.20, 0.35); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 8) * Math.sin(age * 16);
            float scale = (float) (1.0 + 0.75 * pop);
            float tilt = (float) (sign * (25.0 * (1.0 - Math.exp(-age * 4))));
            float stretchX = (float) (1.0 + 0.18 * pop);
            float stretchY = (float) (1.0 - 0.10 * pop);
            double side = sign * 0.12 * (1.0 - Math.exp(-age * 4));
            double rise = -0.22 * (1.0 - Math.exp(-age * 5));
            int rgb = age < 0.04 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, stretchX, stretchY);
        }
    };

    /** Blunt heavy damage with initial squash, hit-stop, and explosive spring rebound. */
    public static final VisualType DAMAGE_BLUNT = new VisualType() {
        @Override public String id() { return "onomixel:damage_blunt"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xEF4444; }
        @Override public int pastelColor() { return 0xFCA5A5; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.2F, 2.2F, 25.0F, 0.20, 0.25); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double hitstop = (age >= 0.03 && age < 0.09) ? 0.02 * Math.sin(age * 100) : 0;
            double pop = Math.exp(-age * 10) * Math.sin(age * 22);
            double beat = age > 0.25 ? 0.12 * Math.exp(-trigger * 14) * Math.sin(trigger * 28) : 0;
            float scale = (float) (1.0 + 0.85 * pop + hitstop + beat);
            float tilt = (float) (sign * (12.0 * Math.exp(-age * 7) + 2.0 * Math.sin(trigger * 6)));
            float stretchX = (float) (age < 0.05 ? 1.30 : (1.0 + 0.12 * pop));
            float stretchY = (float) (age < 0.05 ? 0.68 : (1.0 - 0.08 * pop));
            double side = sign * 0.08 * Math.exp(-age * 6);
            double rise = 0.16 * (1.0 - Math.exp(-age * 6));
            int rgb = age < 0.03 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, stretchX, stretchY);
        }
    };

    /** Piercing arrow and trident punctures with needle-axis elongation and sharp angular shock. */
    public static final VisualType DAMAGE_PIERCE = new VisualType() {
        @Override public String id() { return "onomixel:damage_pierce"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xF97316; }
        @Override public int pastelColor() { return 0xFDBA74; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.0F, 2.0F, 25.0F, 0.20, 0.25); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double trigger = Math.max(0, sinceTriggerSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 12) * Math.sin(age * 26);
            double beat = age > 0.25 ? 0.10 * Math.exp(-trigger * 15) * Math.sin(trigger * 30) : 0;
            float scale = (float) (1.0 + 0.78 * pop + beat);
            float tilt = (float) (-16.0 * Math.exp(-age * 8) + sign * 1.5 * Math.sin(age * 5));
            float stretchX = (float) (1.35 * Math.exp(-age * 6) + 1.0 * (1.0 - Math.exp(-age * 6)));
            float stretchY = (float) (0.80 * Math.exp(-age * 6) + 1.0 * (1.0 - Math.exp(-age * 6)));
            double side = sign * 0.06 * Math.exp(-age * 7);
            double rise = 0.12 * (1.0 - Math.exp(-age * 7));
            int rgb = age < 0.03 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, stretchX, stretchY);
        }
    };

    /** Ground impact and fall crush with contact squash and lateral dust shockwave. */
    public static final VisualType DAMAGE_FALL = new VisualType() {
        @Override public String id() { return "onomixel:damage_fall"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xE2E8F0; }
        @Override public int pastelColor() { return 0xF1F5F9; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.2F, 2.2F, 20.0F, 0.25, 0.25); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1 : -1;
            double pop = Math.exp(-age * 11) * Math.sin(age * 24);
            float scale = (float) (1.0 + 0.80 * pop);
            float tilt = (float) (sign * 6.0 * Math.exp(-age * 6));
            float stretchX = (float) (1.45 * Math.exp(-age * 7) + 1.0 * (1.0 - Math.exp(-age * 7)));
            float stretchY = (float) (0.62 * Math.exp(-age * 7) + 1.0 * (1.0 - Math.exp(-age * 7)));
            double side = sign * 0.14 * (1.0 - Math.exp(-age * 8));
            double rise = 0.08 * (1.0 - Math.exp(-age * 8));
            int rgb = age < 0.03 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, side, rise, rgb, stretchX, stretchY);
        }
    };

    /** Warden sculk heartbeat with low-frequency double concentric expansion. */
    public static final VisualType WARDEN_HEART = new VisualType() {
        @Override public String id() { return "onomixel:warden_heart"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0x06B6D4; }
        @Override public int pastelColor() { return 0x67E8F9; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.0F, 2.0F, 8.0F, 0.10, 0.10); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double beat1 = age < 0.18 ? Math.sin(age / 0.18 * Math.PI) * 0.38 : 0;
            double beat2 = (age >= 0.18 && age < 0.36) ? Math.sin((age - 0.18) / 0.18 * Math.PI) * 0.20 : 0;
            float scale = (float) (1.0 + beat1 + beat2);
            float stretchX = (float) (1.0 + 0.15 * beat1);
            float stretchY = (float) (1.0 + 0.15 * beat1);
            double rise = 0.05 * Math.sin(age * 3);
            return new CaptionVisuals.Effect(scale, 0.0F, 0.0, rise, color(style), stretchX, stretchY);
        }
    };

    /** Warden supersonic sonic boom with high-energy directional piercing beam. */
    public static final VisualType WARDEN_SONIC = new VisualType() {
        @Override public String id() { return "onomixel:warden_sonic"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0x22D3EE; }
        @Override public int pastelColor() { return 0xA5F3FC; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.8F, 2.8F, 25.0F, 0.35, 0.35); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double pop = Math.exp(-age * 9) * Math.sin(age * 20);
            float scale = (float) (1.0 + 0.95 * pop);
            float stretchX = (float) (2.40 * Math.exp(-age * 6) + 1.0 * (1.0 - Math.exp(-age * 6)));
            float stretchY = (float) (0.65 * Math.exp(-age * 6) + 1.0 * (1.0 - Math.exp(-age * 6)));
            float tilt = (float) (-18.0 * Math.exp(-age * 7));
            double rise = 0.16 * (1.0 - Math.exp(-age * 8));
            int rgb = age < 0.04 && style == ColorStyle.VIBRANT ? 0xFFFFFF : color(style);
            return new CaptionVisuals.Effect(scale, tilt, 0.0, rise, rgb, stretchX, stretchY);
        }
    };

    /** Enderman spatial teleportation with quantum horizontal line collapse and burst rebound. */
    public static final VisualType ENDERMAN_TP = new VisualType() {
        @Override public String id() { return "onomixel:enderman_tp"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xC084FC; }
        @Override public int pastelColor() { return 0xE9D5FF; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.0F, 2.0F, 15.0F, 0.15, 0.15); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            float stretchX;
            float stretchY;
            float scale;
            if (age < 0.06) {
                stretchX = 0.08F;
                stretchY = 2.0F;
                scale = 0.85F;
            } else {
                double pop = Math.exp(-(age - 0.06) * 12) * Math.sin((age - 0.06) * 25);
                stretchX = (float) (1.0 + 0.25 * pop);
                stretchY = (float) (1.0 - 0.20 * pop);
                scale = (float) (1.0 + 0.50 * pop);
            }
            float tilt = (float) (4.0 * Math.sin(age * 8));
            return new CaptionVisuals.Effect(scale, tilt, 0.0, 0.08 * (1.0 - Math.exp(-age * 8)), color(style), stretchX, stretchY);
        }
    };

    /** Climax tool or armor durability break with shattered dispersal. */
    public static final VisualType TOOL_BREAK = new VisualType() {
        @Override public String id() { return "onomixel:tool_break"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xEF4444; }
        @Override public int pastelColor() { return 0xFCA5A5; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.BREAK; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.2F, 2.2F, 22.0F, 0.25, 0.25); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double sign = (seed & 1) == 0 ? 1.0 : -1.0;
            double pop = Math.exp(-age * 11) * Math.sin(age * 22);
            float scale = (float) (1.0 + 0.95 * pop);
            float stretchX = (float) (1.0 + 0.35 * Math.exp(-age * 8));
            float stretchY = (float) (1.0 - 0.20 * Math.exp(-age * 8));
            float tilt = (float) (sign * 14.0 * Math.exp(-age * 6));
            double rise = 0.20 * (1.0 - Math.exp(-age * 7));
            double side = sign * 0.15 * (1.0 - Math.exp(-age * 6));
            return new CaptionVisuals.Effect(scale, tilt, side, rise, color(style), stretchX, stretchY);
        }
    };

    /** Village bell golden spherical shockwave expansion. */
    public static final VisualType BELL_RESONANCE = new VisualType() {
        @Override public String id() { return "onomixel:bell_resonance"; }
        @Override public boolean isBold() { return true; }
        @Override public int vibrantColor() { return 0xFBBF24; }
        @Override public int pastelColor() { return 0xFDE68A; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.IMPACT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(2.5F, 2.5F, 10.0F, 0.20, 0.40); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double wave = Math.sin(age * 10) * Math.exp(-age * 2.5);
            float scale = (float) (1.15 + 0.35 * wave);
            float tilt = (float) (3.0 * Math.sin(age * 6) * Math.exp(-age * 2.0));
            double rise = 0.25 * (1.0 - Math.exp(-age * 3.5));
            return new CaptionVisuals.Effect(scale, tilt, 0.0, rise, color(style), 1.05F, 0.95F);
        }
    };

    /** Soul Sand Valley eerie spiraling wail. */
    public static final VisualType NETHER_SOUL = new VisualType() {
        @Override public String id() { return "onomixel:nether_soul"; }
        @Override public boolean isBold() { return false; }
        @Override public int vibrantColor() { return 0x38BDF8; }
        @Override public int pastelColor() { return 0xBAE6FD; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.AMBIENT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.4F, 1.4F, 8.0F, 0.10, 0.30); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double wave = Math.sin(age * 4.5);
            float tilt = (float) (6.0 * wave);
            double rise = 0.28 * (1.0 - Math.exp(-age * 2.0));
            double side = 0.08 * Math.cos(age * 4.0);
            return new CaptionVisuals.Effect(1.0F, tilt, side, rise, color(style), 0.95F, 1.05F);
        }
    };

    /** Basalt Deltas volcanic ash descent. */
    public static final VisualType NETHER_ASH = new VisualType() {
        @Override public String id() { return "onomixel:nether_ash"; }
        @Override public boolean isBold() { return false; }
        @Override public int vibrantColor() { return 0x9CA3AF; }
        @Override public int pastelColor() { return 0xD1D5DB; }
        @Override public CaptionVisuals.Kind toKind() { return CaptionVisuals.Kind.AMBIENT; }
        @Override public MotionEnvelope motionEnvelope() { return new MotionEnvelope(1.3F, 1.3F, 6.0F, 0.05, 0.20); }

        @Override
        public CaptionVisuals.Effect sample(double ageSeconds, double sinceTriggerSeconds, long seed, ColorStyle style) {
            double age = Math.max(0, ageSeconds);
            double descent = -0.18 * (1.0 - Math.exp(-age * 3.0));
            float tilt = (float) (-4.0 * Math.exp(-age * 4));
            return new CaptionVisuals.Effect(0.95F, tilt, 0.0, descent, color(style), 1.0F, 1.0F);
        }
    };

    public static VisualType fromKind(CaptionVisuals.Kind kind) {
        if (kind == null) return IMPACT;
        return switch (kind) {
            case INTERACTION -> INTERACTION;
            case BREAK -> BREAK;
            case PICKUP -> PICKUP;
            case VOICE -> VOICE;
            case IMPACT -> IMPACT;
            case AMBIENT -> AMBIENT;
        };
    }
}
