package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.AbstractArrow;
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

    private static final Set<Projectile> activeProjectiles = ConcurrentHashMap.newKeySet();
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
    // One routing ledger is shared by the OBB sweep and the native underlying-
    // entity redirect. The target is part of the key so Piercing arrows can hit
    // several modeled entities without either path double-firing the same pair.
    private static final Set<ProjectileTarget> routedProjectileHits = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Integer> projectileHitCounts = new ConcurrentHashMap<>();
    private static final Set<UUID> projectilesPendingRemoval = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> projectilesRetiredInBlock = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> piercingFlightRestores = ConcurrentHashMap.newKeySet();
    private static BukkitTask projectileDetectionTask = null;

    private record ProjectileTarget(UUID projectileId, UUID targetId) {
    }

    private record ProjectileHitCandidate(ModeledEntity entity, double distance) {
    }

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
            ModeledEntity modeledEntity = ModeledEntity.getModeledEntity(event.getEntity());
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

    // UUID-keyed (not Player) so no Player references are retained, and cleaned
    // up in onPlayerQuit in case the 1-tick removal task never fires (shutdown).
    private static final HashSet<UUID> leftClickCooldownPlayers = new HashSet<>();
    private static final HashSet<UUID> rightClickCooldownPlayers = new HashSet<>();
    @Getter
    private static HashMap<UUID, Float> attackCooldowns = new HashMap<>();

    public static void startProjectileDetection() {
        pauseProjectileDetection();
        projectileDetectionTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (activeProjectiles.isEmpty()) return;

                // Bucket projectiles by world and drop invalid ones in one pass.
                Map<World, List<Projectile>> projectilesByWorld = null;
                Set<Projectile> retireAfterFinalBlockSweep = null;
                Iterator<Projectile> iter = activeProjectiles.iterator();
                while (iter.hasNext()) {
                    Projectile proj = iter.next();
                    if (!proj.isValid()) {
                        iter.remove();
                        clearProjectileState(proj.getUniqueId());
                        continue;
                    }
                    if (projectilesRetiredInBlock.contains(proj.getUniqueId())) continue;
                    boolean embeddedArrow = proj instanceof AbstractArrow arrow && arrow.isInBlock();
                    // Consumed tridents stay alive for vanilla pickup/Loyalty, and
                    // piercing arrows stay alive after spending their modeled-hit
                    // budget. Retain both only for validity cleanup; neither can
                    // produce another modeled hit, so exclude them from the
                    // projectile x model sweep.
                    if (projectilesPendingRemoval.contains(proj.getUniqueId()) ||
                            hasExhaustedModeledTargetBudget(proj)) continue;
                    // Bukkit keeps arrows and tridents valid while they are embedded
                    // so players can pick them up (and Loyalty can return tridents).
                    // Include their last travelled segment in this sweep, then retire
                    // only FMM's routing state after candidate dispatch below.
                    if (embeddedArrow) {
                        if (retireAfterFinalBlockSweep == null)
                            retireAfterFinalBlockSweep = new HashSet<>();
                        retireAfterFinalBlockSweep.add(proj);
                    }
                    if (projectilesByWorld == null) projectilesByWorld = new HashMap<>(4);
                    projectilesByWorld.computeIfAbsent(proj.getWorld(), w -> new ArrayList<>()).add(proj);
                }
                if (projectilesByWorld == null) return;

                // Iterate the live registry directly — it is a concurrent set, so the
                // per-tick defensive copy ModeledEntityManager.getAllEntities() makes is
                // unnecessary here. The API wrapper keeps copying for external callers.
                Set<ModeledEntity> allEntities = ModeledEntity.getLoadedModeledEntities();

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

                    Map<Projectile, List<ProjectileHitCandidate>> candidatesByProjectile = new HashMap<>();

                    // Outer = entities (typically larger), inner = projectiles. This
                    // keeps the OBB update cost (getObbHitbox()) at one call per
                    // entity per tick instead of one per (entity, projectile) pair.
                    for (ModeledEntity entity : worldEntities) {
                        Entity underlying = entity.getUnderlyingEntity();
                        UUID underlyingId = underlying != null ? underlying.getUniqueId() : null;
                        OrientedBoundingBox obb = entity.getHitboxComponent().getObbHitbox();

                        for (Projectile proj : worldProjectiles) {
                            // Compare cached shooter UUID instead of re-resolving the
                            // shooter Entity every tick.
                            if (underlyingId != null) {
                                UUID shooterId = projectileShooterIds.get(proj.getUniqueId());
                                if (shooterId != null && shooterId.equals(underlyingId)) continue;
                            }

                            double distance = projectileIntersectionDistance(obb, proj);
                            if (distance < 0) continue;

                            candidatesByProjectile
                                    .computeIfAbsent(proj, ignored -> new ArrayList<>())
                                    .add(new ProjectileHitCandidate(entity, distance));
                        }
                    }

                    // Resolve independently of the concurrent modeled-entity set's
                    // iteration order. This also lets a Piercing arrow visit every
                    // unique target along this tick's swept segment in travel order.
                    for (Projectile projectile : worldProjectiles) {
                        List<ProjectileHitCandidate> candidates = candidatesByProjectile.get(projectile);
                        if (candidates != null) {
                            boolean preserveEmbeddedProjectile = retireAfterFinalBlockSweep != null &&
                                    retireAfterFinalBlockSweep.contains(projectile);
                            dispatchProjectileHits(projectile, candidates, preserveEmbeddedProjectile);
                        }
                    }
                }

                if (retireAfterFinalBlockSweep != null) {
                    for (Projectile projectile : retireAfterFinalBlockSweep) {
                        retireEmbeddedProjectile(projectile);
                    }
                }

                // Snapshot the current position of every still-tracked projectile so
                // next tick's swept test has the correct segment start. Hits and
                // invalids were already removed from activeProjectiles above, so only
                // survivors are updated here.
                for (Projectile p : activeProjectiles) {
                    if (projectilesPendingRemoval.contains(p.getUniqueId()) ||
                            projectilesRetiredInBlock.contains(p.getUniqueId())) continue;
                    projectileLastPositions.put(p.getUniqueId(), p.getLocation().toVector());
                }
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L); // todo: somehow can't be async due to getting the entity that fired the projectile. Odd.
    }

    /**
     * Stops the registry-reading clock without resetting per-projectile state.
     * Content reloads leave Bukkit projectile entities alive, so their routed
     * target ledger and consumed/embedded retirement state must remain valid
     * when detection resumes.
     */
    public static void pauseProjectileDetection() {
        if (projectileDetectionTask == null) return;
        projectileDetectionTask.cancel();
        projectileDetectionTask = null;
    }

    /**
     * Destructive plugin-disable cleanup. Unlike a normal content reload, no
     * detection clock will resume against the surviving projectile entities.
     */
    public static void shutdown() {
        pauseProjectileDetection();
        activeProjectiles.clear();
        projectileShooterIds.clear();
        projectileLastPositions.clear();
        routedProjectileHits.clear();
        projectileHitCounts.clear();
        projectilesPendingRemoval.clear();
        projectilesRetiredInBlock.clear();
        piercingFlightRestores.clear();
        leftClickCooldownPlayers.clear();
        rightClickCooldownPlayers.clear();
        attackCooldowns.clear();
        applyDamage = false;
        bypassProjectileRedirect = false;
    }

    /**
     * Swept (segment) hit test: does the path the projectile traversed since the
     * last detection tick intersect the given OBB? Reconstructs the segment from
     * {@link #projectileLastPositions} (start) to the projectile's current
     * position (end) and ray-casts it against the OBB. Returns false when there
     * is no stored previous position yet, or the projectile did not move (the
     * instantaneous AABB test already covers the stationary case).
     */
    private static double projectileIntersectionDistance(OrientedBoundingBox obb, Projectile proj) {
        org.bukkit.util.Vector last = projectileLastPositions.get(proj.getUniqueId());
        org.bukkit.util.Vector cur = proj.getLocation().toVector();
        double segmentLength = 0;

        if (last != null) {
            double dx = cur.getX() - last.getX();
            double dy = cur.getY() - last.getY();
            double dz = cur.getZ() - last.getZ();
            double segmentLengthSquared = dx * dx + dy * dy + dz * dz;
            if (segmentLengthSquared >= 1e-8) {
                segmentLength = Math.sqrt(segmentLengthSquared);
                double inverseLength = 1.0 / segmentLength;
                double intersection = obb.rayIntersection(
                        last.getX(), last.getY(), last.getZ(),
                        dx * inverseLength, dy * inverseLength, dz * inverseLength,
                        segmentLength);
                if (intersection >= 0 && intersection <= segmentLength) return intersection;
            }
        }

        // Slow, newly-launched, or resting projectiles still need the exact SAT
        // overlap path. Sort those endpoint-only hits after swept intersections.
        org.bukkit.util.BoundingBox projectileBounds = proj.getBoundingBox();
        if (obb.quickAabbReject(projectileBounds) && obb.intersectsAABB(projectileBounds)) {
            double dx = obb.getCenter().x - cur.getX();
            double dy = obb.getCenter().y - cur.getY();
            double dz = obb.getCenter().z - cur.getZ();
            return segmentLength + Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        return -1;
    }

    private static void dispatchProjectileHits(Projectile projectile,
                                               List<ProjectileHitCandidate> candidates) {
        dispatchProjectileHits(projectile, candidates, false);
    }

    private static void dispatchProjectileHits(Projectile projectile,
                                               List<ProjectileHitCandidate> candidates,
                                               boolean preserveVanillaProjectile) {
        UUID projectileId = projectile.getUniqueId();
        int maximumHits = maximumModeledTargets(projectile);
        int hitCount = projectileHitCounts.getOrDefault(projectileId, 0);
        if (hitCount >= maximumHits) return;

        candidates.sort(Comparator
                .comparingDouble(ProjectileHitCandidate::distance)
                .thenComparing(candidate -> candidate.entity().getModelInstanceId()));

        for (ProjectileHitCandidate candidate : candidates) {
            ModeledEntity entity = candidate.entity();
            ProjectileTarget routedHit = new ProjectileTarget(projectileId, entity.getModelInstanceId());
            if (!routedProjectileHits.add(routedHit)) continue;

            try {
                entity.getInteractionComponent().callModeledEntityHitByProjectileEvent(projectile);
            } catch (Throwable throwable) {
                Entity underlying = entity.getUnderlyingEntity();
                com.magmaguy.magmacore.util.Logger.warn("[FMM-ProjTrace] modeled projectile hit listener failed for proj="
                        + projectileId + " type=" + projectile.getType()
                        + " vs entity=" + (underlying != null ? underlying.getType() : "?")
                        + ": " + throwable.getClass().getSimpleName()
                        + " " + (throwable.getMessage() == null ? "" : throwable.getMessage()));
                throwable.printStackTrace();
            }

            hitCount++;
            projectileHitCounts.put(projectileId, hitCount);
            if (hitCount >= maximumHits) {
                // Non-piercing projectiles are consumed by their nearest hit.
                // Piercing arrows remain alive after reaching their modeled-target
                // budget; the count above prevents any further routed targets,
                // while normal flight, block collision, and pickup stay vanilla.
                if (maximumHits == 1 && !preserveVanillaProjectile)
                    consumeProjectile(projectile);
                break;
            }
        }
    }

    private static int maximumModeledTargets(Projectile projectile) {
        if (projectile instanceof AbstractArrow arrow) {
            return Math.max(1, arrow.getPierceLevel() + 1);
        }
        return 1;
    }

    private static boolean hasExhaustedModeledTargetBudget(Projectile projectile) {
        return projectileHitCounts.getOrDefault(projectile.getUniqueId(), 0) >=
                maximumModeledTargets(projectile);
    }

    private static void consumeProjectile(Projectile projectile) {
        UUID projectileId = projectile.getUniqueId();
        projectileShooterIds.remove(projectileId);
        projectileLastPositions.remove(projectileId);
        if (!projectilesPendingRemoval.add(projectileId)) return;

        if (projectile instanceof Trident) {
            // A consumed trident must remain excluded for the lifetime of this
            // entity. Clearing its ledger on a timer lets a resting or returning
            // trident hit the same modeled entity again. Keep it in the cheap
            // validity pass until pickup/despawn instead.
            activeProjectiles.add(projectile);
            return;
        }

        activeProjectiles.remove(projectile);

        new BukkitRunnable() {
            @Override
            public void run() {
                projectile.remove();
                clearProjectileState(projectileId);
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 1L);
    }

    private static void clearProjectileState(UUID projectileId) {
        projectileShooterIds.remove(projectileId);
        projectileLastPositions.remove(projectileId);
        projectileHitCounts.remove(projectileId);
        projectilesPendingRemoval.remove(projectileId);
        projectilesRetiredInBlock.remove(projectileId);
        piercingFlightRestores.remove(projectileId);
        routedProjectileHits.removeIf(hit -> hit.projectileId().equals(projectileId));
    }

    private static void retireEmbeddedProjectile(Projectile projectile) {
        UUID projectileId = projectile.getUniqueId();
        projectileShooterIds.remove(projectileId);
        projectileLastPositions.remove(projectileId);
        projectileHitCounts.remove(projectileId);
        piercingFlightRestores.remove(projectileId);
        routedProjectileHits.removeIf(hit -> hit.projectileId().equals(projectileId));
        // Keep the Bukkit entity in activeProjectiles solely for cheap validity
        // cleanup. This blocks both the sweep and native backing-entity redirect
        // without removing the embedded arrow/trident players can still pick up.
        projectilesRetiredInBlock.add(projectileId);
    }

    private static void executeLeftClickAttack(Player player) {
        executePlayerInteraction(player, leftClickCooldownPlayers,
                hitEntity -> hitEntity.getInteractionComponent().callLeftClickEvent(player));
    }

    private static void executeRightClickInteraction(Player player) {
        executePlayerInteraction(player, rightClickCooldownPlayers,
                hitEntity -> hitEntity.getInteractionComponent().callRightClickEvent(player));
    }

    private static void executePlayerInteraction(Player player, HashSet<UUID> cooldownSet,
                                                 Consumer<ModeledEntity> interactionCallback) {
        UUID playerId = player.getUniqueId();
        if (cooldownSet.contains(playerId)) return;
        cooldownSet.add(playerId);
        new BukkitRunnable() {
            @Override
            public void run() {
                cooldownSet.remove(playerId);
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
            attackCooldowns.put(event.getPlayer().getUniqueId(), event.getPlayer().getAttackCooldown());
            executeLeftClickAttack(event.getPlayer());
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            executeRightClickInteraction(event.getPlayer());
        }
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        // Keyed by UUID and cleaned up here so quitting players don't leak entries.
        UUID playerId = event.getPlayer().getUniqueId();
        attackCooldowns.remove(playerId);
        leftClickCooldownPlayers.remove(playerId);
        rightClickCooldownPlayers.remove(playerId);
    }

    @EventHandler
    public void onProjectileCreate(ProjectileLaunchEvent event) {
        Projectile proj = event.getEntity();
        clearProjectileState(proj.getUniqueId());
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
        ModeledEntity modeledEntity = ModeledEntity.getModeledEntity(event.getEntity());

        if (modeledEntity == null) return;

        org.bukkit.util.Vector impactVelocity = projectile.getVelocity().clone();
        Location impactLocation = projectile.getLocation().clone();

        // Resolve at impact time so listeners and damage formulas observe the
        // projectile's real collision velocity. Scan the same final movement
        // segment against every modeled OBB and merge the vanilla backing target
        // at the segment endpoint before choosing the nearest target(s).
        UUID projectileId = projectile.getUniqueId();
        if (projectilesPendingRemoval.contains(projectileId) ||
                projectilesRetiredInBlock.contains(projectileId)) return;
        activeProjectiles.add(projectile);
        projectileLastPositions.putIfAbsent(projectileId, projectile.getLocation().toVector());
        ProjectileSource shooter = projectile.getShooter();
        if (shooter instanceof Entity shooterEntity) {
            projectileShooterIds.putIfAbsent(projectileId, shooterEntity.getUniqueId());
        }

        if (!hasExhaustedModeledTargetBudget(projectile)) {
            List<ProjectileHitCandidate> candidates = collectProjectileCandidates(projectile);
            candidates.add(new ProjectileHitCandidate(
                    modeledEntity, projectileTravelDistance(projectile)));
            dispatchProjectileHits(projectile, candidates);
        }

        if (projectile instanceof AbstractArrow arrow && arrow.getPierceLevel() > 0) {
            preservePiercingFlight(
                    arrow,
                    impactLocation,
                    impactVelocity,
                    event.getEntity().getBoundingBox());
        }
    }

    /**
     * Cancelling the backing entity's native damage is required to avoid a
     * duplicate hit, but vanilla treats a cancelled arrow collision as a failed
     * hit and reverses its motion. Restore the captured impact trajectory just
     * beyond that backing hitbox on the next tick so a Piercing arrow actually
     * continues to later ordered targets.
     */
    private static void preservePiercingFlight(AbstractArrow arrow,
                                               Location impactLocation,
                                               org.bukkit.util.Vector impactVelocity,
                                               org.bukkit.util.BoundingBox targetBounds) {
        if (impactVelocity.lengthSquared() < 1e-8) return;
        UUID projectileId = arrow.getUniqueId();
        if (!piercingFlightRestores.add(projectileId)) return;

        org.bukkit.util.Vector direction = impactVelocity.clone().normalize();
        org.bukkit.util.BoundingBox expandedBounds = targetBounds.clone().expand(0.35);
        double exitDistance = rayExitDistance(expandedBounds, impactLocation.toVector(), direction);
        Location resumeLocation = impactLocation.clone()
                .add(direction.clone().multiply(Math.max(0.1, exitDistance + 0.1)));

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (!arrow.isValid()
                            || resumeLocation.getWorld() == null
                            || !arrow.getWorld().equals(resumeLocation.getWorld())) return;
                    arrow.teleport(resumeLocation);
                    arrow.setVelocity(impactVelocity);
                } finally {
                    piercingFlightRestores.remove(projectileId);
                }
            }
        }.runTask(MetadataHandler.PLUGIN);
    }

    private static double rayExitDistance(org.bukkit.util.BoundingBox bounds,
                                          org.bukkit.util.Vector origin,
                                          org.bukkit.util.Vector direction) {
        double tMin = Double.NEGATIVE_INFINITY;
        double tMax = Double.POSITIVE_INFINITY;
        double[] origins = {origin.getX(), origin.getY(), origin.getZ()};
        double[] directions = {direction.getX(), direction.getY(), direction.getZ()};
        double[] minimums = {bounds.getMinX(), bounds.getMinY(), bounds.getMinZ()};
        double[] maximums = {bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ()};

        for (int axis = 0; axis < 3; axis++) {
            double axisDirection = directions[axis];
            if (Math.abs(axisDirection) < 1e-8) {
                if (origins[axis] < minimums[axis] || origins[axis] > maximums[axis]) return 0;
                continue;
            }
            double t1 = (minimums[axis] - origins[axis]) / axisDirection;
            double t2 = (maximums[axis] - origins[axis]) / axisDirection;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMax < tMin) return 0;
        }
        return tMax >= 0 && Double.isFinite(tMax) ? tMax : 0;
    }

    private static List<ProjectileHitCandidate> collectProjectileCandidates(Projectile projectile) {
        List<ProjectileHitCandidate> candidates = new ArrayList<>();
        World projectileWorld = projectile.getWorld();
        UUID shooterId = projectileShooterIds.get(projectile.getUniqueId());
        for (ModeledEntity entity : ModeledEntity.getLoadedModeledEntities()) {
            World entityWorld = entity.getWorld();
            if (entityWorld == null || !entityWorld.equals(projectileWorld)) continue;
            Entity underlying = entity.getUnderlyingEntity();
            if (underlying != null && underlying.getUniqueId().equals(shooterId)) continue;
            double distance = projectileIntersectionDistance(
                    entity.getHitboxComponent().getObbHitbox(), projectile);
            if (distance >= 0) {
                candidates.add(new ProjectileHitCandidate(entity, distance));
            }
        }
        return candidates;
    }

    private static double projectileTravelDistance(Projectile projectile) {
        org.bukkit.util.Vector previous = projectileLastPositions.get(projectile.getUniqueId());
        if (previous == null) return 0;
        return previous.distance(projectile.getLocation().toVector());
    }

}
