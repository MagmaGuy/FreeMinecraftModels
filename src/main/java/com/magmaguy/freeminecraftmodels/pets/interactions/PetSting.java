package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import com.magmaguy.freeminecraftmodels.pets.PetControl;
import com.magmaguy.freeminecraftmodels.pets.PetManager;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.BoundingBox;

/**
 * Contact sting. A wild pet whose model touches a player stings them (FMM's hitbox contact event); an owned pet stings
 * hostile mobs that come within reach of its model's oriented hitbox, and never a player. Each victim waits out a
 * cooldown before the same pet stings it again; adults play their attack animation as they sting.
 */
public final class PetSting {
    private PetSting() {
    }

    /**
     * FreeMinecraftModels found a player touching a model. For a pet body this replaces FMM's contact attack: the
     * event is cancelled, and a wild pet with a sting stings.
     */
    public static void onContact(ModeledEntityHitboxContactEvent event) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        Entity underlying = event.getEntity().getUnderlyingEntity();
        PetControl control = underlying == null ? null : manager.pet(underlying);
        if (control == null) return;
        event.setCancelled(true);
        PetInteractions.Sting sting = control.species().interactions().sting();
        if (sting == null || !sting.wild() || !control.isWild()) return;
        Player player = event.getPlayer();
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || player.isDead()) return;
        sting(control, PetInteractionState.of(control), player, sting);
    }

    /** An owned pet's look around for hostile mobs touching its model. */
    static void stingMobs(PetControl control, LivingEntity body, PetInteractions.Sting sting, PetInteractionState state) {
        PetManager manager = PetManager.get();
        if (manager == null) return;
        BoundingBox around = body.getBoundingBox().expand(sting.reach() + 1.5D);
        for (Entity entity : body.getWorld().getNearbyEntities(around,
                candidate -> candidate instanceof Enemy && candidate instanceof LivingEntity && !(candidate instanceof Player))) {
            LivingEntity victim = (LivingEntity) entity;
            if (victim.isDead() || manager.pet(victim) != null) continue;
            if (touches(control, body, victim, sting.reach())) sting(control, state, victim, sting);
        }
    }

    /** Whether the victim's box, grown by the reach, meets the model's oriented hitbox (or the body's box). */
    private static boolean touches(PetControl control, LivingEntity body, LivingEntity victim, double reach) {
        BoundingBox victimBox = victim.getBoundingBox().expand(reach);
        DynamicEntity modeled = control.modeled();
        if (modeled != null && !modeled.isRemoved()) {
            OrientedBoundingBox hitbox = modeled.getHitboxComponent().getObbHitbox();
            if (hitbox != null) return hitbox.intersectsAABBCoherently(victimBox);
        }
        return body.getBoundingBox().overlaps(victimBox);
    }

    private static void sting(PetControl control, PetInteractionState state, LivingEntity victim, PetInteractions.Sting sting) {
        long now = PetInteractionRuntime.ticks();
        Long readyAt = state.stingReadyAt.get(victim.getUniqueId());
        if (readyAt != null && now < readyAt) return;
        if (state.stingReadyAt.size() > 32) state.stingReadyAt.values().removeIf(at -> at <= now);
        state.stingReadyAt.put(victim.getUniqueId(), now + sting.cooldownTicks());
        if (sting.effect() != null)
            victim.addPotionEffect(new PotionEffect(sting.effect(), sting.durationTicks(), sting.amplifier(), false, true, true));
        if (sting.damage() > 0D) victim.damage(sting.damage(), control.body());
        Location at = victim.getLocation().add(0D, victim.getHeight() * 0.5D, 0D);
        victim.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, at, 8, 0.25D, 0.3D, 0.25D, 0.05D);
        victim.getWorld().playSound(at, Sound.ENTITY_BEE_STING, 0.6F, 1.6F);
        DynamicEntity modeled = control.modeled();
        // Babies have no attack animation.
        if (!control.isBaby() && modeled != null && !modeled.isRemoved() && modeled.hasAnimation("attack"))
            modeled.playAnimation("attack", true, false);
    }
}
