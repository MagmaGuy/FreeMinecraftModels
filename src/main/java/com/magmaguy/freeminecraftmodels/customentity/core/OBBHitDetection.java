package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

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
                !RegisterModelEntity.isModelEntity(event.getDamager())) return;
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
                processPendingAttacks();

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
                        OrientedBoundingBox obb = entity.getHitboxComponent().getObbHitbox().update(entity.getLocation());
                        if (obb.containsPoint(proj.getLocation())) {
                            // hit! deal damage and stop scanning this projectile
                            if (!entity.damage(proj)) break;

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
        if (projectileDetectionTask != null) projectileDetectionTask.cancel();
    }

    // Add these fields to your class
    private static final Map<UUID, Integer> swingDelay = new HashMap<>();
    private static final Map<UUID, Integer> timeout = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerAnimation(PlayerAnimationEvent event) {
        // Only process arm swings (attacks)
        if (!event.getAnimationType().equals(PlayerAnimationType.ARM_SWING)) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();

        // Set swing delay - we'll check in 2 ticks if this was actually a left click
        swingDelay.put(playerId, 2);
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        // Set timeout to indicate a right-click occurred
        timeout.put(playerId, 4);
    }

    @EventHandler
    public void EntityInteractEvent(PlayerInteractEvent event) {
        // Only process right-click actions (both air and block)
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        UUID playerId = event.getPlayer().getUniqueId();

        // Set timeout to indicate a right-click occurred
        timeout.put(playerId, 4);

        // Check for hit entity using raytrace
        Optional<ModeledEntity> hitEntity = OrientedBoundingBox.raytraceFromPlayer(event.getPlayer());

        // If no entity was hit, allow normal processing
        if (hitEntity.isEmpty()) {
            return;
        }

        // Cancel the event to prevent interaction with blocks behind the entity
        event.setCancelled(true);

        // Trigger the right-click event on the modeled entity
        hitEntity.get().getInteractionComponent().callRightClickEvent(event.getPlayer());
    }

    // Add this method to be called every tick (you'll need a scheduler)
    public static void processPendingAttacks() {
        Iterator<Map.Entry<UUID, Integer>> swingIter = swingDelay.entrySet().iterator();
        while (swingIter.hasNext()) {
            Map.Entry<UUID, Integer> entry = swingIter.next();
            UUID playerId = entry.getKey();
            int delay = entry.getValue() - 1;

            if (delay <= 0) {
                // Check if timeout is 0 (no right-click occurred)
                if (timeout.getOrDefault(playerId, 0) == 0) {
                    // This was a genuine left click - execute the attack
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null) {
                        executeLeftClickAttack(player);
                    }
                }
                swingIter.remove();
            } else {
                entry.setValue(delay);
            }
        }

        // Decrement timeouts
        Iterator<Map.Entry<UUID, Integer>> timeoutIter = timeout.entrySet().iterator();
        while (timeoutIter.hasNext()) {
            Map.Entry<UUID, Integer> entry = timeoutIter.next();
            int time = entry.getValue() - 1;
            if (time <= 0) {
                timeoutIter.remove();
            } else {
                entry.setValue(time);
            }
        }
    }

    private static void executeLeftClickAttack(Player player) {
        // Check for hit entity
        Optional<ModeledEntity> hitEntity = OrientedBoundingBox.raytraceFromPlayer(player);

        // If no entity was hit, allow normal processing
        if (hitEntity.isEmpty()) {
            return;
        }

        // Process the hit
        hitEntity.get().getInteractionComponent().callLeftClickEvent(player);
        hitEntity.get().damage(player);
    }

    @EventHandler
    public void onProjectileCreate(ProjectileLaunchEvent event) {
        activeProjectiles.add(event.getEntity());
    }

}