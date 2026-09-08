package com.magmaguy.freeminecraftmodels.api.magic;

/**
 * Optional progression adapter for FMM-owned magic attacks.
 *
 * <p>The resolver calculates the final damage and invokes the supplied one-shot application.
 * It must not damage the target directly. FMM remains the sole input, projectile, collision and
 * damage-application owner.</p>
 */
@FunctionalInterface
public interface MagicAttackResolver {

    /** Aim-assist tier reserved for an integration's premium targets (for example elites). */
    int PRIORITY_PREMIUM = 0;
    int PRIORITY_MOB = 1;
    int PRIORITY_HOSTILE = PRIORITY_MOB;
    int PRIORITY_PLAYER = 2;
    int PRIORITY_PASSIVE = PRIORITY_MOB;

    void resolve(MagicAttackRequest request, MagicDamageApplication application);

    /** Called once before casting, while the held item is still available for item policy. */
    default boolean canAttack(org.bukkit.entity.Player player, org.bukkit.inventory.ItemStack weapon,
                              MagicAttackKind attackKind) {
        return true;
    }

    /**
     * Optionally narrows FMM's generic living-target rules for an integrating combat system.
     * FMM still performs acquisition, flight, collision and the eventual damage application.
     */
    default boolean isTargetEligible(MagicTargetRequest request) {
        return true;
    }

    /**
     * Optionally ranks eligible living targets for aim assist. Lower tiers are acquired before
     * any aim score is compared, so all mobs, including passive mobs, beat players.
     */
    default int targetPriority(MagicTargetRequest request) {
        return defaultTargetPriority(request.target());
    }

    /** Plugin-neutral tier classification shared by the engine and integrations. */
    static int defaultTargetPriority(org.bukkit.entity.LivingEntity target) {
        if (target instanceof org.bukkit.entity.Player) return PRIORITY_PLAYER;
        return PRIORITY_MOB;
    }
}
