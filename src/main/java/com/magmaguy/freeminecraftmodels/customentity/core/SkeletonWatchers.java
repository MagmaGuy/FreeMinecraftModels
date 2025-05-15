package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

public class SkeletonWatchers implements Listener {
    private final Skeleton skeleton;
    private final Set<UUID> viewers = new CopyOnWriteArraySet<>();
    private BukkitTask tick;

    public SkeletonWatchers(Skeleton skeleton) {
        this.skeleton = skeleton;
        tick();
    }

    public void remove() {
        tick.cancel();
    }

    private void tick() {
        tick = new BukkitRunnable() {
            @Override
            public void run() {
                updateWatcherList();
            }
        }.runTaskTimerAsynchronously(MetadataHandler.PLUGIN, 0, 1);
    }

    private void updateWatcherList() {
        List<UUID> newPlayers = new ArrayList<>();
        for (Player player : skeleton.getCurrentLocation().getWorld().getPlayers()) {
            double distance = player.getLocation().distanceSquared(skeleton.getCurrentLocation());
            // Distance check first
            if (distance < Math.pow(Bukkit.getSimulationDistance() * 16D, 2)) {
                // Now check if the model is in sight via ray tracing
                if (distance > Math.pow(20, 2) && !isModelInSight(player)) {
                    continue; // Skip if the view is obstructed
                }
                newPlayers.add(player.getUniqueId());
                if (!viewers.contains(player.getUniqueId())) {
                    displayTo(player);
                }
            }
        }

        List<UUID> toRemove = new ArrayList<>();
        for (UUID viewer : viewers) {
            if (!newPlayers.contains(viewer)) {
                toRemove.add(viewer);
            }
        }
        viewers.removeAll(toRemove);
        toRemove.forEach(this::hideFrom);
    }

    /**
     * Checks if the skeleton model is in the player's line of sight.
     *
     * @param player the player to check for
     * @return true if no blocks obstruct the view from the player's eyes to the model location.
     */
    private boolean isModelInSight(Player player) {
        if (skeleton.getDynamicEntity() == null) return true;
        if (skeleton.getDynamicEntity().getHitbox() == null) return true;

        // Retrieve the hitbox. Adjust the type as necessary.
        BoundingBox hitbox = skeleton.getDynamicEntity().getHitbox();
        // Assume the hitbox is located in the same world as the skeleton.
        // Retrieve the world from the skeleton's current location.
        org.bukkit.World world = skeleton.getCurrentLocation().getWorld();

        // Create sample points: center and eight corners of the hitbox.
        List<Location> samplePoints = new ArrayList<>();

        // Center point
        samplePoints.add(new Location(world, hitbox.getCenterX(), hitbox.getCenterY(), hitbox.getCenterZ()));

        // Corners of the hitbox
        samplePoints.add(new Location(world, hitbox.getMinX(), hitbox.getMinY(), hitbox.getMinZ()));
        samplePoints.add(new Location(world, hitbox.getMinX(), hitbox.getMinY(), hitbox.getMaxZ()));
        samplePoints.add(new Location(world, hitbox.getMinX(), hitbox.getMaxY(), hitbox.getMinZ()));
        samplePoints.add(new Location(world, hitbox.getMinX(), hitbox.getMaxY(), hitbox.getMaxZ()));
        samplePoints.add(new Location(world, hitbox.getMaxX(), hitbox.getMinY(), hitbox.getMinZ()));
        samplePoints.add(new Location(world, hitbox.getMaxX(), hitbox.getMinY(), hitbox.getMaxZ()));
        samplePoints.add(new Location(world, hitbox.getMaxX(), hitbox.getMaxY(), hitbox.getMinZ()));
        samplePoints.add(new Location(world, hitbox.getMaxX(), hitbox.getMaxY(), hitbox.getMaxZ()));

        // Get the player's eye location
        Location eyeLocation = player.getEyeLocation();

        // For each sample point, perform a ray trace.
        for (Location target : samplePoints) {
            Vector direction = target.toVector().subtract(eyeLocation.toVector());
            double distance = direction.length();
            direction.normalize();

            // Raytrace from the player's eye to the target sample point.
            RayTraceResult result = player.getWorld().rayTraceBlocks(eyeLocation, direction, distance);
            // If nothing blocks the ray, the point is visible.
            if (result == null) {
                return true;
            }
        }

        // If all sample points are obstructed, return false.
        return false;
    }

    private void displayTo(Player player) {
        viewers.add(player.getUniqueId());
        skeleton.getBones().forEach(bone -> bone.displayTo(player));
    }

    private void hideFrom(UUID uuid) {
        viewers.remove(uuid);
        skeleton.getBones().forEach(bone -> bone.hideFrom(uuid));
    }

    //Todo: one day rounding errors might be fixed such that this is no longer needed
    public void reset() {
        Set<UUID> tempViewers = Collections.synchronizedSet(new HashSet<>(viewers));
        tempViewers.forEach(viewer -> {
            hideFrom(viewer);
            displayTo(Bukkit.getPlayer(viewer));
        });
    }

    public void sendPackets(Bone bone) {
        if (viewers.isEmpty()) return;
        bone.sendUpdatePacket();
    }
}
