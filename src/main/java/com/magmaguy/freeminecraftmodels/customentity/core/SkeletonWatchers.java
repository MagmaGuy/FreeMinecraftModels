package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;

public class SkeletonWatchers implements Listener {
    private final Skeleton skeleton;
    private final Set<UUID> viewers = new CopyOnWriteArraySet<>();
    private boolean wasInvisible = false;

    public HashSet<UUID> getViewers() {
        return new HashSet<>(viewers);
    }

    private final int resetTimer = 20 * 60;
    private int counter = ThreadLocalRandom.current().nextInt(20 * 60);

    public SkeletonWatchers(Skeleton skeleton) {
        this.skeleton = skeleton;
        tick();
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

    private int watcherUpdateCounter = 0;
    private static final int UPDATE_INTERVAL = 4;

    public void tick() {
        watcherUpdateCounter++;
        if (watcherUpdateCounter >= UPDATE_INTERVAL) {
            updateWatcherList();
            watcherUpdateCounter = 0;
        }
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
        resync(false);
    }

    private volatile long lastResyncTime = 0L;

    // Clients gets a bit of drift due to some inaccuracies, this resyncs the skeleton
    public void resync(boolean force) {
        long now = System.currentTimeMillis();

        // throttle: if not forced and we ran <1s ago, skip entirely
        if (!force && now - lastResyncTime < 1_000) {
            return;
        }

        counter++;
        // your existing random / timer logic
        if (force || (counter > resetTimer && ThreadLocalRandom.current().nextBoolean())) {
            // update timestamp and reset counter
            lastResyncTime = now;
            counter = 0;

            // do the actual hide/display (displayTo already respects invisibility)
            Set<UUID> tempViewers = Collections.synchronizedSet(new HashSet<>(viewers));
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
        if (skeleton.getCurrentLocation() == null) return;

        List<UUID> newPlayers = new ArrayList<>();
        List<UUID> toRemove = new ArrayList<>();

        int effectiveViewDistance = skeleton.getModeledEntity() != null
                ? skeleton.getModeledEntity().getEffectiveViewDistance()
                : DefaultConfig.maxModelViewDistance;
        double maxViewDistanceSquared = (double) effectiveViewDistance * effectiveViewDistance;

        for (Player player : skeleton.getCurrentLocation().getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(skeleton.getCurrentLocation());

            if (distance < MIN_VIEW_DISTANCE_SQUARED ||
                    distance < maxViewDistanceSquared && isModelInSight(player)) {
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
     * @param player the player to check for
     * @return true if any part of the entity is visible
     */
    private boolean isModelInSight(Player player) {
        // Quick sanity checks
        if (skeleton.getModeledEntity() == null) return true;

        // Get the entity's hitbox
        OrientedBoundingBox hitbox = skeleton.getModeledEntity().getHitboxComponent().getObbHitbox();
        if (hitbox == null) return true;

        // First try the center point (most efficient check)
        Vector centerPoint = skeleton.getCurrentLocation().toVector();
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
     * with recursive handling of non-occluding blocks
     */
    private boolean isPointVisible(Player player, Vector point) {
        return isPointVisibleRecursive(player.getEyeLocation(), point, 5); // Max 5 passes through non-occluding blocks
    }

    private boolean isPointVisibleRecursive(Location eyeLocation, Vector targetPoint, int remainingPasses) {
        if (remainingPasses <= 0) {
            return false; // Prevent infinite recursion
        }

        Vector toPoint = targetPoint.clone().subtract(eyeLocation.toVector());
        double distance = toPoint.length();
        toPoint.normalize();

        var result = eyeLocation.getWorld().rayTraceBlocks(
                eyeLocation,
                toPoint,
                distance,
                FluidCollisionMode.NEVER,
                true
        );

        // No block was hit, clear line of sight
        if (result == null) {
            return true;
        }

        // A block was hit, check if it's occluding or non-occluding
        if (result.getHitBlock() != null) {
            if (result.getHitBlock().getType().isOccluding()) {
                // Occluding block (like stone) blocks vision
                return false;
            } else {
                // Non-occluding block (like glass), continue tracing through it
                // Create a new starting point past this block

                // Get hit position and normalize our direction vector
                Location hitPos = result.getHitPosition().toLocation(eyeLocation.getWorld());
                Vector normalizedDirection = toPoint.clone().normalize();

                // Use a larger offset to ensure we move past the block
                // 0.1 blocks (1/10th of a block) should be sufficient
                Location nextLocation = hitPos.clone().add(
                        normalizedDirection.clone().multiply(2)
                );

                // Continue the ray trace
                return isPointVisibleRecursive(nextLocation, targetPoint, remainingPasses - 1);
            }
        }

        // Something else was hit (should rarely happen)
        return false;
    }

    private void displayTo(Player player) {
        displayTo(player, true);
    }

    private void displayTo(Player player, boolean allowBedrockResyncSchedule) {
        if (player == null || !player.isValid()) return;
        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock) {
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
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "SkeletonWatchers.displayTo: V2=false fallback — showing native underlying entity to "
                            + player.getName() + " instead of bones");
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () ->
                    player.showEntity(MetadataHandler.PLUGIN, skeleton.getModeledEntity().getUnderlyingEntity())
            );
        }
        boolean wasAlreadyViewing = !viewers.add(player.getUniqueId());
        if (isBedrock) {
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "SkeletonWatchers.displayTo: wasAlreadyViewing=" + wasAlreadyViewing
                            + " for " + player.getName());
        }
        // Only show bones if the underlying entity is not invisible
        if (!isUnderlyingEntityInvisible())
            skeleton.getBones().forEach(bone -> bone.displayTo(player));
        if (skeleton.getModeledEntity() instanceof PropEntity propEntity)
            propEntity.showFakePropBlocksToPlayer(player);
        // Show the packet interaction entity for click detection (always, even when invisible)
        skeleton.getModeledEntity().getHitboxComponent().showPacketInteractionEntityTo(player);
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
        skeleton.getBones().forEach(bone -> bone.hideFrom(uuid));
        // Hide the packet interaction entity (uses UUID, works even if player is offline)
        skeleton.getModeledEntity().getHitboxComponent().hidePacketInteractionEntityFrom(uuid);

        // Player-specific cleanup only if player is online
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isValid()) return;

        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock && !DefaultConfig.sendCustomModelsToBedrockClientsV2 && skeleton.getModeledEntity().getUnderlyingEntity() != null) {
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () ->
                    player.hideEntity(MetadataHandler.PLUGIN, skeleton.getModeledEntity().getUnderlyingEntity())
            );
        }
        if (skeleton.getModeledEntity() instanceof PropEntity propEntity)
            propEntity.showRealBlocksToPlayer(player);
    }

    public void sendPackets(Bone bone, AbstractPacketBundle abstractPacketBundle) {
        if (!hasObservers()) return;
        // Skip bone update packets when entity is invisible - bones are hidden
        if (wasInvisible) return;
        bone.sendUpdatePacket(abstractPacketBundle);
    }
}
