package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import lombok.Getter;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Consumer;

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

    private static final HashSet<Player> leftClickCooldownPlayers = new HashSet<>();
    private static final HashSet<Player> rightClickCooldownPlayers = new HashSet<>();
    @Getter
    private static HashMap<Player, Float> attackCooldowns = new HashMap<>();

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
                        if (entity.getHitboxComponent().getObbHitbox().isAABBCollidingWithOBB(proj.getBoundingBox())) {
                            entity.getInteractionComponent().callModeledEntityHitByProjectileEvent(proj);

                            // remove it from our set so we don't double‐hit
                            iter.remove();
                            new BukkitRunnable() {
                                @Override
                                public void run() {
                                    proj.remove();
                                }
                            }.runTask(MetadataHandler.PLUGIN);
                            break;
                        }
                    }
                }
            }
        }.runTaskTimerAsynchronously(MetadataHandler.PLUGIN, 0L, 1L);
    }

    public static void shutdown() {
        activeProjectiles.clear();
        leftClickCooldownPlayers.clear();
        rightClickCooldownPlayers.clear();
        if (projectileDetectionTask != null) projectileDetectionTask.cancel();
    }

    private static void executeLeftClickAttack(Player player) {
        executePlayerInteraction(player, leftClickCooldownPlayers,
                hitEntity -> hitEntity.getInteractionComponent().callLeftClickEvent(player));
    }

    private static void executeRightClickInteraction(Player player) {
        executePlayerInteraction(player, rightClickCooldownPlayers,
                hitEntity -> hitEntity.getInteractionComponent().callRightClickEvent(player));
    }

    /**
     * Generic method to handle player interactions with cooldown management
     *
     * @param player              The player performing the interaction
     * @param cooldownSet         The cooldown set for this type of interaction
     * @param interactionCallback The callback to execute when an entity is hit
     */
    private static void executePlayerInteraction(Player player, HashSet<Player> cooldownSet,
                                                 Consumer<ModeledEntity> interactionCallback) {
        // Check cooldown
        if (cooldownSet.contains(player)) return;

        // Add to cooldown
        cooldownSet.add(player);
        new BukkitRunnable() {
            @Override
            public void run() {
                cooldownSet.remove(player);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 1);

        // Check for hit entity
        Optional<ModeledEntity> hitEntity = OrientedBoundingBox.raytraceFromPlayer(player);

        // If no entity was hit, allow normal processing
        if (hitEntity.isEmpty()) {
            return;
        }

        // Process the hit using the provided callback
        interactionCallback.accept(hitEntity.get());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void playerAnimation(PlayerAnimationEvent event) {
        // Only process arm swings (attacks)
        if (!event.getAnimationType().equals(PlayerAnimationType.ARM_SWING)) {
            return;
        }
        attackCooldowns.put(event.getPlayer(), event.getPlayer().getAttackCooldown());
        executeLeftClickAttack(event.getPlayer());
    }

    @EventHandler
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        executeLeftClickAttack(event.getPlayer());
    }

    @EventHandler
    public void EntityInteractEvent(PlayerInteractEvent event) {
        // Only process right-click actions (both air and block)
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
                event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        executeRightClickInteraction(event.getPlayer());
    }

    @EventHandler
    public void onProjectileCreate(ProjectileLaunchEvent event) {
        activeProjectiles.add(event.getEntity());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onEntityHitEvent(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Projectile projectile) || !RegisterModelEntity.isModelEntity(event.getEntity()))
            return;
        event.setCancelled(true);
        ModeledEntity modeledEntity = null;
        for (ModeledEntity loadedModeledEntity : ModeledEntity.getLoadedModeledEntities()) {
            if (loadedModeledEntity.getUnderlyingEntity() != null && loadedModeledEntity.getUnderlyingEntity().equals(event.getEntity())) {
                modeledEntity = loadedModeledEntity;
                break;
            }
        }

        if (modeledEntity == null) return;

        modeledEntity.getInteractionComponent().callModeledEntityHitByProjectileEvent(projectile);
    }

}