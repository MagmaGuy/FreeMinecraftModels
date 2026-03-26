package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.listeners.ArmorStandListener;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

/**
 * Represents a single seat on a mount-point bone.
 * The seat uses an invisible ArmorStand as the vehicle entity.
 */
public class MountSeat {

    public static final NamespacedKey MOUNT_SEAT_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_mount_seat");

    @Getter
    private final Bone bone;
    private final ModeledEntity modeledEntity;
    @Getter
    private ArmorStand vehicle;
    @Getter
    private Player occupant;

    public MountSeat(Bone bone, ModeledEntity modeledEntity) {
        this.bone = bone;
        this.modeledEntity = modeledEntity;
    }

    private float getEntityYaw() {
        Location loc = modeledEntity.getSpawnLocation();
        return loc != null ? loc.getYaw() : 0f;
    }

    /**
     * Mounts a player onto this seat by spawning a vehicle ArmorStand
     * at the bone location and adding the player as a passenger.
     *
     * @param player the player to mount
     * @return true if the mount was successful
     */
    public boolean mount(Player player) {
        if (occupant != null) return false;
        Location loc = bone.getBoneLocation();
        if (loc == null || loc.getWorld() == null) return false;

        loc.setYaw(getEntityYaw());
        ArmorStandListener.bypass = true;
        vehicle = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        vehicle.setVisible(false);
        vehicle.setGravity(false);
        vehicle.setInvulnerable(true);
        vehicle.setPersistent(false);
        vehicle.setSmall(true);
        vehicle.setMarker(true);
        vehicle.getPersistentDataContainer().set(MOUNT_SEAT_KEY, PersistentDataType.BYTE, (byte) 1);
        ArmorStandListener.bypass = false;

        vehicle.addPassenger(player);
        occupant = player;
        return true;
    }

    /**
     * Dismounts the current occupant and removes the vehicle ArmorStand.
     */
    public void dismount() {
        if (occupant != null && vehicle != null) {
            vehicle.removePassenger(occupant);
        }
        occupant = null;
        if (vehicle != null) {
            vehicle.remove();
        }
        vehicle = null;
    }

    /**
     * Updates the vehicle position to follow the bone location.
     * Should be called each tick while occupied.
     */
    public void tick() {
        if (vehicle == null || occupant == null) return;
        if (!occupant.isOnline() || !occupant.isValid()) {
            dismount();
            return;
        }
        Location boneLoc = bone.getBoneLocation();
        if (boneLoc != null && boneLoc.getWorld() != null) {
            boneLoc.setYaw(getEntityYaw());
            vehicle.teleport(boneLoc);
        }
    }

    /**
     * Returns whether this seat currently has an occupant.
     */
    public boolean isOccupied() {
        return occupant != null;
    }

    /**
     * Cleans up the seat, removing the vehicle and clearing the occupant.
     */
    public void cleanup() {
        dismount();
    }
}
