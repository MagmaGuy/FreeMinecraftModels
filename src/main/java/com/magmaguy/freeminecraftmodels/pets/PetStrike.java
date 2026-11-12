package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OBBHitDetection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;

/**
 * The hit a pet's sting or strike deals, and whether it went through.
 * <p>
 * FreeMinecraftModels cancels a mob's hit on anything that wears a model unless the hit is marked as its own (see
 * {@link OBBHitDetection#applyDamage}): a model's body is hit through its model. A pet's body is a mob, so its hits on
 * a wild pet, on another player's pet and on any other modeled mob were all cancelled, while the pet played its swing:
 * a pet defending its owner against a wild pet chased it and swung at it for as long as it stayed an attacker, and
 * never hurt it. A pet's hit is now dealt as FreeMinecraftModels' own, so it reaches the damage event like any mob's
 * hit, where the server and other plugins still decide: a pet that cannot be hurt, a protected region, PvP rules.
 * <p>
 * Whether it went through is read off that event, so the pet swings only at what it hit, and gives up on what it
 * cannot hurt (see {@link PetTruce}).
 */
final class PetStrike {
    // The hit being dealt, and whether its damage event got through every listener. Server thread only.
    private static Entity striker, struck;
    private static boolean wentThrough;

    private PetStrike() {
    }

    /**
     * Whether a hit on a victim this far into its hurt immunity can count: Minecraft ignores a hit no harder than
     * the last while more than half of the immunity a hit leaves is still running, without a damage event. Two pets
     * on one attacker land inside each other's immunity all the time; such a hit is tried again later, and is no
     * sign that the victim cannot be hurt.
     */
    static boolean ready(int noDamageTicks, int maximumNoDamageTicks) {
        return noDamageTicks <= maximumNoDamageTicks / 2;
    }

    /**
     * Deals {@code damage} to {@code victim} as {@code body}'s hit. True when it went through: its damage event
     * reached the end uncancelled with damage left to deal, or the victim lost health or absorption.
     */
    static boolean deal(LivingEntity body, LivingEntity victim, double damage) {
        double health = victim.getHealth(), absorption = victim.getAbsorptionAmount();
        Entity outerStriker = striker, outerStruck = struck;
        boolean outerWentThrough = wentThrough, outerBypass = OBBHitDetection.applyDamage;
        striker = body;
        struck = victim;
        wentThrough = false;
        OBBHitDetection.applyDamage = true;
        boolean landed;
        try {
            victim.damage(damage, body);
            landed = landed(wentThrough, victim.isDead(), health, victim.getHealth(), absorption, victim.getAbsorptionAmount());
        } finally {
            OBBHitDetection.applyDamage = outerBypass;
            striker = outerStriker;
            struck = outerStruck;
            wentThrough = outerWentThrough;
        }
        if (landed) {
            // A modeled victim flashes as it does under any other hit that reaches it.
            ModeledEntity modeled = ModeledEntity.getModeledEntity(victim);
            if (modeled != null && modeled.getSkeleton() != null) modeled.getSkeleton().tint();
        }
        return landed;
    }

    /** {@link #deal}'s verdict from what the hit left behind. */
    static boolean landed(boolean eventWentThrough, boolean dead, double healthBefore, double healthAfter,
                          double absorptionBefore, double absorptionAfter) {
        return eventWentThrough || dead || healthAfter < healthBefore || absorptionAfter < absorptionBefore;
    }

    /** A damage event got through every listener with this much damage left to deal: was it the hit being dealt? */
    static void wentThrough(Entity damager, Entity victim, double finalDamage) {
        if (striker != null && striker.equals(damager) && struck.equals(victim) && finalDamage > 0D) wentThrough = true;
    }
}
