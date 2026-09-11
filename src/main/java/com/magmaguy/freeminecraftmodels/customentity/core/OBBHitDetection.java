package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityInteractEvent;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.interaction.InteractionProtectionPolicy;
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
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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
    // Identity-scoped because Bukkit damage events have no stable public event
    // ID. Entries live only from LOWEST through MONITOR during one synchronous
    // dispatch and let the visible model flash only when native damage survives.
    private static final Set<EntityDamageByEntityEvent> nativeMeleeTintCandidates =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final OneTickInteractionLedger interactionLedger =
            new OneTickInteractionLedger(task -> new BukkitRunnable() {
                @Override
                public void run() {
                    task.run();
                }
            }.runTaskLater(MetadataHandler.PLUGIN, 1L));
    private static final ClassValue<AnimationHandResolver> animationHandResolvers =
            new ClassValue<>() {
                @Override
                protected AnimationHandResolver computeValue(Class<?> eventType) {
                    try {
                        Method getHand = eventType.getMethod("getHand");
                        if (!EquipmentSlot.class.isAssignableFrom(getHand.getReturnType())) {
                            return ignored -> null;
                        }
                        return event -> {
                            try {
                                Object hand = getHand.invoke(event);
                                return hand instanceof EquipmentSlot slot ? slot : null;
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                                // A Paper-style event advertised hand data but it
                                // could not be read. Fail closed instead of turning
                                // an unknown/offhand animation into a mainhand hit.
                                return null;
                            }
                        };
                    } catch (NoSuchMethodException ignored) {
                        // Spigot exposes the hand through PlayerAnimationType.
                        return OBBHitDetection::resolveSpigotAnimationHand;
                    } catch (RuntimeException ignored) {
                        return event -> null;
                    }
                }
            };
    private static BukkitTask projectileDetectionTask = null;

    @FunctionalInterface
    private interface AnimationHandResolver {
        EquipmentSlot resolve(PlayerAnimationEvent event);
    }

    private record ProjectileTarget(UUID projectileId, UUID targetId) {
    }

    private record ProjectileHitCandidate(ModeledEntity entity, double distance) {
    }

    enum BackingMeleeRoute {
        ALLOW_NATIVE,
        CANCEL_NATIVE,
        ROUTE_VISIBLE_MODEL
    }

    /**
     * Chooses the single owner of a melee swing that reached a modeled entity's
     * vanilla backing entity. A matching visible OBB keeps the original Bukkit
     * damage event intact. A missing ray result also keeps that authoritative
     * native hit because there is no alternate visible target to receive it. A
     * different OBB in front owns the swing instead, and an already-routed swing
     * can never apply a second hit.
     */
    static BackingMeleeRoute resolveBackingMeleeRoute(UUID backingModelId,
                                                       UUID rayHitModelId,
                                                       UUID alreadyRoutedModelId) {
        if (alreadyRoutedModelId != null)
            return BackingMeleeRoute.CANCEL_NATIVE;
        if (rayHitModelId == null || backingModelId.equals(rayHitModelId))
            return BackingMeleeRoute.ALLOW_NATIVE;
        return BackingMeleeRoute.ROUTE_VISIBLE_MODEL;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void EntityDamageByEntityEvent(EntityDamageByEntityEvent event) {
        if (applyDamage) {
            applyDamage = false;
            return;
        }

        Player player = event.getDamager() instanceof Player attacker ? attacker : null;
        boolean physicalPlayerMelee = shouldObservePhysicalPlayerMelee(
                player != null, event.getCause());
        boolean modeledTarget = RegisterModelEntity.isModelEntity(event.getEntity());
        if (physicalPlayerMelee) {
            // Some native path already owns this swing — a real entity, or one
            // of the modeled routes below. Its paired arm-animation packet can
            // be processed a tick or two later under load, so record the click
            // now; otherwise that late animation could raytrace a second
            // modeled hit out of the same physical input.
            interactionLedger.observeLeftClick(player.getUniqueId());
            if (!modeledTarget) return;
        }
        if (!modeledTarget
                || (event.getDamageSource().getDamageType().equals(DamageType.MOB_ATTACK)
                        && !RegisterModelEntity.isModelEntity(event.getDamager()))) return;

        if (player != null) {
            UUID playerId = player.getUniqueId();
            // A cancelled event still consumed the physical swing; the
            // observation above already keeps the animation fallback from
            // resurrecting the denied input.
            if (event.isCancelled()) return;
            ModeledEntity backingModel = ModeledEntity.getModeledEntity(event.getEntity());
            if (backingModel == null) {
                event.setCancelled(true);
                return;
            }

            Optional<ModeledEntity> rayHit = OrientedBoundingBox.raytraceFromPlayer(player);
            BackingMeleeRoute route = resolveBackingMeleeRoute(
                    backingModel.getModelInstanceId(),
                    rayHit.map(ModeledEntity::getModelInstanceId).orElse(null),
                    interactionLedger.meleeTarget(playerId));

            if (route == BackingMeleeRoute.ALLOW_NATIVE) {
                if (!recordMeleeRoute(player, backingModel)) {
                    event.setCancelled(true);
                    return;
                }
                if (backingModel.getInteractionComponent().callNativeBackingLeftClickEvent(player)) {
                    nativeMeleeTintCandidates.add(event);
                    return;
                }
                event.setCancelled(true);
                return;
            }

            event.setCancelled(true);
            if (route == BackingMeleeRoute.ROUTE_VISIBLE_MODEL) {
                routeTransferredMeleeHit(player, rayHit.orElseThrow(), event.getDamage());
            }
            return;
        }
        event.setCancelled(true);
    }

    static boolean shouldObservePhysicalPlayerMelee(
            boolean playerDamager,
            EntityDamageEvent.DamageCause cause) {
        return playerDamager
                && (cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                        || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK);
    }

    static EquipmentSlot resolveAnimationHand(PlayerAnimationEvent event) {
        return animationHandResolvers.get(event.getClass()).resolve(event);
    }

    private static EquipmentSlot resolveSpigotAnimationHand(PlayerAnimationEvent event) {
        return switch (event.getAnimationType().name()) {
            case "ARM_SWING" -> EquipmentSlot.HAND;
            case "OFF_ARM_SWING" -> EquipmentSlot.OFF_HAND;
            default -> null;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void tintAcceptedNativeMeleeHit(EntityDamageByEntityEvent event) {
        if (!nativeMeleeTintCandidates.remove(event) || event.isCancelled()) return;
        ModeledEntity modeledEntity = ModeledEntity.getModeledEntity(event.getEntity());
        if (modeledEntity != null) modeledEntity.getSkeleton().tint();
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
            // The dig click's swing packets can trail into later ticks; keep
            // them from re-routing this click through the animation fallback.
            interactionLedger.observeLeftClick(event.getPlayer().getUniqueId());
            routeLeftClick(event.getPlayer(), hitEntity);
        }
    }

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
        nativeMeleeTintCandidates.clear();
        interactionLedger.clear();
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
        double centerDistance = obb.centerDistanceIfIntersects(
                projectileBounds, cur.getX(), cur.getY(), cur.getZ());
        if (centerDistance >= 0D) return segmentLength + centerDistance;
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

    /**
     * Routes every non-native left-click source through the same one-tick target
     * ledger. Packet Interaction entities, OBB raytraces, and block interception
     * therefore cannot each fire the same click independently.
     */
    public static void routeLeftClick(Player player, ModeledEntity target) {
        if (!recordMeleeRoute(player, target)) return;
        target.getInteractionComponent().callLeftClickEvent(player);
    }

    /**
     * Validates a client-targeted packet entity against the authoritative OBB
     * ray before routing it. This preserves configured reach, block occlusion,
     * rotated geometry, and nearest-visible-model ownership even for packet ID
     * aliases and deliberately over-approximated Interaction envelopes.
     */
    public static void routePacketLeftClick(Player player, ModeledEntity packetOwner) {
        if (player == null) return;
        // The client sends the paired arm-swing packet right behind this
        // ATTACK packet, but under load it can be processed a tick or two
        // later — after the one-tick melee claim below has already expired.
        // Record the click before resolving the ray so the swing can never be
        // reclassified as a second click, not even when the ray misses here
        // and would hit a model that moved into it by swing time.
        interactionLedger.observeLeftClick(player.getUniqueId());
        resolvePacketInteractionTarget(player, packetOwner)
                .ifPresent(target -> routeLeftClick(player, target));
    }

    public static void routePacketRightClick(Player player, ModeledEntity packetOwner) {
        if (player == null) return;
        interactionLedger.routeRightClick(
                player.getUniqueId(),
                () -> resolvePacketInteractionTarget(player, packetOwner),
                target -> target.getInteractionComponent().callRightClickEvent(player));
    }

    private static Optional<ModeledEntity> resolvePacketInteractionTarget(
            Player player,
            ModeledEntity packetOwner) {
        if (player == null || packetOwner == null || packetOwner.isRemoved()
                || !player.isOnline() || !player.isValid()
                || packetOwner.getWorld() == null
                || !packetOwner.getWorld().equals(player.getWorld())) {
            return Optional.empty();
        }
        return OrientedBoundingBox.raytraceFromPlayer(player);
    }

    private static void routeTransferredMeleeHit(Player player, ModeledEntity target, double rawDamage) {
        if (!recordMeleeRoute(player, target)) return;
        if (!target.getInteractionComponent().callNativeBackingLeftClickEvent(player)) return;
        if (!Double.isFinite(rawDamage) || rawDamage <= 0D) return;

        // The original Bukkit event was aimed at a backing entity hidden behind
        // this nearer visible OBB. Preserve its already-computed base attack
        // damage, but avoid recursively invoking Player#attack from inside the
        // original damage event (the server can reject that second attack).
        applyDamage = true;
        try {
            target.damage(player, rawDamage);
        } finally {
            applyDamage = false;
        }
    }

    private static boolean recordMeleeRoute(Player player, ModeledEntity target) {
        return interactionLedger.claimMelee(
                player.getUniqueId(), target.getModelInstanceId());
    }

    private static void scheduleCapturedInteraction(
            Player player,
            ModeledEntity target,
            Runnable dispatcher) {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || !player.isValid()
                        || target.isRemoved() || target.getWorld() == null
                        || !target.getWorld().equals(player.getWorld())) {
                    return;
                }
                dispatcher.run();
            }
        }.runTaskLater(MetadataHandler.PLUGIN, 1L);
    }

    // ignoreCancelled=false on purpose: LEFT_CLICK_AIR / RIGHT_CLICK_AIR arrive
    // with useInteractedBlock=DENY (there's no block to "use"), and Bukkit
    // reports such events as cancelled — so ignoreCancelled=true would silently
    // skip every air click, breaking hit detection on any entity without a
    // block behind it. Complete denial still suppresses left clicks. Right clicks
    // use a separate entity permission check in InteractionComponent: block-use
    // protection must not also disable NPCs and props in the protected area.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        boolean leftClick = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean rightClick = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (!leftClick && !rightClick) return;

        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean denied = InteractionProtectionPolicy.isDenied(
                event.useInteractedBlock(), event.useItemInHand());

        if (leftClick) {
            interactionLedger.observeLeftClick(playerId);
            if (denied) return;

            float attackCooldown = player.getAttackCooldown();
            OrientedBoundingBox.raytraceFromPlayer(player).ifPresent(target ->
                    scheduleCapturedInteraction(player, target, () -> {
                        attackCooldowns.put(playerId, attackCooldown);
                        routeLeftClick(player, target);
                    }));
            return;
        }

        // Claim before resolving the target so a swallowed or duplicate right-click
        // cannot later be reclassified as an animation-based left attack.
        if (!interactionLedger.observeRightClick(playerId)
                || event.getHand() != org.bukkit.inventory.EquipmentSlot.HAND) return;
        OrientedBoundingBox.raytraceFromPlayer(player).ifPresent(target ->
                scheduleCapturedInteraction(
                        player,
                        target,
                        () -> target.getInteractionComponent().callRightClickEvent(player)));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void routePlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event instanceof ModeledEntityInteractEvent) return;
        if (event instanceof PlayerInteractAtEntityEvent) return;
        routeAllowedNativeBackingRightClick(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void routePlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        routeAllowedNativeBackingRightClick(event);
    }

    private static void routeAllowedNativeBackingRightClick(
            PlayerInteractEntityEvent event) {
        ModeledEntity target = ModeledEntity.getModeledEntity(event.getRightClicked());
        if (!shouldRouteNativeRightClick(
                event.isCancelled(), event.getHand(), target != null)) return;
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!interactionLedger.observeRightClick(player.getUniqueId())) return;
        scheduleCapturedInteraction(
                player,
                target,
                () -> target.getInteractionComponent().callRightClickEvent(player));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void observePlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event instanceof ModeledEntityInteractEvent) return;
        if (event instanceof PlayerInteractAtEntityEvent) return;
        interactionLedger.observeRightClick(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void observePlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        interactionLedger.observeRightClick(event.getPlayer().getUniqueId());
    }

    static boolean shouldRouteNativeRightClick(
            boolean cancelled,
            EquipmentSlot hand,
            boolean modeledBacking) {
        return !cancelled && hand == EquipmentSlot.HAND && modeledBacking;
    }

    /**
     * Spigot/Paper can suppress LEFT_CLICK_AIR when its server-side ray hits a
     * backing entity that the client was told to hide. The arm-swing packet is
     * then the only surviving evidence of the physical click. Delay its OBB
     * fallback by one tick so packet-only entity routes can claim the same
     * input first. Every other route — packet Interaction attacks, native
     * melee, Bukkit interact and right-click paths — records its click in the
     * ledger with a multi-tick swing suppression, because this swing packet
     * can be processed a tick or two after the input that owns it; only a
     * swing with no recorded owner may be routed as a click of its own.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerAnimation(PlayerAnimationEvent event) {
        if (resolveAnimationHand(event) != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        String animationType = event.getAnimationType().name();
        if (!interactionLedger.shouldRouteAnimation(playerId, animationType)) return;
        Optional<ModeledEntity> fallbackTarget =
                OrientedBoundingBox.raytraceFromPlayer(player);
        if (fallbackTarget.isEmpty()) return;
        ModeledEntity target = fallbackTarget.get();
        float attackCooldown = player.getAttackCooldown();
        scheduleCapturedInteraction(player, target, () -> {
            if (interactionLedger.shouldRouteAnimation(playerId, animationType)) {
                attackCooldowns.put(playerId, attackCooldown);
                routeLeftClick(player, target);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        // Keyed by UUID and cleaned up here so quitting players don't leak entries.
        UUID playerId = event.getPlayer().getUniqueId();
        attackCooldowns.remove(playerId);
        interactionLedger.forget(playerId);
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
        // FMM's magic engine owns these marker arrows end to end. Its LOWEST
        // listener cancels native arrow damage and resolves one custom impact;
        // routing the same marker through the ordinary modeled-projectile path
        // here would create a second damage authority.
        if (com.magmaguy.magmacore.projectiles.MagicProjectileMarker.isMarked(projectile)) return;
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
