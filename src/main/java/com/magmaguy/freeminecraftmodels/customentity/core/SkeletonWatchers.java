package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketEquipmentVeil;
import com.magmaguy.easyminecraftgoals.internal.ViewerPlayers;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;

public class SkeletonWatchers {
    private final Skeleton skeleton;
    private final Set<UUID> viewers = new CopyOnWriteArraySet<>();
    // Viewers on Java Edition; particle sprites are display entities, which Bedrock cannot show
    private final Set<UUID> javaViewers = new CopyOnWriteArraySet<>();
    // Java viewers that track the model's hidden body as an invisible, bare entity so the bones can ride it.
    private final Set<UUID> bodyViewers = new CopyOnWriteArraySet<>();
    // Players a plugin hid the models on each body from; the watcher list never admits them until they are shown it
    // again. Keyed by the body, so a model rebuilt on it (a prop reloading with its chunk or after /fmm reload, a new
    // model on the same mob) keeps them hidden. An entry lasts until its body is gone for good or the plugin stops.
    private static final Map<UUID, Set<UUID>> hiddenFromByBody = new ConcurrentHashMap<>();
    // Players hidden from a model with no body, or hidden before it got one.
    private final Set<UUID> ownHiddenFrom = new CopyOnWriteArraySet<>();
    private volatile UUID bodyId;
    private int trackedBodyId = -1;
    // Whether every Java viewer's client tracks the model's entity, refreshed on the primary thread. A client that
    // lacks the entity drops the bones' mount and stops drawing them, so the bones ride it only while this holds.
    private volatile boolean entityTrackedByJavaViewers = true;
    private volatile boolean wasInvisible = false;

    // The online viewers in viewer order, rebuilt only when one changes.
    private final ViewerPlayers viewerPlayers = new ViewerPlayers();

    public HashSet<UUID> getViewers() {
        return new HashSet<>(viewers);
    }

    private final int resetTimer = 20 * 60;
    private int counter = ThreadLocalRandom.current().nextInt(20 * 60);

    // Cadence at which ModeledEntitiesClock invokes tickPrimaryThread().
    private static final int VIEWER_STATE_INTERVAL_TICKS = 4;
    // The sight point (0 the center, then the corners) each player last saw the model by, tried first next time.
    private final Map<UUID, Integer> sightPoints = new HashMap<>();

    public SkeletonWatchers(Skeleton skeleton) {
        this.skeleton = skeleton;
    }

    /** Live view of the Java Edition viewers, safe to read from the model clock. */
    public Set<UUID> getJavaViewers() {
        return javaViewers;
    }

    public boolean hasObservers() {
        return !viewers.isEmpty();
    }

    /** Shares the hidden players of every model on this body; players hidden before the model got it move over. */
    public void bindBody(UUID bodyId) {
        this.bodyId = bodyId;
        if (ownHiddenFrom.isEmpty()) return;
        hiddenFromByBody.compute(bodyId, (ignored, players) -> {
            Set<UUID> hidden = players == null ? new CopyOnWriteArraySet<>() : players;
            hidden.addAll(ownHiddenFrom);
            return hidden;
        });
        ownHiddenFrom.clear();
    }

