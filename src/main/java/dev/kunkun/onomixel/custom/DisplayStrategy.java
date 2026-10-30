package dev.kunkun.onomixel.custom;

import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Text resolution strategy for customized onomatopoeia sound definitions.
 * Zero Minecraft runtime dependencies for pure JVM testability.
 */
public sealed interface DisplayStrategy {

    /**
     * Resolves the onomatopoeia text to be displayed for the given sound event.
     *
     * @param seed pseudo-random seed (e.g. system nanoTime or sound event coordinate hash)
     * @param soundId the sound event identifier
     * @return display string (either literal text or translatable key)
     */
    String resolve(long seed, String soundId);

    /**
     * Resolves the prefixed/formatted display string for the given sound event.
     * Literal strings return pre-cached "literal:..." string with zero runtime allocation.
     * Translatable keys return key directly.
     */
    String resolveFormatted(long seed, String soundId);

    /**
     * Returns true if the resolved string is a raw literal string rather than an i18n translation key.
     */
    boolean isLiteral();

    /**
     * Single literal text strategy (e.g. "咚！" or "KABOOM!").
     */
    record SingleText(String text, String formattedText) implements DisplayStrategy {
        public SingleText(String text) {
            this(Objects.requireNonNull(text, "text cannot be null"), "literal:" + text);
        }

        public SingleText {
            Objects.requireNonNull(text, "text cannot be null");
            if (formattedText == null) {
                formattedText = "literal:" + text;
            }
        }

        @Override
        public String resolve(long seed, String soundId) {
            return text;
        }

        @Override
        public String resolveFormatted(long seed, String soundId) {
            return formattedText;
        }

        @Override
        public boolean isLiteral() {
            return true;
        }
    }

    /**
     * Translatable localization key strategy (e.g. "my_pack.subtitles.explosion").
     */
    record Translatable(String translationKey) implements DisplayStrategy {
        public Translatable {
            Objects.requireNonNull(translationKey, "translationKey cannot be null");
        }

        @Override
        public String resolve(long seed, String soundId) {
            return translationKey;
        }

        @Override
        public String resolveFormatted(long seed, String soundId) {
            return translationKey;
        }

        @Override
        public boolean isLiteral() {
            return false;
        }
    }

    /**
     * Weighted variant item for weighted random pools.
     */
    record Variant(String text, int weight, String formattedText) {
        public Variant(String text, int weight) {
            this(Objects.requireNonNull(text, "variant text cannot be null"), weight, "literal:" + text);
        }

        public Variant {
            Objects.requireNonNull(text, "variant text cannot be null");
            if (weight <= 0) {
                throw new IllegalArgumentException("Variant weight must be strictly positive (> 0), got: " + weight);
            }
            if (formattedText == null) {
                formattedText = "literal:" + text;
            }
        }
    }

    /**
     * Weighted random variant pool strategy.
     * Efficient O(log N) binary search roulette wheel resolution with zero runtime allocation.
     */
    record WeightedRandom(List<Variant> variants, int[] cumulativeWeights, int totalWeight) implements DisplayStrategy {
        public WeightedRandom(List<Variant> variants) {
            this(variants, computeCumulative(variants), computeTotal(variants));
        }

        private static int[] computeCumulative(List<Variant> list) {
            Objects.requireNonNull(list, "variants list cannot be null");
            if (list.isEmpty()) {
                throw new IllegalArgumentException("Weighted variants list cannot be empty");
            }
            int[] cumulative = new int[list.size()];
            long sum = 0;
            for (int i = 0; i < list.size(); i++) {
                Variant v = Objects.requireNonNull(list.get(i), "variant cannot be null");
                sum += v.weight();
                if (sum > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("Total variant weight exceeds Integer.MAX_VALUE: " + sum);
                }
                cumulative[i] = (int) sum;
            }
            return cumulative;
        }

        private static int computeTotal(List<Variant> list) {
            if (list == null || list.isEmpty()) return 0;
            long sum = 0;
            for (Variant v : list) {
                if (v != null) {
                    sum += v.weight();
                    if (sum > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Total variant weight exceeds Integer.MAX_VALUE: " + sum);
                    }
                }
            }
            return (int) sum;
        }

        @Override
        public String resolve(long seed, String soundId) {
            if (variants.isEmpty() || totalWeight <= 0) return "";
            return variants.get(pickIndex(seed)).text();
        }

        @Override
        public String resolveFormatted(long seed, String soundId) {
            if (variants.isEmpty() || totalWeight <= 0) return "";
            return variants.get(pickIndex(seed)).formattedText();
        }

        private int pickIndex(long seed) {
            // Use standard positive modulo roulette
            long positiveSeed = seed < 0 ? -seed : seed;
            if (positiveSeed < 0) positiveSeed = 0; // Handle Long.MIN_VALUE edge case
            int roll = (int) (positiveSeed % totalWeight);

            // Fast binary search on cumulative weight thresholds
            int low = 0;
            int high = cumulativeWeights.length - 1;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (roll < cumulativeWeights[mid]) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            return low;
        }

        @Override
        public boolean isLiteral() {
            return true;
        }
    }
}
