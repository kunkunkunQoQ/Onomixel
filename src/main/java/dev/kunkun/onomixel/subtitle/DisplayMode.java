package dev.kunkun.onomixel.subtitle;

/**
 * Display density and sound filtering mode.
 */
public enum DisplayMode {
    /**
     * Lightweight mode (Default): heavily throttles footsteps and reduces mob ambient voices;
     * keeps action, block breaks, and combat responsive and clutter-free.
     */
    LIGHTWEIGHT,

    /**
     * Balanced mode: moderate spatial throttling across footsteps and ambient voices.
     */
    BALANCED,

    /**
     * Full mode: maximum onomatopoeia density with minimal cooldowns.
     */
    FULL,

    /**
     * Action only mode: completely suppresses footsteps, rain, and idle mob ambient sounds.
     */
    ACTION_ONLY
}