    /** Hides the whole model from one player until {@link #showToPlayer(Player)}, while other viewers keep it. */
    public void hideFromPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        if (!addHidden(uuid) || !viewers.contains(uuid)) return;
        if (Bukkit.isPrimaryThread()) hideFrom(uuid);
        else Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            if (hiddenFrom().contains(uuid) && viewers.contains(uuid)) hideFrom(uuid);
        });
    }

    /** Lets a hidden player back in; the next watcher update shows them the model if it is in range and in sight. */
    public void showToPlayer(Player player) {
        UUID uuid = player.getUniqueId();
        UUID body = bodyId;
        if (body == null) {
            ownHiddenFrom.remove(uuid);
            return;
        }
        hiddenFromByBody.computeIfPresent(body, (ignored, players) -> {
            players.remove(uuid);
            return players.isEmpty() ? null : players;
        });
    }

    public boolean isHiddenFrom(Player player) {
        return hiddenFrom().contains(player.getUniqueId());
    }

    /** Forgets who was hidden from the models on a body that is gone for good. */
    public static void forgetHiddenPlayers(UUID bodyId) {
        hiddenFromByBody.remove(bodyId);
    }

    public static void clearHiddenPlayers() {
        hiddenFromByBody.clear();
    }

    private boolean addHidden(UUID uuid) {
        UUID body = bodyId;
        if (body == null) return ownHiddenFrom.add(uuid);
        boolean[] added = new boolean[1];
        hiddenFromByBody.compute(body, (ignored, players) -> {
            Set<UUID> hidden = players == null ? new CopyOnWriteArraySet<>() : players;
            added[0] = hidden.add(uuid);
            return hidden;
        });
        return added[0];
    }

    private Set<UUID> hiddenFrom() {
        UUID body = bodyId;
        if (body == null) return ownHiddenFrom;
        Set<UUID> players = hiddenFromByBody.get(body);
        return players == null ? Set.of() : players;
    }

    /** Whether every Java viewer's client tracked the model's entity at the last primary-thread check. */
    public boolean isEntityTrackedByJavaViewers() {
        return entityTrackedByJavaViewers;
    }

    /**
     * Rechecks that every Java viewer's client tracks the model's entity. A viewer can see a model farther away than
     * the server's entity tracking range (monsters default to 48 blocks, models to 60), and plugins can hide the
     * entity from a viewer, so being a viewer does not mean the client has the entity the bones would ride.
     */
    public void refreshEntityTracking() {
        var modeledEntity = skeleton.getModeledEntity();
        Entity entity = modeledEntity == null ? null : modeledEntity.getUnderlyingEntity();
        if (entity == null || !entity.isValid()) {
            entityTrackedByJavaViewers = false;
            return;
        }
        Set<Player> trackers = entity.getTrackedBy();
        for (UUID uuid : javaViewers) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && !trackers.contains(player)) {
                entityTrackedByJavaViewers = false;
                return;
            }
        }
        entityTrackedByJavaViewers = true;
    }

    /** Restores the veil on a tracked body that {@code showUnderlyingEntity} lifted for this viewer. */
    public void reveilTrackedBody(Player player) {
        if (trackedBodyId != -1 && bodyViewers.contains(player.getUniqueId()))
            PacketEquipmentVeil.veil(player.getUniqueId(), trackedBodyId);
    }

    private boolean isUnderlyingEntityInvisible() {
        if (skeleton.getModeledEntity() == null) return false;
        // Cosmetic invisibility (e.g. mount command) should not hide the model
        if (skeleton.getModeledEntity() instanceof DynamicEntity de && de.isCosmeticInvisibility())
            return false;
        if (skeleton.getModeledEntity().getUnderlyingEntity() instanceof LivingEntity livingEntity
                && livingEntity.isValid())
            return livingEntity.hasPotionEffect(PotionEffectType.INVISIBILITY);
        return false;
    }

    /**
     * Refreshes all state that touches Bukkit worlds, players, entities, potion
     * effects, or block ray tracing. ModeledEntitiesClock invokes this on the
     * primary thread; the one-tick packet/animation path remains asynchronous.
     */
    public void tickPrimaryThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Skeleton watcher state must be refreshed on the primary thread");
        }
        updateWatcherList();
        refreshEntityTracking();
        boolean isInvisible = isUnderlyingEntityInvisible();
        if (isInvisible != wasInvisible) {
            wasInvisible = isInvisible;
            if (isInvisible) {
                // Entity became invisible - hide bones from all viewers but keep them tracked
                viewers.forEach(uuid -> skeleton.getBones().forEach(bone -> bone.hideFrom(uuid)));
            } else {
                // Entity became visible again - show bones to all viewers
                viewers.forEach(uuid -> {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player != null && player.isValid())
                        skeleton.getBones().forEach(bone -> bone.displayTo(player));
                });
            }
        }
        resync(VIEWER_STATE_INTERVAL_TICKS);
        var modeledEntity = skeleton.getModeledEntity();
        // The nameplate rides the body whenever the bones do, through MagmaCore's shared text placement.
        modeledEntity.getNameplate().ride(skeleton.isRidden() ? modeledEntity.getUnderlyingEntity() : null);
        modeledEntity.getNameplate().move(modeledEntity.getNameplateLocation());
        modeledEntity.getNameplate().syncViewers(isInvisible ? List.of() : viewerPlayers.of(viewers));
    }

    private volatile long lastResyncTime = 0L;

    // Clients gets a bit of drift due to some inaccuracies, this resyncs the skeleton
    private void resync(int elapsedTicks) {
        // Viewers place ridden bones from the entity they ride, so nothing drifts; the hide+show would only blink
        // the mount under its rider.
        if (skeleton.isRidden()) return;
        long now = System.currentTimeMillis();

        // throttle: if we ran <1s ago, skip entirely
        if (now - lastResyncTime < 1_000) {
            return;
        }

        counter += elapsedTicks;
        // your existing random / timer logic
        if (counter > resetTimer && ThreadLocalRandom.current().nextBoolean()) {
            // update timestamp and reset counter
            lastResyncTime = now;
            counter = 0;

            // do the actual hide/display (displayTo already respects invisibility)
            Set<UUID> tempViewers = new HashSet<>(viewers);
            tempViewers.forEach(viewer -> {
                // Bones only: a tracked body stays shown, or the viewer would see it blink.
                hideFrom(viewer, false);
                Player p = Bukkit.getPlayer(viewer);
                if (p != null) {
                    displayTo(p);
                }
            });
        }
    }

    private static final int MIN_VIEW_DISTANCE = 10;
    // A player is admitted inside the model's view distance and kept until this far past it, so a model wandering
    // along the edge, such as a pet seen by a player standing near it, does not flip in and out of view.
    private static final double VIEW_DISTANCE_KEEP_MARGIN = 4;
    private static final double MIN_VIEW_DISTANCE_SQUARED = MIN_VIEW_DISTANCE * (double) MIN_VIEW_DISTANCE;

    private void updateWatcherList() {
        // Hoisted: getCurrentLocation() is not a plain getter — it rebuilds a
        // Location (NMS body-rotation lookup + clone for dynamic entities), so
        // one snapshot per pass instead of one per player matters.
        Location currentLocation = skeleton.getCurrentLocation();
        if (currentLocation == null) return;

        Set<UUID> newPlayers = new HashSet<>();
        List<UUID> toRemove = new ArrayList<>();

        int effectiveViewDistance = skeleton.getModeledEntity() != null
                ? skeleton.getModeledEntity().getEffectiveViewDistance()
                : DefaultConfig.maxModelViewDistance;
        double maxViewDistanceSquared = (double) effectiveViewDistance * effectiveViewDistance;
        double keepViewDistance = reachFor(effectiveViewDistance, true);
        double keepViewDistanceSquared = keepViewDistance * keepViewDistance;

        // Pseudo load balancer: only honor the close-range proximity override (always-show
        // within MIN_VIEW_DISTANCE, skipping the raytrace) when this world isn't crowded.
        // In a dense multi-floor hub the override would make every nearby model a viewer even
        // when occluded by floors/walls; disabling it lets isModelInSight() cull them.
        boolean proximityOverride = ModelDensity.proximityOverrideActive(
                currentLocation.getWorld());
        // Filmic mode (for ReplayMod recordings) skips the raytrace and shows every
        // model in the player's sent chunks. It never shows fewer models than normal
        // mode, so anything inside the view distance stays visible regardless of chunks.
        boolean filmicMode = FilmicMode.isEnabled();
        int worldViewDistance = filmicMode ? currentLocation.getWorld().getViewDistance() : 0;
        Set<UUID> hidden = hiddenFrom();
        // Bones that ride the body need every viewer's client to track it: one viewer past the body's tracking range
        // puts all viewers back on per-bone teleports, which doubles their packets and snaps the animation. The server
        // is told to track the body as far as the model is kept, so the check below only turns away viewers whose own
        // view distance is shorter. A model given its own view distance (migration flocks) is meant to be seen from
        // farther, so it keeps its full range.
        var modeledEntity = skeleton.getModeledEntity();
        Entity body = modeledEntity == null ? null : modeledEntity.getRiddenBody();
        NMSAdapter adapter = body == null ? null : NMSManager.getAdapter();
        if (adapter != null) widenBodyTracking(adapter, body, effectiveViewDistance);
        Entity riddenBody = filmicMode || modeledEntity == null || modeledEntity.hasViewDistanceOverride() ? null : body;
        Location bodyLocation = adapter == null || riddenBody == null ? null : riddenBody.getLocation();

        for (Player player : currentLocation.getWorld().getPlayers()) {
            // Left out of newPlayers, a hidden player who is still a viewer gets dropped below.
            if (hidden.contains(player.getUniqueId())) continue;
            double distance = player.getLocation().distanceSquared(currentLocation);
            boolean viewing = viewers.contains(player.getUniqueId());
            double reachSquared = viewing ? keepViewDistanceSquared : maxViewDistanceSquared;

            boolean visible = filmicMode
                    ? distance < reachSquared
                            || FilmicMode.isWithinSentChunks(player, currentLocation, worldViewDistance)
                    : (proximityOverride && distance < MIN_VIEW_DISTANCE_SQUARED)
                            || (distance < reachSquared && isModelInSight(player, currentLocation));
            if (visible && bodyLocation != null)
                visible = withinBodyTracking(adapter, riddenBody, bodyLocation, player);
            if (visible) {
                newPlayers.add(player.getUniqueId());
                if (!viewing) displayTo(player);
            }
        }

        for (UUID viewer : viewers) {
            if (!newPlayers.contains(viewer)) {
                toRemove.add(viewer);
            }
        }

        toRemove.forEach(viewers::remove);
        toRemove.forEach(this::hideFrom);
        if (!sightPoints.isEmpty()) sightPoints.keySet().retainAll(newPlayers);
    }

    // A viewer enters this far inside the body's tracking distance and leaves this close to its edge, so a player
    // standing at the edge does not flip in and out of view, and leaves before the server stops tracking the body.
    private static final double BODY_TRACKING_ADMIT_MARGIN = 3;
    private static final double BODY_TRACKING_KEEP_MARGIN = 1;
    // The body whose tracking range this model raised, given back its own range when the model goes away.
    private Entity widenedBody;

    /**
     * Has the server track the ridden body as far as the model is kept, plus the body gate's margins, so viewers
     * between the body type's own tracking range (48 blocks for animals and monsters by default) and the model's keep
     * distance still ride the bones: a new viewer inside the view distance passes the admission margin, and a viewer
     * kept up to {@link #VIEW_DISTANCE_KEEP_MARGIN} past it passes the keep margin. The body loses the range when it
     * gets a new tracker (a chunk reload or a change of world), so each pass checks it again.
     */
    private void widenBodyTracking(NMSAdapter adapter, Entity body, int viewDistance) {
        int wanted = bodyTrackingRangeFor(viewDistance);
        int range = adapter.getTrackingRange(body);
        if (range < 0 || range >= wanted) return;
        if (adapter.setTrackingRange(body, wanted)) widenedBody = body;
    }

    /** The tracking range a ridden body needs for a model seen this far: its keep distance and margins, plus one. */
    static int bodyTrackingRangeFor(int viewDistance) {
        return viewDistance + (int) Math.ceil(Math.max(BODY_TRACKING_ADMIT_MARGIN,
                VIEW_DISTANCE_KEEP_MARGIN + BODY_TRACKING_KEEP_MARGIN)) + 1;
    }

    /** How far a player may be from the model and still get it: the view distance, or the keep distance for a viewer. */
    static double reachFor(int viewDistance, boolean viewing) {
        return viewing ? viewDistance + VIEW_DISTANCE_KEEP_MARGIN : viewDistance;
    }

    /** Gives the body back the server's own tracking range when the model goes away. */
    public void restoreBodyTracking() {
        Entity body = widenedBody;
        widenedBody = null;
        NMSAdapter adapter = body == null ? null : NMSManager.getAdapter();
        if (adapter == null) return;
        Runnable restore = () -> {
            if (body.isValid()) adapter.setTrackingRange(body, -1);
        };
        if (Bukkit.isPrimaryThread()) restore.run();
        else if (MetadataHandler.PLUGIN != null && MetadataHandler.PLUGIN.isEnabled())
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, restore);
    }

    /** Whether the player's client tracks the ridden body, using the server tracker's own horizontal distance. */
    private boolean withinBodyTracking(NMSAdapter adapter, Entity body, Location bodyLocation, Player player) {
        double range = adapter.getTrackingDistance(body, player);
        if (range < 0) return true;
        double limit = range - (viewers.contains(player.getUniqueId())
                ? BODY_TRACKING_KEEP_MARGIN : BODY_TRACKING_ADMIT_MARGIN);
        if (limit <= 0) return false;
        Location location = player.getLocation();
        double dx = location.getX() - bodyLocation.getX();
        double dz = location.getZ() - bodyLocation.getZ();
        return dx * dx + dz * dz <= limit * limit;
    }

    /**
     * Checks if any part of the skeleton model is in the player's line of sight.
     * Tests the center and strategic corners of the bounding box, going from top to bottom.
     *
     * @param player          the player to check for
     * @param currentLocation the skeleton's location, hoisted by the caller so the
     *                        per-player loop doesn't recompute it
     * @return true if any part of the entity is visible
     */
    private boolean isModelInSight(Player player, Location currentLocation) {
        // Quick sanity checks
        if (skeleton.getModeledEntity() == null) return true;

        // Get the entity's hitbox
        OrientedBoundingBox hitbox = skeleton.getModeledEntity().getHitboxComponent().getObbHitbox();
        if (hitbox == null) return true;

        // The model is in sight when any of its sight points is: the center, then one top and one bottom corner on
        // each side. The answer does not depend on the order, so the point that last worked for this player goes
        // first, which spares the rays before it while the player keeps seeing the model by it.
        Location eyeLocation = player.getEyeLocation();
        World world = eyeLocation.getWorld();
        Vector eye = eyeLocation.toVector();
        UUID uuid = player.getUniqueId();
        // The corners are only worked out once a corner is tried.
        Vector3d[][] corners = new Vector3d[1][];
        int seen = firstVisiblePoint(sightPoints.get(uuid), point -> {
            if (point > 0 && corners[0] == null) corners[0] = hitbox.getCorners();
            return isPointVisible(world, eye, sightPoint(point, currentLocation, corners[0]));
        });
        if (seen < 0) {
            sightPoints.remove(uuid);
            return false;
        }
        sightPoints.put(uuid, seen);
        return true;
    }

    // The OBB corners checked after the center, top to bottom, one from each side:
    // 0 top front right, 4 top front left, 2 bottom front right, 6 bottom front left.
    private static final int[] SIGHT_CORNERS = {0, 4, 2, 6};
    static final int SIGHT_POINTS = 1 + SIGHT_CORNERS.length;

    /**
     * The first sight point {@code visible} accepts, trying {@code remembered} first and then the others in order,
     * each once; -1 when it accepts none. Point 0 is the center, 1 to 4 the corners.
     */
    static int firstVisiblePoint(Integer remembered, IntPredicate visible) {
        if (remembered != null && visible.test(remembered)) return remembered;
        for (int point = 0; point < SIGHT_POINTS; point++) {
            if (remembered != null && point == remembered) continue;
            if (visible.test(point)) return point;
        }
        return -1;
    }

    private static Vector sightPoint(int point, Location center, Vector3d[] corners) {
        if (point == 0) return center.toVector();
        Vector3d corner = corners[SIGHT_CORNERS[point - 1]];
        return new Vector(corner.x, corner.y, corner.z);
    }

    /**
     * Helper method to check if a specific point is visible from the eye,
     * with bounded traversal through non-occluding blocks.
     */
    private static boolean isPointVisible(World world, Vector eye, Vector point) {
        return BlockVisibilityRay.isVisible(
                eye,
                point,
                5,
                (origin, direction, distance) -> {
                    var result = world.rayTraceBlocks(
                            origin.toLocation(world),
                            direction,
                            distance,
                            FluidCollisionMode.NEVER,
                            true
                    );
                    if (result == null) return null;

                    Block hitBlock = result.getHitBlock();
                    if (hitBlock == null) {
                        Vector hitPosition = result.getHitPosition();
                        return new BlockVisibilityRay.Hit(
                                hitPosition,
                                hitPosition.getBlockX(),
                                hitPosition.getBlockY(),
                                hitPosition.getBlockZ(),
                                true
                        );
                    }

                    return new BlockVisibilityRay.Hit(
                            result.getHitPosition(),
                            hitBlock.getX(),
                            hitBlock.getY(),
                            hitBlock.getZ(),
                            hitBlock.getType().isOccluding()
                    );
                }
        );
    }

    private void displayTo(Player player) {
        displayTo(player, true);
    }

    private void displayTo(Player player, boolean allowBedrockResyncSchedule) {
        if (player == null || !player.isValid()) return;
        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock && com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled()) {
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "SkeletonWatchers.displayTo entry — player=" + player.getName()
                            + " v2=" + DefaultConfig.sendCustomModelsToBedrockClientsV2
                            + " allowResync=" + allowBedrockResyncSchedule
                            + " entityClass=" + (skeleton.getModeledEntity() == null
                                    ? "null" : skeleton.getModeledEntity().getClass().getSimpleName())
                            + " boneCount=" + skeleton.getBones().size()
                            + " underlyingInvisible=" + isUnderlyingEntityInvisible());
        }
        if (isBedrock && !DefaultConfig.sendCustomModelsToBedrockClientsV2 && skeleton.getModeledEntity().getUnderlyingEntity() != null) {
            if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled())
                com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                        "SkeletonWatchers.displayTo: V2=false fallback — showing native underlying entity to "
                                + player.getName() + " instead of bones");
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () ->
                    player.showEntity(MetadataHandler.PLUGIN, skeleton.getModeledEntity().getUnderlyingEntity())
            );
        }
        boolean wasAlreadyViewing = !viewers.add(player.getUniqueId());
        // Unwatched, the model skipped sampling its pose; from here on the clock samples it every tick, and this
        // catches up on the last one before the bones are shown in it.
        synchronized (skeleton) {
            skeleton.getModeledEntity().getAnimationComponent().refreshPose();
        }
        if (!isBedrock) {
            javaViewers.add(player.getUniqueId());
            syncUnderlyingEntityForJavaViewer(player);
        }
        if (isBedrock
                && DefaultConfig.sendCustomModelsToBedrockClientsV2
                && skeleton.getModeledEntity().getBedrockModeledEntity() != null
                && skeleton.getModeledEntity().getBedrockModeledEntity().isAvailable()) {
            skeleton.getModeledEntity().getBedrockModeledEntity().displayTo(player);
            if (skeleton.getModeledEntity() instanceof PropEntity propEntity)
                propEntity.showFakePropBlocksToPlayer(player);
            skeleton.getModeledEntity().getHitboxComponent().showPacketInteractionEntityTo(player);
            return;
        }
        if (isBedrock && com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled()) {
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "SkeletonWatchers.displayTo: wasAlreadyViewing=" + wasAlreadyViewing
                            + " for " + player.getName());
        }
        // Only show bones if the underlying entity is not invisible
        synchronized (skeleton) {
            AbstractPacketBundle initialDisplayBundle = createInitialDisplayBundle();
            if (!isUnderlyingEntityInvisible()) {
                if (initialDisplayBundle != null) skeleton.prepareDisplayPose(initialDisplayBundle);
                skeleton.getBones().forEach(bone -> bone.displayTo(player, initialDisplayBundle));
            }
            if (skeleton.getModeledEntity() instanceof PropEntity propEntity)
                propEntity.showFakePropBlocksToPlayer(player);
            // Interaction remains visible for click detection even when bones are hidden.
            skeleton.getModeledEntity().getHitboxComponent().showPacketInteractionEntityTo(player, initialDisplayBundle);
            if (initialDisplayBundle != null) initialDisplayBundle.send();
        }
        // Bedrock-only: the initial AddEntity → EntityData → Equipment → HeadPose
        // sequence races against Geyser's per-session entity-registration state.
        // When it loses the race, Bedrock binds the attachable to the wrong rotation
        // reference (or fails to bind at all) and the model appears broken until
        // the player walks out of range and back — which empirically forces the
        // attachable to re-bind correctly. We replicate that hide+show here on a
        // short delay so the user doesn't have to do it manually. The
        // allowBedrockResyncSchedule guard prevents the resync from re-triggering
        // itself: the deferred resync calls displayTo(player, false) so this
        // branch is suppressed, otherwise every resync fires another resync 10
        // ticks later and the model visibly flickers/resets twice per second.
        if (allowBedrockResyncSchedule && isBedrock && !wasAlreadyViewing) scheduleBedrockInitialResync(player);
    }

    private AbstractPacketBundle createInitialDisplayBundle() {
        NMSAdapter adapter = NMSManager.getAdapter();
        if (!NMSManager.isEnabled() || adapter == null) return null;
        return adapter.createPacketBundle();
    }

    /** The hidden entity Java viewers must track for its bones to ride it, or null when this model has none. */
    private Entity trackedBody() {
        if (!(skeleton.getModeledEntity() instanceof DynamicEntity de) || !de.isViewerTrackedBody()) return null;
        Entity underlyingEntity = de.getUnderlyingEntity();
        return underlyingEntity != null && underlyingEntity.isValid() ? underlyingEntity : null;
    }

    /**
     * Shows a tracked body to a new Java viewer of the model, veiled so its equipment never renders, before the
     * bones mount it. Any other hidden body stays hidden from the viewer as before.
     */
    private void syncUnderlyingEntityForJavaViewer(Player player) {
        Entity body = trackedBody();
        if (body == null || MetadataHandler.PLUGIN == null) {
            hideUnderlyingEntityFromJavaViewer(player);
            return;
        }
        if (!bodyViewers.add(player.getUniqueId())) return;
        trackedBodyId = body.getEntityId();
        PacketEquipmentVeil.veil(player.getUniqueId(), trackedBodyId);
        player.showEntity(MetadataHandler.PLUGIN, body);
    }

    /** Hides the tracked body from a viewer who stops seeing the model, then lifts the veil. */
    private void releaseTrackedBody(UUID uuid) {
        if (!bodyViewers.remove(uuid)) return;
        // Off the main thread the model is being torn down with its entity, which untracks it anyway.
        Entity body = Bukkit.isPrimaryThread() ? trackedBody() : null;
        Player player = Bukkit.getPlayer(uuid);
        // Bukkit refuses hideEntity from a plugin that is disabling; the rest of the shutdown must still run.
        if (body != null && player != null && player.isValid() && MetadataHandler.PLUGIN != null
                && MetadataHandler.PLUGIN.isEnabled())
            player.hideEntity(MetadataHandler.PLUGIN, body);
        if (trackedBodyId != -1) PacketEquipmentVeil.unveil(uuid, trackedBodyId);
    }

    /** Releases every tracked body when the model goes away. */
    public void releaseTrackedBodies() {
        for (UUID uuid : bodyViewers) releaseTrackedBody(uuid);
    }

    private void hideUnderlyingEntityFromJavaViewer(Player player) {
        if (MetadataHandler.PLUGIN == null || skeleton.getModeledEntity() == null) {
            return;
        }
        // Mounts made with cosmetic invisibility stay tracked: a client that cannot see the vehicle
        // never seats its rider, so hiding it here leaves the rider behind.
        if (skeleton.getModeledEntity() instanceof DynamicEntity de && de.isCosmeticInvisibility()) {
            return;
        }
        Entity underlyingEntity = skeleton.getModeledEntity().getUnderlyingEntity();
        if (underlyingEntity == null || !underlyingEntity.isValid()) {
            return;
        }
        player.hideEntity(MetadataHandler.PLUGIN, underlyingEntity);
    }

    /**
     * Schedules a {@code hideFrom + displayTo} cycle ~10 ticks (500ms) after the
     * initial Bedrock display. The cycle forces Geyser to tear down the bedrock
     * entity binding and re-create it, which reliably triggers attachable
     * rebinding on the Bedrock client. Without this the initial display works
     * sporadically and players have to manually walk out of range and back.
     * <p>
     * Calls {@code displayTo(player, false)} explicitly so the resync's own
     * displayTo doesn't schedule another resync — otherwise it would loop
     * forever, visibly flickering the model every 500ms.
     * <p>
     * Cost: one extra round-trip of AddEntity + EntityData + Equipment per
     * Bedrock viewer per spawn. Bandwidth-cheap, only fires once per add-viewer
     * event, and only for Bedrock — Java viewers see no extra packets.
     */
    private void scheduleBedrockInitialResync(Player player) {
        final UUID uuid = player.getUniqueId();
        if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled())
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "SkeletonWatchers.scheduleBedrockInitialResync: queued 10-tick hide+show for "
                            + player.getName() + " (Geyser attachable rebind dance)");
        Bukkit.getScheduler().runTaskLater(MetadataHandler.PLUGIN, () -> {
            // Abort if the entity was removed in the meantime. Without this
            // check, if the entity was destroyed (e.g. /fmm disguise twice in
            // quick succession, where the first disguise is removed before its
            // 10-tick resync fires), the resync would re-send AddEntity packets
            // for the destroyed entity's bones and a ghost copy of the old
            // model would persist on the bedrock client until they walked out
            // of range. Manifested as "double disguise" — both old and new
            // models visible at once.
            if (skeleton.getModeledEntity() == null
                    || skeleton.getModeledEntity().isRemoved()) return;
            if (!viewers.contains(uuid)) return; // player left or got hideFrom'd in the meantime
            Player p = Bukkit.getPlayer(uuid);
            if (p == null || !p.isOnline()) return;
            if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled())
                com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                        "SkeletonWatchers.scheduleBedrockInitialResync: FIRING hide+show now for "
                                + p.getName());
            hideFrom(uuid);
            displayTo(p, false);
        }, 10L);
    }

    private void hideFrom(UUID uuid) {
        hideFrom(uuid, true);
    }

    /** {@code releaseBody} false keeps a tracked body shown to the viewer, for a resync that only re-sends bones. */
    private void hideFrom(UUID uuid, boolean releaseBody) {
        // Always clean up viewer state, even if player is offline
        viewers.remove(uuid);
        javaViewers.remove(uuid);
        // Same null guard the other methods in this class use — the skeleton's
        // modeled entity can be null during construction/teardown windows.
        var modeledEntity = skeleton.getModeledEntity();
        if (modeledEntity != null && modeledEntity.getBedrockModeledEntity() != null) {
            modeledEntity.getBedrockModeledEntity().hideFrom(uuid);
        }
        if (modeledEntity != null) modeledEntity.getNameplate().hideFrom(uuid);
        if (modeledEntity != null) modeledEntity.getParticleComponent().hideFrom(uuid);
        skeleton.getBones().forEach(bone -> bone.hideFrom(uuid));
        if (releaseBody) releaseTrackedBody(uuid);
        // Hide the packet interaction entity (uses UUID, works even if player is offline)
        if (modeledEntity != null)
            modeledEntity.getHitboxComponent().hidePacketInteractionEntityFrom(uuid);

        // Player-specific cleanup only if player is online
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isValid() || modeledEntity == null) return;

        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock && !DefaultConfig.sendCustomModelsToBedrockClientsV2 && modeledEntity.getUnderlyingEntity() != null) {
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () ->
                    player.hideEntity(MetadataHandler.PLUGIN, modeledEntity.getUnderlyingEntity())
            );
        }
        if (modeledEntity instanceof PropEntity propEntity)
            propEntity.showRealBlocksToPlayer(player);
    }

    public void sendPackets(Bone bone, AbstractPacketBundle abstractPacketBundle) {
        if (!hasObservers()) return;
        // Skip bone update packets when entity is invisible - bones are hidden
        if (wasInvisible) return;
        bone.sendUpdatePacket(abstractPacketBundle);
    }
}
