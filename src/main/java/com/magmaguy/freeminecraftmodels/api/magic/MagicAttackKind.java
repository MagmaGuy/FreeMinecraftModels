package com.magmaguy.freeminecraftmodels.api.magic;

/** Stable attack identities owned and dispatched by FreeMinecraftModels. */
public enum MagicAttackKind {
    WAND_MISSILE(MagicWeaponKind.WAND),
    STAFF_FIREBALL(MagicWeaponKind.STAFF),
    STAFF_MELEE(MagicWeaponKind.STAFF);

    private final MagicWeaponKind weaponKind;

    MagicAttackKind(MagicWeaponKind weaponKind) {
        this.weaponKind = weaponKind;
    }

    public MagicWeaponKind weaponKind() {
        return weaponKind;
    }
}
