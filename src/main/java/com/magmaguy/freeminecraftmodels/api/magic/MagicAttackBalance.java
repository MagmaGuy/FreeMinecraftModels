package com.magmaguy.freeminecraftmodels.api.magic;

/**
 * Plugin-neutral balance inputs for one impact.
 *
 * <p>FreeMinecraftModels standalone damage is {@code referenceDamage * basePower * impactScale}.
 * Integrations may replace the reference curve while retaining the attack's base power and
 * distance falloff.</p>
 */
public record MagicAttackBalance(
        double standaloneReferenceDamage,
        double basePower,
        double impactScale) {

    public MagicAttackBalance {
        requirePositiveFinite(standaloneReferenceDamage, "standaloneReferenceDamage");
        requirePositiveFinite(basePower, "basePower");
        requirePositiveFinite(impactScale, "impactScale");
    }

    public double standaloneDamage() {
        double result = standaloneReferenceDamage * basePower * impactScale;
        return Double.isFinite(result) && result > 0D ? result : 0D;
    }

    private static void requirePositiveFinite(double value, String name) {
        if (!Double.isFinite(value) || value <= 0D)
            throw new IllegalArgumentException(name + " must be positive and finite");
    }
}
