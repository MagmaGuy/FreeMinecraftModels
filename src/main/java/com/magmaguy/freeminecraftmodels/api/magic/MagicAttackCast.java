package com.magmaguy.freeminecraftmodels.api.magic;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * One accepted cast of a {@link MagicAttackBehavior}, from the click until it ends.
 *
 * <p>The cast is active while {@link MagicAttackBehavior#cast} runs and for as long as it has a
 * pending task scheduled through this handle. It ends when {@link #end()} is called, when the
 * behavior returns with no task pending, or when its last task finishes. FreeMinecraftModels also
 * ends it when the behavior's plugin disables or unregisters the behavior, when FreeMinecraftModels
 * reloads its content or shuts down, when the progression integration changes, and when the caster
 * quits or changes world. Ending cancels the pending tasks, runs the {@link #onEnd} callbacks and
 * makes {@link #impact} refuse further impacts.</p>
 *
 * <p>Every method must be called on the server thread. A behavior that tracks its own entities or
 * listeners keeps the cast open with a timeout task and removes them in an {@link #onEnd} callback.</p>
 *
 * <p>New in capability {@value MagicWeaponAPI#ATTACK_BEHAVIORS_CAPABILITY}. The contract is meant
 * to stay stable.</p>
 */
public interface MagicAttackCast {

    /** The cast's identity. Every impact of this cast reaches the damage path under this id. */
    UUID attackId();

    /** The id the running behavior was registered under. */
    NamespacedKey behaviorId();

    Player caster();

    /** A copy of the weapon as it was held when the cast was accepted. */
    ItemStack weapon();

    /** The weapon definition's id: its item file name without the extension. */
    String weaponId();

    /** The weapon's family. A behavior never changes it. */
    MagicWeaponKind weaponKind();

    /** The built-in attack this behavior stands in for on this weapon. */
    MagicAttackKind attackKind();

    /** The living entity the caster clicked, or null when the click met air or a block. */
    LivingEntity clickedTarget();

    /**
     * The attack's power coefficient from the weapon definition, after the weapon's enchantments.
     * {@link #impact} already applies it; it is exposed for visuals and behavior-side scaling.
     */
    double basePower();

    /** The reload FreeMinecraftModels starts when the behavior reports the attack as cast. */
    int reloadTicks();

    /** The weapon definition's reach in blocks. */
    double range();

    /** The weapon definition's projectile speed in blocks per tick. */
    double projectileSpeed();

    /** The weapon definition's projectile lifetime in ticks. */
    int travelTicks();

    /** The weapon definition's area radius in blocks, after the weapon's enchantments. */
    double impactRadius();

    /** The weapon definition's aim-assist half-angle in degrees; 0 means manual aim. */
    double aimAssistDegrees();

    /** How many projectiles the weapon's enchantments ask for; 1 without such an enchantment. */
    int missileCount();

    /** How long the weapon's enchantments ask an impact to burn, in ticks; 0 without one. */
    int ignitionTicks();

    /**
     * Whether an impact on this entity would be authorized now: a living, valid entity other than
     * the caster that the progression integration accepts as a target of this cast.
     */
    boolean isTargetEligible(LivingEntity target);

    /**
     * Optional targeting assistance: the eligible entities inside a cone around the caster's view,
     * ranked the way the built-in wand ranks them, best first. Pass this cast's {@link #range()}
     * and {@link #aimAssistDegrees()} to follow the weapon definition.
     *
     * @param limit the most targets to return, at least 1
     */
    List<LivingEntity> acquireTargets(double range, double aimAssistDegrees, int limit);

    /**
     * Reports one impact on a living entity. FreeMinecraftModels checks the target, then applies the
     * damage through the same path as a built-in hit: the registered progression integration computes
     * it from this cast's snapshot, the hit is attributed to the caster, and the weapon's
     * enchantments and worn item sets answer it. A cast may report any number of impacts, on one
     * target or several; they all carry {@link #attackId()}.
     *
     * @param powerFactor scales this impact against a full-power hit of the attack: 1.0 for a
     * direct hit, lower for splash, chained or glancing impacts. Must be positive and finite.
     * @return true when the damage was applied; false when the cast has ended, the target is not
     * eligible, the factor is invalid, or the hit was refused or cancelled downstream
     */
    boolean impact(LivingEntity target, double powerFactor);

    /**
     * Runs a task once after a delay, as long as the cast is still active then.
     *
     * @param delayTicks ticks to wait, at least 0
     * @return the task, already cancelled when the cast has ended
     */
    Task runLater(long delayTicks, Runnable task);

    /**
     * Runs a task repeatedly until it is cancelled or the cast ends.
     *
     * @param delayTicks ticks before the first run, at least 0
     * @param periodTicks ticks between runs, at least 1
     * @return the task, already cancelled when the cast has ended
     */
    Task runTimer(long delayTicks, long periodTicks, Runnable task);

    /**
     * Registers cleanup that runs exactly once when the cast ends, whichever way it ends. Runs
     * immediately when the cast has already ended.
     */
    void onEnd(Runnable cleanup);

    /** Ends the cast now. Safe to call more than once. */
    void end();

    /** False once the cast has ended. */
    boolean isActive();

    /** A task scheduled through a cast. FreeMinecraftModels cancels it when the cast ends. */
    interface Task {
        void cancel();

        /** True once the task can no longer run: cancelled, finished, or its cast ended. */
        boolean isCancelled();
    }
}
