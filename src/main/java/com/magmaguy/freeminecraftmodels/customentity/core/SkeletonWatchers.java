package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;

public class SkeletonWatchers {
    private final Skeleton skeleton;
    private final Set<UUID> viewers = new CopyOnWriteArraySet<>();
    private volatile boolean wasInvisible = false;

    public HashSet<UUID> getViewers() {
        return new HashSet<>(viewers);
    }

    private final int resetTimer = 20 * 60;
    private int counter = ThreadLocalRandom.current().nextInt(20 * 60);

    // Cadence at which ModeledEntitiesClock invokes tickPrimaryThread().
    private static final int VIEWER_STATE_INTERVAL_TICKS = 4;

    public SkeletonWatchers(Skeleton skeleton) {
        this.skeleton = skeleton;
    }

    public boolean hasObservers() {
        return !viewers.isEmpty();
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
    }

    private volatile long lastResyncTime = 0L;

    // Clients gets a bit of drift due to some inaccuracies, this resyncs the skeleton
    private void resync(int elapsedTicks) {
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
                hideFrom(viewer);
                Player p = Bukkit.getPlayer(viewer);
                if (p != null) {
                    displayTo(p);
                }
            });
        }
    }

    private static final int MIN_VIEW_DISTANCE = 10;
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

        // Pseudo load balancer: only honor the close-range proximity override (always-show
        // within MIN_VIEW_DISTANCE, skipping the raytrace) when this world isn't crowded.
        // In a dense multi-floor hub the override would make every nearby model a viewer even
        // when occluded by floors/walls; disabling it lets isModelInSight() cull them.
        boolean proximityOverride = ModelDensity.proximityOverrideActive(
                currentLocation.getWorld());

        for (Player player : currentLocation.getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(currentLocation);

            if ((proximityOverride && distance < MIN_VIEW_DISTANCE_SQUARED) ||
                    (distance < maxViewDistanceSquared && isModelInSight(player, currentLocation))) {
                newPlayers.add(player.getUniqueId());
                if (!viewers.contains(player.getUniqueId())) displayTo(player);
            }
        }

        for (UUID viewer : viewers) {
            if (!newPlayers.contains(viewer)) {
                toRemove.add(viewer);
            }
        }

        toRemove.forEach(viewers::remove);
        toRemove.forEach(this::hideFrom);
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

        // First try the center point (most efficient check)
        Vector centerPoint = currentLocation.toVector();
        if (isPointVisible(player, centerPoint)) {
            return true;
        }

        // If center isn't visible, check key points of the bounding box
        Vector3d[] corners = hitbox.getCorners();

        // Check every other corner, prioritizing top to bottom
        // OBB corner layout:
        // 0: top front right, 1: top back right (top corners)
        // 4: top front left, 5: top back left (top corners)
        // 2: bottom front right, 3: bottom back right (bottom corners)
        // 6: bottom front left, 7: bottom back left (bottom corners)

        // Check top corners first (0, 4) - one from each side, skipping every other one
        if (isPointVisible(player, new Vector(corners[0].x, corners[0].y, corners[0].z))) {
            return true;
        }
        if (isPointVisible(player, new Vector(corners[4].x, corners[4].y, corners[4].z))) {
            return true;
        }

        // Then check bottom corners (2, 6) - one from each side, maintaining top-to-bottom order
        if (isPointVisible(player, new Vector(corners[2].x, corners[2].y, corners[2].z))) {
            return true;
        }
        return isPointVisible(player, new Vector(corners[6].x, corners[6].y, corners[6].z));

        // No points were visible
    }

    /**
     * Helper method to check if a specific point is visible to the player,
     * with bounded traversal through non-occluding blocks.
     */
    private boolean isPointVisible(Player player, Vector point) {
        Location eyeLocation = player.getEyeLocation();
        return BlockVisibilityRay.isVisible(
                eyeLocation.toVector(),
                point,
                5,
                (origin, direction, distance) -> {
                    var result = eyeLocation.getWorld().rayTraceBlocks(
                            origin.toLocation(eyeLocation.getWorld()),
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
        if (!isBedrock) {
            hideUnderlyingEntityFromJavaViewer(player);
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
        AbstractPacketBundle initialDisplayBundle = createInitialDisplayBundle();
        if (!isUnderlyingEntityInvisible())
            skeleton.getBones().forEach(bone -> bone.displayTo(player, initialDisplayBundle));
        if (skeleton.getModeledEntity() instanceof PropEntity propEntity)
            propEntity.showFakePropBlocksToPlayer(player);
        // Show the packet interaction entity for click detection (always, even when invisible)
        skeleton.getModeledEntity().getHitboxComponent().showPacketInteractionEntityTo(player, initialDisplayBundle);
        if (initialDisplayBundle != null) initialDisplayBundle.send();
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

    private void hideUnderlyingEntityFromJavaViewer(Player player) {
        if (MetadataHandler.PLUGIN == null || skeleton.getModeledEntity() == null) {
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
        // Always clean up viewer state, even if player is offline
        viewers.remove(uuid);
        // Same null guard the other methods in this class use — the skeleton's
        // modeled entity can be null during construction/teardown windows.
        var modeledEntity = skeleton.getModeledEntity();
        if (modeledEntity != null && modeledEntity.getBedrockModeledEntity() != null) {
            modeledEntity.getBedrockModeledEntity().hideFrom(uuid);
        }
        skeleton.getBones().forEach(bone -> bone.hideFrom(uuid));
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
