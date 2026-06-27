package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import com.magmaguy.freeminecraftmodels.packets.PacketEntityDisplayHelper;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class HitboxComponent {
    private static final double PLAYER_COLLISION_RANGE_SQUARED = 100.0; // 10 blocks
    private final ModeledEntity modeledEntity;
    @Getter
    private OrientedBoundingBox obbHitbox = null;
    private PacketInteractionEntity packetInteractionEntity = null;

    public HitboxComponent(ModeledEntity modeledEntity) {
        this.modeledEntity = modeledEntity;
    }

    public OrientedBoundingBox getObbHitbox() {
        if (obbHitbox == null) {
            if (modeledEntity.getSkeletonBlueprint().getHitbox() != null) {
                obbHitbox = new OrientedBoundingBox(
                        modeledEntity.getSkeleton().getCurrentLocation(),
                        //For some reason the width is the Z axis, not the X axis
                        modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ(),
                        modeledEntity.getSkeletonBlueprint().getHitbox().getHeight(),
                        //For some reason the width is the X axis, not the Z axis
                        modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX());
                obbHitbox.setAssociatedEntity(modeledEntity);
                return obbHitbox;
            } else {
                obbHitbox = new OrientedBoundingBox(modeledEntity.getSkeleton().getCurrentLocation(), 1, 2, 1);
                obbHitbox.setAssociatedEntity(modeledEntity);
                return obbHitbox;
            }
        } else return obbHitbox;
    }

    /**
     * Async!
     *
     * @param tickCounter
     */
    public void tick(int tickCounter, com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle packetBundle) {
        // Always update the OBB hitbox position, even if no blueprint hitbox is configured
        // (getObbHitbox() creates a default 1x2x1 hitbox when blueprint hitbox is null).
        // This is the server-side click-detection box and is cheap (no packets), so it always runs.
        getObbHitbox().update(modeledEntity.getLocation());

        // Update the client-side interaction entity position (sends packets) — only when moved.
        updatePacketInteractionEntityPosition(packetBundle);

        if (modeledEntity.getInteractionComponent().getHitboxContactCallback() == null) return;
        if (tickCounter % 2 == 0) {
            checkPlayerCollisions();
        }
    }

    /**
     * Checks for collisions with nearby players and fires appropriate events
     */
    public void checkPlayerCollisions() {
        if (modeledEntity.getHitboxComponent().getObbHitbox() == null) return;
        if (modeledEntity.getWorld() == null) return;

        Location entityLocation = modeledEntity.getLocation();
        // Local scratch — checkPlayerCollisions is invoked from the async
        // ModeledEntitiesClock, and consecutive ticks can overlap on different
        // async threads when a tick takes >1 server tick. An instance-level
        // scratch list was being clear()ed under one thread's iteration on the
        // other, producing a ConcurrentModificationException at the iteration
        // below. Allocating per call keeps the scope thread-local.
        List<Player> nearbyPlayers = new ArrayList<>();
        for (Player player : modeledEntity.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(entityLocation) < PLAYER_COLLISION_RANGE_SQUARED) {
                nearbyPlayers.add(player);
            }
        }

        // For each nearby player, check collision
        for (Player player : nearbyPlayers) {
            if (isPlayerColliding(player)) {
                // Fire the appropriate hitbox contact event
                modeledEntity.getInteractionComponent().callHitboxContactEvent(player);
            }
        }
    }

    /**
     * Checks if a player is colliding with this entity's OBB hitbox
     */
    protected boolean isPlayerColliding(Player player) {
        OrientedBoundingBox obb = getObbHitbox();
        org.bukkit.util.BoundingBox aabb = player.getBoundingBox();
        // Cheap reject first, then exact SAT — avoids false hits on rotated, long entities.
        return obb.quickAabbReject(aabb) && obb.intersectsAABB(aabb);
    }

    public void setCustomHitboxOnUnderlyingEntity() {
        if (modeledEntity.getSkeletonBlueprint().getHitbox() == null) return;
        NMSManager.getAdapter().setCustomHitbox(modeledEntity.getUnderlyingEntity(), modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() < modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ() ? (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() : (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ(), (float) modeledEntity.getSkeletonBlueprint().getHitbox().getHeight(), true);
    }

    /**
     * Creates a packet-only Interaction entity for click detection.
     * This entity is invisible to players but receives their clicks.
     * Should be called after the model is spawned.
     * Props also get packet interaction entities for reliable right-click detection
     * even when no block is behind the model. Dynamic entities with underlying
     * living entities use OBB-based detection instead.
     */
    public void createPacketInteractionEntity() {
        if (packetInteractionEntity != null) return;
        if (modeledEntity.getLocation() == null) return;
        // Skip non-prop entities that have underlying entities - they use OBB-based detection
        if (modeledEntity.getUnderlyingEntity() != null && !(modeledEntity instanceof PropEntity)) return;

        // Get hitbox dimensions - use the smaller dimension for width since
        // Interaction entities have equal X and Z sizes
        float width, height;
        if (modeledEntity.getSkeletonBlueprint().getHitbox() != null) {
            width = (float) Math.min(
                    modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX(),
                    modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ()
            );
            height = (float) modeledEntity.getSkeletonBlueprint().getHitbox().getHeight();
        } else {
            // Default hitbox size
            width = 1.0f;
            height = 2.0f;
        }

        try {
            packetInteractionEntity = NMSManager.getAdapter().createPacketInteractionEntity(
                    modeledEntity.getLocation(),
                    width,
                    height
            );

            // Set up right-click callback
            packetInteractionEntity.setRightClickCallback((player, entity) -> {
                modeledEntity.getInteractionComponent().callRightClickEvent(player);
            });

            // Set up left-click callback
            packetInteractionEntity.setLeftClickCallback((player, entity) -> {
                modeledEntity.getInteractionComponent().callLeftClickEvent(player);
            });

            // Show to all current viewers
            for (UUID viewerUUID : modeledEntity.getViewers()) {
                Player viewer = Bukkit.getPlayer(viewerUUID);
                PacketEntityDisplayHelper.displayToPlayer(packetInteractionEntity, viewer);
            }
        } catch (UnsupportedOperationException e) {
            // This version doesn't support packet interaction entities
            // Fall back to OBB-based detection only
        }
    }

    /**
     * Updates the packet interaction entity's position.
     * Should be called during tick.
     */
    private Location lastInteractionLocation = null;

    private void updatePacketInteractionEntityPosition(com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle packetBundle) {
        // Snapshot the field: tick() runs async, removePacketInteractionEntity()
        // on the main thread can null this between the check and the teleport.
        PacketInteractionEntity entity = packetInteractionEntity;
        if (entity == null) return;
        Location location = modeledEntity.getLocation();
        if (location == null) return;

        // Change detection: this used to teleport the interaction entity EVERY tick, unbundled,
        // even for a perfectly still model — a hidden per-model packet per tick that bypassed the
        // bundler. Skip when the position/rotation hasn't changed (gated by the same toggle as the
        // bone dirty-check so it can be A/B compared / rolled back).
        if (DefaultConfig.skipUnchangedBoneUpdates
                && lastInteractionLocation != null
                && lastInteractionLocation.getWorld() == location.getWorld()
                && lastInteractionLocation.distanceSquared(location) < 1.0E-8
                && lastInteractionLocation.getYaw() == location.getYaw()
                && lastInteractionLocation.getPitch() == location.getPitch()) {
            return;
        }

        // Bundled teleport (rides the clock bundle instead of a direct unbundled send). Falls back
        // to a direct send when packetBundle is null (non-tick callers).
        entity.teleport(location, packetBundle);
        lastInteractionLocation = location.clone();
    }

    /**
     * Shows the packet interaction entity to a player.
     * Should be called when a player starts viewing the model.
     */
    public void showPacketInteractionEntityTo(Player player) {
        showPacketInteractionEntityTo(player, null);
    }

    public void showPacketInteractionEntityTo(Player player, com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle packetBundle) {
        if (packetInteractionEntity == null) return;
        PacketEntityDisplayHelper.displayToPlayer(packetInteractionEntity, player, packetBundle);
    }

    /**
     * Hides the packet interaction entity from a player.
     * Should be called when a player stops viewing the model.
     * Accepts UUID to work even when player is offline.
     */
    public void hidePacketInteractionEntityFrom(UUID uuid) {
        if (packetInteractionEntity == null) return;
        packetInteractionEntity.hideFrom(uuid);
    }

    /**
     * Removes the packet interaction entity.
     * Should be called when the model is removed.
     */
    public void removePacketInteractionEntity() {
        if (packetInteractionEntity == null) return;
        packetInteractionEntity.remove();
        packetInteractionEntity = null;
    }
}
