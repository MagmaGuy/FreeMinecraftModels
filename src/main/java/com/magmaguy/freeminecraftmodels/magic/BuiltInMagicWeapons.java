package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponAPI;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;

import java.util.Map;

/** Family defaults only. Actual weapons are loaded from the ordinary item YAML catalog. */
public final class BuiltInMagicWeapons {
    public static final String DEFAULT_WAND_ID = MagicWeaponAPI.DEFAULT_WAND_ID;
    public static final String DEFAULT_STAFF_ID = MagicWeaponAPI.DEFAULT_STAFF_ID;
    public static final double STANDALONE_REFERENCE_DAMAGE = 4D;
    // A 45-degree half-angle gives the default wand a 90-degree forward acquisition cone.
    private static final double DEFAULT_WAND_AIM_ASSIST_DEGREES = 45D;

    public static MagicWeaponDefinition defaults(String itemId, MagicWeaponKind kind) {
        return switch (kind) {
            case WAND -> new MagicWeaponDefinition(
                    itemId,
                    MagicWeaponKind.WAND,
                    new MagicWeaponTraits(
                            1, 0D, 0D, 0, .72D, 18D, 24, DEFAULT_WAND_AIM_ASSIST_DEGREES),
                    // 20% slower cadence than the original 9-tick reload at identical DPS:
                    // .55 power * 11/9 = .672.
                    Map.of(MagicAttackKind.WAND_MISSILE, .672D),
                    Map.of(MagicAttackKind.WAND_MISSILE, 11));
            case STAFF -> new MagicWeaponDefinition(
                    itemId,
                    MagicWeaponKind.STAFF,
                    new MagicWeaponTraits(1, 0D, 2.75D, 0, .38D, 28D, 74, 0D),
                    Map.of(
                            MagicAttackKind.STAFF_MELEE, .45D,
                            MagicAttackKind.STAFF_FIREBALL, 1.25D),
                    Map.of(
                            MagicAttackKind.STAFF_MELEE, 12,
                            MagicAttackKind.STAFF_FIREBALL, 40));
        };
    }

    private BuiltInMagicWeapons() {
    }

}
