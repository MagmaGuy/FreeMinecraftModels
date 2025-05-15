package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
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
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockBreakEvent(BlockBreakEvent event) {
        // Get the block location and calculate distance to player
        double blockDistance = event.getPlayer().getEyeLocation().distance(
                event.getBlock().getLocation().add(0.5, 0.5, 0.5)); // Center of block

        // Check for hit entity
        Optional<ModeledEntity> hitEntityOpt = OrientedBoundingBoxRayTracer.raytraceFromPlayer(event.getPlayer());

        // If no entity was hit, allow the block break
        if (hitEntityOpt.isEmpty()) return;

        // Get the hit entity and calculate its distance
        ModeledEntity hitEntity = hitEntityOpt.get();
        double entityDistance = event.getPlayer().getEyeLocation().distance(hitEntity.getLocation());

        // Only cancel if the entity is closer than or at the same distance as the block
        if (entityDistance <= blockDistance) {
            event.setCancelled(true);
            Logger.debug("Cancelled block break - entity in the way at distance " + entityDistance +
                    " (block at " + blockDistance + ")");
        } else {
            Logger.debug("Allowing block break - entity is behind block (entity: " +
                    entityDistance + ", block: " + blockDistance + ")");
        }
    }

    //todo: this does not currently account for projectiles

}