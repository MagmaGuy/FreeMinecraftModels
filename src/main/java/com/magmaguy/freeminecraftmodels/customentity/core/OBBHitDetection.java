package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import lombok.Getter;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Handles hit detection for modeled entities using Oriented Bounding Boxes.
 * This system can detect hits even when the model is rotated.
 */
public class OBBHitDetection implements Listener {

    public static boolean applyDamage = false;

    private static Set<Projectile> activeProjectiles = ConcurrentHashMap.newKeySet();
    private static BukkitTask projectileDetectionTask = null;

    @EventHandler(priority = EventPriority.LOWEST)
    public void EntityDamageByEntityEvent(EntityDamageByEntityEvent event) {
        if (
                !RegisterModelEntity.isModelEntity(event.getEntity())
                        || (event.getDamageSource().getDamageType().equals(DamageType.MOB_ATTACK)
                                &&!RegisterModelEntity.isModelEntity(event.getDamager()))
        ) return;
        if (applyDamage) {
            applyDamage = false;
            return;
        }
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void blockBreakEvent(BlockBreakEvent event) {
        // Ensure player and block are in the same world before calculating distance
        if (!event.getPlayer().getWorld().equals(event.getBlock().getWorld())) return;

        // Get the block location and calculate distance to player
        double blockDistance = event.getPlayer().getEyeLocation().distance(
                event.getBlock().getLocation().add(0.5, 0.5, 0.5)); // Center of block

        // Check for hit entity
        Optional<ModeledEntity> hitEntityOpt = OrientedBoundingBox.raytraceFromPlayer(event.getPlayer());

        // If no entity was hit, allow the block break
        if (hitEntityOpt.isEmpty()) return;

        // Get the hit entity and calculate its distance
        ModeledEntity hitEntity = hitEntityOpt.get();

        // Ensure hit entity is in the same world before calculating distance
        if (hitEntity.getLocation() == null || !event.getPlayer().getWorld().equals(hitEntity.getLocation().getWorld()))
            return;

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

                        if (proj.getShooter() != null && entity.getUnderlyingEntity() != null && proj.getShooter().equals(entity.getUnderlyingEntity())) continue;

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
        }.runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L); // todo: somehow can't be async due to getting the entity that fired the projectile. Odd.
    }

    public static void shutdown() {
        activeProjectiles.clear();
        leftClickCooldownPlayers.clear();
        rightClickCooldownPlayers.clear();
        attackCooldowns.clear();
        if (projectileDetectionTask != null) {
            projectileDetectionTask.cancel();
            projectileDetectionTask = null;
        }
    }

    private static void executeLeftClickAttack(Player player) {
        executePlayerInteraction(player, leftClickCooldownPlayers,
                hitEntity -> hitEntity.getInteractionComponent().callLeftClickEvent(player));
    }

    private static void executeRightClickInteraction(Player player) {
        executePlayerInteraction(player, rightClickCooldownPlayers,
                hitEntity -> hitEntity.getInteractionComponent().callRightClickEvent(player));
    }

    private static void executePlayerInteraction(Player player, HashSet<Player> cooldownSet,
                                                 Consumer<ModeledEntity> interactionCallback) {
        if (cooldownSet.contains(player)) return;
        cooldownSet.add(player);
        new BukkitRunnable() {
            @Override
            public void run() {
                cooldownSet.remove(player);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 1);

        Optional<ModeledEntity> hitEntity = OrientedBoundingBox.raytraceFromPlayer(player);
        if (hitEntity.isEmpty()) return;
        interactionCallback.accept(hitEntity.get());
    }

    // ignoreCancelled=false on purpose: LEFT_CLICK_AIR / RIGHT_CLICK_AIR arrive
    // with useInteractedBlock=DENY (there's no block to "use"), and Bukkit
    // reports such events as cancelled — so ignoreCancelled=true would silently
    // skip every air click, breaking hit detection on any entity without a
    // block behind it. Suppress only when BOTH useBlock and useItem are DENY,
    // which is the real "another plugin cancelled this" signal.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.useInteractedBlock() == Event.Result.DENY
                && event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            attackCooldowns.put(event.getPlayer(), event.getPlayer().getAttackCooldown());
            executeLeftClickAttack(event.getPlayer());
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            executeRightClickInteraction(event.getPlayer());
        }
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