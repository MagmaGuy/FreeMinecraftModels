package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;

/**
 * Handles hit detection for modeled entities using Oriented Bounding Boxes.
 * This system can detect hits even when the model is rotated.
 */
public class OBBHitDetection implements Listener {

    public static boolean applyDamage = false;

    private static HashSet<Projectile> activeProjectiles = new HashSet<>();
    private static BukkitTask projectileDetectionTask = null;

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
        Optional<ModeledEntity> hitEntityOpt = OrientedBoundingBox.raytraceFromPlayer(event.getPlayer());

        // If no entity was hit, allow the block break
        if (hitEntityOpt.isEmpty()) return;

        // Get the hit entity and calculate its distance
        ModeledEntity hitEntity = hitEntityOpt.get();
        double entityDistance = event.getPlayer().getEyeLocation().distance(hitEntity.getLocation());

        // Only cancel if the entity is closer than or at the same distance as the block
        if (entityDistance <= blockDistance) {
            event.setCancelled(true);
        }
    }

    public static void startProjectileDetection() {
        projectileDetectionTask = new BukkitRunnable() {
            @Override
            public void run() {
                Iterator<Projectile> iter = activeProjectiles.iterator();
                while (iter.hasNext()) {
                    Projectile proj = iter.next();

                    // 1) drop invalid projectiles
                    if (!proj.isValid()) {
                        iter.remove();
                        continue;
                    }

                    // 2) scan against every modeled entity in the same world
                    for (ModeledEntity entity : ModeledEntityManager.getAllEntities()) {
                        if (entity.getWorld() == null ||
                                !entity.getWorld().equals(proj.getWorld())) {
                            continue;
                        }

                        // update the OBB to the entity's current position/orientation
                        OrientedBoundingBox obb = entity.getObbHitbox().update(entity.getLocation());
                        if (obb.containsPoint(proj.getLocation())) {
                            // hit! deal damage and stop scanning this projectile
                            if (!entity.damageByProjectile(proj)) break;

                            // remove it from our set so we don't double‐hit
                            iter.remove();
                            proj.remove();
                            break;
                        }
                    }
                }
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L);
    }

    public static void shutdown() {
        activeProjectiles.clear();
        projectileDetectionTask.cancel();
    }

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
        Optional<ModeledEntity> hitEntity = OrientedBoundingBox.raytraceFromPlayer(event.getPlayer());

        // If no entity was hit, allow normal processing
        if (hitEntity.isEmpty()) {
            return;
        }

        // Process the hit
        event.setCancelled(true);
        hitEntity.get().triggerLeftClickEvent(event.getPlayer());
        hitEntity.get().damageByLivingEntity(event.getPlayer());
    }

    @EventHandler
    public void EntityInteractEvent(PlayerInteractEvent event) {
        // Only process right-click actions (both air and block)
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // Check for hit entity using raytrace
        Optional<ModeledEntity> hitEntity = OrientedBoundingBox.raytraceFromPlayer(event.getPlayer());

        // If no entity was hit, allow normal processing
        if (hitEntity.isEmpty()) {
            return;
        }

        // Cancel the event to prevent interaction with blocks behind the entity
        event.setCancelled(true);

        // Trigger the right-click event on the modeled entity
        hitEntity.get().triggerRightClickEvent(event.getPlayer());
    }

    @EventHandler
    public void onProjectileCreate(ProjectileLaunchEvent event) {
        activeProjectiles.add(event.getEntity());
    }

}