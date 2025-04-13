package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

import java.util.Optional;

/**
 * Handles hit detection for modeled entities using Oriented Bounding Boxes.
 * This system can detect hits even when the model is rotated.
 */
public class OBBHitDetection implements Listener {

    public static boolean applyDamage = false;

    /**
     * Handles player arm swing animations to detect attacks
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerAnimation(PlayerAnimationEvent event) {
        // Only process arm swings (attacks)
        if (!event.getAnimationType().equals(PlayerAnimationType.ARM_SWING)) {
            return;
        }

        // Check for hit entity
        Optional<ModeledEntity> hitEntity = OrientedBoundingBoxRayTracer.raytraceFromPlayer(event.getPlayer());

        // If no entity was hit, allow normal processing
        if (hitEntity.isEmpty()) {
            return;
        }

        // Process the hit
        event.setCancelled(true);
        hitEntity.get().damage(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void EntityDamageByEntityEvent(EntityDamageByEntityEvent event) {
        if (!RegisterModelEntity.isModelEntity(event.getEntity()) &&
                !RegisterModelEntity.isModelArmorStand(event.getEntity()) ||
                !RegisterModelEntity.isModelEntity(event.getDamager()) &&
                        !RegisterModelEntity.isModelArmorStand(event.getDamager())) return;
        if (applyDamage) {
            applyDamage = false;
            return;
        }
        event.setCancelled(true);
        Logger.debug("cancelled damaged by entity");
    }

    //todo: this does not currently account for projectiles

}