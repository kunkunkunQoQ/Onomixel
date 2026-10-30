package dev.kunkun.onomixel.subtitle;

/**
 * Color accessibility filtration modes for players with various color vision deficiencies.
 */
public enum ColorblindMode {
    /** Standard full-spectrum color rendering. */
    OFF,
    /** Protanopia (red-weak/red-blind): shifts red critical alerts to high-visibility golden amber and bright white. */
    PROTANOPIA,
    /** Deuteranopia (green-weak/green-blind): shifts mid-greens to bright sky cyan and lake blue. */
    DEUTERANOPIA,
    /** Tritanopia (blue-weak/blue-blind): shifts deep blues to vivid magenta and rose amber. */
    TRITANOPIA
}
