package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;

import java.util.Objects;
import java.util.Optional;

/** Pure control contract shared by the Bukkit listener and focused regression tests. */
final class MagicWeaponInputRouter {
    private MagicWeaponInputRouter() {
    }

    static Optional<MagicAttackKind> route(MagicWeaponKind weapon, MagicInput input) {
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(input, "input");
        if (weapon == MagicWeaponKind.WAND) {
            return switch (input) {
                case AIR_LEFT_CLICK, BLOCK_LEFT_CLICK, ENTITY_LEFT_CLICK ->
                        Optional.of(MagicAttackKind.WAND_MISSILE);
                default -> Optional.empty();
            };
        }
        return switch (input) {
            case ENTITY_LEFT_CLICK -> Optional.of(MagicAttackKind.STAFF_MELEE);
            case AIR_RIGHT_CLICK, BLOCK_RIGHT_CLICK, ENTITY_RIGHT_CLICK ->
                    Optional.of(MagicAttackKind.STAFF_FIREBALL);
            default -> Optional.empty();
        };
    }
}
