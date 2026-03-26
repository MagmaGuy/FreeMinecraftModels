package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages all mount seats for a single modeled entity.
 * Tick task only runs while at least one seat is occupied.
 */
public class MountPointManager {

    @Getter
    private final List<MountSeat> seats = new ArrayList<>();
    private BukkitTask tickTask;

    public MountPointManager(Skeleton skeleton, com.magmaguy.freeminecraftmodels.customentity.ModeledEntity modeledEntity) {
        for (Bone bone : skeleton.getMountPointBones()) {
            seats.add(new MountSeat(bone, modeledEntity));
        }
    }

    /**
     * Returns whether this manager has any mount points defined.
     */
    public boolean hasMountPoints() {
        return !seats.isEmpty();
    }

    /**
     * Attempts to mount the player onto the first available seat.
     *
     * @param player the player to mount
     * @return true if the player was successfully mounted
     */
    public boolean tryMount(Player player) {
        Logger.debug("trying to mount");

        // Don't mount if the player is already in one of our seats
        for (MountSeat seat : seats) {
            if (player.equals(seat.getOccupant())) return false;
        }
        for (MountSeat seat : seats) {
            if (!seat.isOccupied()) {
                boolean success = seat.mount(player);
                if (success) {
                    ensureTickTaskRunning();
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Handles a player dismount. Called from the dismount event listener.
     *
     * @param player    the player who dismounted
     * @param armorStand the armor stand they dismounted from
     */
    public void handleDismount(Player player, ArmorStand armorStand) {
        for (MountSeat seat : seats) {
            if (armorStand.equals(seat.getVehicle())) {
                seat.dismount();
                stopTickTaskIfEmpty();
                return;
            }
        }
    }

    /**
     * Checks if the given armor stand belongs to any of this manager's seats.
     */
    public boolean isMountArmorStand(ArmorStand armorStand) {
        for (MountSeat seat : seats) {
            if (armorStand.equals(seat.getVehicle())) return true;
        }
        return false;
    }

    /**
     * Cleans up all seats and stops the tick task.
     */
    public void cleanup() {
        for (MountSeat seat : seats) {
            seat.cleanup();
        }
        stopTickTask();
    }

    private void ensureTickTaskRunning() {
        if (tickTask != null && !tickTask.isCancelled()) return;
        tickTask = new BukkitRunnable() {
            @Override
            public void run() {
                boolean anyOccupied = false;
                for (MountSeat seat : seats) {
                    if (seat.isOccupied()) {
                        seat.tick();
                        anyOccupied = true;
                    }
                }
                if (!anyOccupied) {
                    stopTickTask();
                }
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0L, 1L);
    }

    private void stopTickTaskIfEmpty() {
        boolean anyOccupied = false;
        for (MountSeat seat : seats) {
            if (seat.isOccupied()) {
                anyOccupied = true;
                break;
            }
        }
        if (!anyOccupied) stopTickTask();
    }

    private void stopTickTask() {
        if (tickTask != null && !tickTask.isCancelled()) {
            tickTask.cancel();
        }
        tickTask = null;
    }
}
