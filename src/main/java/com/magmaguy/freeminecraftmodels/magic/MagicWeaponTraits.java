package com.magmaguy.freeminecraftmodels.magic;

/**
 * Generic modifier seam for future item/enchantment composition.
 *
 * <p>Traits describe mechanics, not named enchantments, so callers can layer item-specific
 * modifiers without coupling the projectile engine to an enchant catalog.</p>
 */
public record MagicWeaponTraits(
        int missileCount,
        double spreadDegrees,
        double impactRadius,
        int ignitionTicks,
        double projectileSpeed,
        double range,
        int travelTicks,
        double aimAssistDegrees) implements com.magmaguy.magmacore.projectiles.MagicProjectileEngine.Traits {

    public MagicWeaponTraits {
        if (missileCount < 1 || travelTicks < 1)
            throw new IllegalArgumentException("Invalid discrete magic-weapon traits");
        requireNonNegativeFinite(spreadDegrees, "spreadDegrees");
        requireNonNegativeFinite(impactRadius, "impactRadius");
        requireNonNegativeFinite(projectileSpeed, "projectileSpeed");
        requirePositiveFinite(range, "range");
        requireNonNegativeFinite(aimAssistDegrees, "aimAssistDegrees");
        if (ignitionTicks < 0) throw new IllegalArgumentException("ignitionTicks must not be negative");
    }

    private static void requireNonNegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0D)
            throw new IllegalArgumentException(name + " must be non-negative and finite");
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0D)
            throw new IllegalArgumentException(name + " must be positive and finite");
    }
}
