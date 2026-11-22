package com.magmaguy.freeminecraftmodels.api.magic;

/**
 * A custom staff or wand attack supplied by another plugin.
 *
 * <p>A behavior stands in for one built-in {@link MagicAttackKind} of the weapons it is bound to.
 * FreeMinecraftModels keeps the input, the reload and the damage application; the behavior owns
 * the trajectory, targeting, visuals and timing. Register one through {@link MagicAttackBehaviors}.</p>
 *
 * <p>New in capability {@value MagicWeaponAPI#ATTACK_BEHAVIORS_CAPABILITY}. The contract is meant
 * to stay stable.</p>
 */
@FunctionalInterface
public interface MagicAttackBehavior {

    /**
     * Called on the server thread once FreeMinecraftModels has accepted the attack: the input was
     * routed to this attack, the progression integration allowed it and the weapon was off reload.
     *
     * @return true when the attack was cast, which starts the weapon's reload and swings the
     * caster's arm. Return false to decline, for example when the attack needs a target and found
     * none: nothing is spent and the cast ends.
     */
    boolean cast(MagicAttackCast cast);
}
