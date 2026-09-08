package com.magmaguy.freeminecraftmodels.api.magic;

/** Item-specific mechanics snapshotted by FMM at cast time; independent of enchantment names. */
public record MagicWeaponModifiers(int missileCount, double missileDamageMultiplier,
                                   double blastRadiusMultiplier, int ignitionTicks) {
    public static final MagicWeaponModifiers NONE = new MagicWeaponModifiers(1, 1D, 1D, 0);

    public MagicWeaponModifiers {
        if (missileCount < 1 || missileCount > 3) throw new IllegalArgumentException("missileCount must be 1..3");
        if (!Double.isFinite(missileDamageMultiplier) || missileDamageMultiplier <= 0D || missileDamageMultiplier > 1D)
            throw new IllegalArgumentException("missileDamageMultiplier must be in (0, 1]");
        if (!Double.isFinite(blastRadiusMultiplier) || blastRadiusMultiplier < 1D || blastRadiusMultiplier > 2D)
            throw new IllegalArgumentException("blastRadiusMultiplier must be 1..2");
        if (ignitionTicks < 0 || ignitionTicks > 200) throw new IllegalArgumentException("ignitionTicks must be 0..200");
    }
}
