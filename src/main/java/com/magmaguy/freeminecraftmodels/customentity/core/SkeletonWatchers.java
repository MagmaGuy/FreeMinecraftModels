package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.thirdparty.BedrockChecker;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.util.Vector;
import org.joml.Vector3d;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ThreadLocalRandom;

public class SkeletonWatchers implements Listener {
    private final Skeleton skeleton;
    private final Set<UUID> viewers = new CopyOnWriteArraySet<>();

    public HashSet<UUID> getViewers() {
        return new HashSet<>(viewers);
    }

    // Reused collections to avoid constant reallocation
    private final List<UUID> newPlayers = new ArrayList<>();
    private final List<UUID> toRemove = new ArrayList<>();
    private final int resetTimer = 20 * 60;
    private int counter = ThreadLocalRandom.current().nextInt(20 * 60);

    public SkeletonWatchers(Skeleton skeleton) {
        this.skeleton = skeleton;
        tick();
    }
    private boolean updateWatchers = true;

    public boolean hasObservers() {
        return !viewers.isEmpty();
    }

    private int watcherUpdateCounter = 0;
    private static final int UPDATE_INTERVAL = 4;

    public void tick() {
        watcherUpdateCounter++;
        if (watcherUpdateCounter >= UPDATE_INTERVAL) {
            updateWatcherList();
            watcherUpdateCounter = 0;
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

            // do the actual hide/display
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

    private void updateWatcherList() {
        if (skeleton.getCurrentLocation() == null) return;

        // Clear reused collections instead of creating new ones
        newPlayers.clear();
        toRemove.clear();

        double sightCheckDistanceMin = Math.pow(MIN_VIEW_DISTANCE, 2);
        double maxViewDistanceSquared = Math.pow(DefaultConfig.maxModelViewDistance, 2);

        for (Player player : skeleton.getCurrentLocation().getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(skeleton.getCurrentLocation());

            if (distance < sightCheckDistanceMin ||
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
        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock && !DefaultConfig.sendCustomModelsToBedrockClients && skeleton.getModeledEntity().getUnderlyingEntity() != null)
            player.showEntity(MetadataHandler.PLUGIN, skeleton.getModeledEntity().getUnderlyingEntity());
        viewers.add(player.getUniqueId());
        skeleton.getBones().forEach(bone -> bone.displayTo(player));
        if (skeleton.getModeledEntity() instanceof PropEntity propEntity)
            propEntity.showFakePropBlocksToPlayer(player);
    }

    private void hideFrom(UUID uuid) {
        boolean isBedrock = BedrockChecker.isBedrock(Bukkit.getPlayer(uuid));
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isValid()) return;
        if (isBedrock && !DefaultConfig.sendCustomModelsToBedrockClients && skeleton.getModeledEntity().getUnderlyingEntity() != null)
            player.hideEntity(MetadataHandler.PLUGIN, skeleton.getModeledEntity().getUnderlyingEntity());
        viewers.remove(uuid);
        skeleton.getBones().forEach(bone -> bone.hideFrom(uuid));
        if (skeleton.getModeledEntity() instanceof PropEntity propEntity)
            propEntity.showRealBlocksToPlayer(player);
    }

    public void sendPackets(Bone bone) {
        if (viewers.isEmpty()) return;
        bone.sendUpdatePacket();
    }
}