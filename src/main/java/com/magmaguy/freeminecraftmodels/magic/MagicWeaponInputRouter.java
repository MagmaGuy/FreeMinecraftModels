package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;

import java.util.Objects;
import java.util.Optional;

/** Pure control contract shared by the Bukkit listener and focused regression tests. */
final class MagicWeaponInputRouter {
    private MagicWeaponInputRouter() {
    }

    /**
     * A custom behavior standing in for the staff strike is also cast by a left click that met no
     * entity, so it can be aimed into empty space. The built-in strike keeps needing an entity.
     */
    static Optional<MagicAttackKind> route(MagicWeaponKind weapon, MagicInput input, boolean staffStrikeHasBehavior) {
        Optional<MagicAttackKind> routed = route(weapon, input);
        if (routed.isEmpty() && staffStrikeHasBehavior && weapon == MagicWeaponKind.STAFF
                && (input == MagicInput.AIR_LEFT_CLICK || input == MagicInput.BLOCK_LEFT_CLICK))
            return Optional.of(MagicAttackKind.STAFF_MELEE);
        return routed;
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
