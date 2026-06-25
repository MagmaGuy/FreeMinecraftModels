package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import lombok.Getter;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Trident;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.projectiles.ProjectileSource;
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
    // When true, the HIGHEST-priority onEntityHitEvent listener below skips its
    // projectile-redirect logic. Set by callers that are themselves applying a
    // projectile-damage event on a modeled entity's underlying LivingEntity to
    // prevent the redirect from cancelling and re-firing the hit event in a loop.
    public static boolean bypassProjectileRedirect = false;

    // Diagnostic: logs every projectile->modeled-entity hit with the
    // detection path that caught it, the projectile velocity, and — in the damage
    // chokepoint (InteractionComponent) — a full call stack + HP delta. Lets us see
    // double-hits and velocity collapse directly from console instead of inferring.
    // Flip to false / remove once the projectile-damage path is confirmed.
    public static boolean DEBUG_PROJECTILE_HITS = false;

    private static Set<Projectile> activeProjectiles = ConcurrentHashMap.newKeySet();
    // Cached shooter UUIDs keyed by projectile UUID. Avoids re-resolving the
    // shooter Entity (a non-trivial NMS lookup) on every hit-check tick.
    // Block-source projectiles (dispensers) are simply absent from this map.
    private static final Map<UUID, UUID> projectileShooterIds = new ConcurrentHashMap<>();
    // Last-tick world position of each tracked projectile, keyed by projectile UUID.
    // Used for swept (segment) hit detection: a fast arrow moves several blocks per
    // tick, so testing only its instantaneous AABB each tick lets it tunnel through a
    // thin model OBB between samples. We instead ray-cast the segment from this stored
    // position to the projectile's current position against the OBB.
    private static final Map<UUID, org.bukkit.util.Vector> projectileLastPositions = new ConcurrentHashMap<>();
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
        if (event.getDamager() instanceof Player player) {
            ModeledEntity modeledEntity = ModeledEntity.getLoadedModeledEntitiesWithUnderlyingEntities().get(event.getEntity());
            if (modeledEntity != null) modeledEntity.getInteractionComponent().callLeftClickEvent(player);
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
            hitEntity.getInteractionComponent().callLeftClickEvent(event.getPlayer());
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
                if (activeProjectiles.isEmpty()) return;

                // Bucket projectiles by world and drop invalid ones in one pass.
                Map<World, List<Projectile>> projectilesByWorld = null;
                Iterator<Projectile> iter = activeProjectiles.iterator();
                while (iter.hasNext()) {
                    Projectile proj = iter.next();
                    if (!proj.isValid()) {
                        iter.remove();
                        projectileShooterIds.remove(proj.getUniqueId());
                        projectileLastPositions.remove(proj.getUniqueId());
                        continue;
                    }
                    if (projectilesByWorld == null) projectilesByWorld = new HashMap<>(4);
                    projectilesByWorld.computeIfAbsent(proj.getWorld(), w -> new ArrayList<>()).add(proj);
                }
                if (projectilesByWorld == null) return;

                Set<Projectile> hitThisTick = null;
                List<Projectile> removeAfterTick = null;

                HashSet<ModeledEntity> allEntities = ModeledEntityManager.getAllEntities();

                for (Map.Entry<World, List<Projectile>> entry : projectilesByWorld.entrySet()) {
                    World world = entry.getKey();
                    List<Projectile> worldProjectiles = entry.getValue();

                    // Per-world entity snapshot so the inner projectile loop doesn't
                    // re-filter world membership for every entity/projectile pair.
                    List<ModeledEntity> worldEntities = null;
                    for (ModeledEntity entity : allEntities) {
                        if (entity.getWorld() == null) continue;
                        if (!entity.getWorld().equals(world)) continue;
                        if (worldEntities == null) worldEntities = new ArrayList<>();
                        worldEntities.add(entity);
                    }
                    if (worldEntities == null) continue;

                    // Outer = entities (typically larger), inner = projectiles. This
                    // keeps the OBB update cost (getObbHitbox()) at one call per
                    // entity per tick instead of one per (entity, projectile) pair.
                    for (ModeledEntity entity : worldEntities) {
                        Entity underlying = entity.getUnderlyingEntity();
                        UUID underlyingId = underlying != null ? underlying.getUniqueId() : null;
                        OrientedBoundingBox obb = entity.getHitboxComponent().getObbHitbox();

                        for (Projectile proj : worldProjectiles) {
                            if (hitThisTick != null && hitThisTick.contains(proj)) continue;

                            // Compare cached shooter UUID instead of re-resolving the
                            // shooter Entity every tick.
                            if (underlyingId != null) {
                                UUID shooterId = projectileShooterIds.get(proj.getUniqueId());
                                if (shooterId != null && shooterId.equals(underlyingId)) continue;
                            }

                            org.bukkit.util.BoundingBox projAabb = proj.getBoundingBox();
                            // Stage 1 — instantaneous overlap: cheap world-axis-aligned
                            // reject first, then exact SAT (the cheap test over-reports
                            // for rotated/long OBBs). Catches slow or resting projectiles
                            // whose body currently overlaps the OBB.
                            boolean hit = obb.quickAabbReject(projAabb) && obb.intersectsAABB(projAabb);
                            // Stage 2 — swept segment: if the instantaneous sample missed,
                            // ray-cast the path traversed since last tick. A full-draw arrow
                            // moves ~3 blocks/tick, so a single sample lands before the OBB
                            // one tick and past it the next, tunnelling straight through.
                            if (!hit) hit = sweptProjectileHit(obb, proj);
                            if (!hit) continue;

                            if (DEBUG_PROJECTILE_HITS)
                                com.magmaguy.magmacore.util.Logger.warn("[FMM-ProjTrace] PATH=OBB-detection caught proj="
                                        + proj.getUniqueId() + " type=" + proj.getType()
                                        + " vel=" + String.format("%.4f", proj.getVelocity().length())
                                        + " vs entity=" + (underlying != null ? underlying.getType() : "?"));

                            try {
                                entity.getInteractionComponent().callModeledEntityHitByProjectileEvent(proj);
                            } catch (Throwable throwable) {
                                com.magmaguy.magmacore.util.Logger.warn("[FMM-ProjTrace] modeled projectile hit listener failed for proj="
                                        + proj.getUniqueId() + " type=" + proj.getType()
                                        + " vs entity=" + (underlying != null ? underlying.getType() : "?")
                                        + ": " + throwable.getClass().getSimpleName()
                                        + " " + (throwable.getMessage() == null ? "" : throwable.getMessage()));
                                throwable.printStackTrace();
                            }

                            if (hitThisTick == null) hitThisTick = new HashSet<>();
                            hitThisTick.add(proj);
                            activeProjectiles.remove(proj);
                            projectileShooterIds.remove(proj.getUniqueId());
                            projectileLastPositions.remove(proj.getUniqueId());
                            if (removeAfterTick == null) removeAfterTick = new ArrayList<>();
                            removeAfterTick.add(proj);
                        }
                    }
                }

                if (removeAfterTick != null) {
                    final List<Projectile> toRemove = removeAfterTick;
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            for (Projectile p : toRemove) {
                                // Tridents are intentionally left alive so vanilla
                                // pickup, Loyalty return, and Riptide all keep
                                // working. The underlying entity hitbox may still
                                // register a second hit; that's an accepted
                                // tradeoff until/unless it surfaces as a problem.
                                if (p instanceof Trident) continue;
                                p.remove();
                            }
                        }
                    }.runTask(MetadataHandler.PLUGIN);
                }

                // Snapshot the current position of every still-tracked projectile so
                // next tick's swept test has the correct segment start. Hits and
                // invalids were already removed from activeProjectiles above, so only
                // survivors are updated here.
                for (Projectile p : activeProjectiles) {
                    projectileLastPositions.put(p.getUniqueId(), p.getLocation().toVector());
                }
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L); // todo: somehow can't be async due to getting the entity that fired the projectile. Odd.
    }

    public static void shutdown() {
        activeProjectiles.clear();
        projectileShooterIds.clear();
        projectileLastPositions.clear();
        leftClickCooldownPlayers.clear();
        rightClickCooldownPlayers.clear();
        attackCooldowns.clear();
        applyDamage = false;
        bypassProjectileRedirect = false;
        if (projectileDetectionTask != null) {
            projectileDetectionTask.cancel();
            projectileDetectionTask = null;
        }
    }

    /**
     * Swept (segment) hit test: does the path the projectile traversed since the
     * last detection tick intersect the given OBB? Reconstructs the segment from
     * {@link #projectileLastPositions} (start) to the projectile's current
     * position (end) and ray-casts it against the OBB. Returns false when there
     * is no stored previous position yet, or the projectile did not move (the
     * instantaneous AABB test already covers the stationary case).
     */
    private static boolean sweptProjectileHit(OrientedBoundingBox obb, Projectile proj) {
        org.bukkit.util.Vector last = projectileLastPositions.get(proj.getUniqueId());
        if (last == null) return false;
        org.bukkit.util.Vector cur = proj.getLocation().toVector();
        double dx = cur.getX() - last.getX();
        double dy = cur.getY() - last.getY();
        double dz = cur.getZ() - last.getZ();
        double segLenSq = dx * dx + dy * dy + dz * dz;
        if (segLenSq < 1e-8) return false;
        double segLen = Math.sqrt(segLenSq);
        double inv = 1.0 / segLen;
        double t = obb.rayIntersection(
                last.getX(), last.getY(), last.getZ(),
                dx * inv, dy * inv, dz * inv,
                segLen);
        return t >= 0 && t <= segLen;
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
        Projectile proj = event.getEntity();
        activeProjectiles.add(proj);
        projectileLastPositions.put(proj.getUniqueId(), proj.getLocation().toVector());
        ProjectileSource shooter = proj.getShooter();
        if (shooter instanceof Entity shooterEntity) {
            projectileShooterIds.put(proj.getUniqueId(), shooterEntity.getUniqueId());
        }
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.LOW)
    public void onEntityHitEvent(EntityDamageByEntityEvent event) {
        // Redirect native projectile hits on the underlying entity before combat
        // plugins process them. If this waits until HIGHEST, plugins that listen at
        // NORMAL can apply their own damage/display once, then this redirect fires a
        // modeled hit that applies it again when the projectile also intersects the
        // model OBB.
        //
        // FMM-initiated projectile damage sets this flag so we don't cancel and
        // re-route the very event we just fired.
        if (bypassProjectileRedirect) return;
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

        if (DEBUG_PROJECTILE_HITS)
            com.magmaguy.magmacore.util.Logger.warn("[FMM-ProjTrace] PATH=vanilla-hitbox EntityDamageByEntityEvent redirect proj="
                    + projectile.getUniqueId() + " type=" + projectile.getType()
                    + " vel=" + String.format("%.4f", projectile.getVelocity().length()));

        modeledEntity.getInteractionComponent().callModeledEntityHitByProjectileEvent(projectile);
    }

}
