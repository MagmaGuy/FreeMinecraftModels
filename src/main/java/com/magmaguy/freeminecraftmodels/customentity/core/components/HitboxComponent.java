package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class HitboxComponent {
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
    public void tick(int tickCounter) {
        // Always update the OBB hitbox position, even if no blueprint hitbox is configured
        // (getObbHitbox() creates a default 1x2x1 hitbox when blueprint hitbox is null)
        getObbHitbox().update(modeledEntity.getLocation());

        // Update the packet interaction entity position
        updatePacketInteractionEntityPosition();

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

        // Check for nearby players (within 10 blocks)
        List<Player> nearbyPlayers = modeledEntity.getWorld().getPlayers().stream()
                .filter(player -> player.getLocation().distanceSquared(modeledEntity.getLocation()) < Math.pow(10, 2))
                .collect(Collectors.toList());

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
        return getObbHitbox().isAABBCollidingWithOBB(player.getBoundingBox());
    }

    public void setCustomHitboxOnUnderlyingEntity() {
        if (modeledEntity.getSkeletonBlueprint().getHitbox() == null) return;
        NMSManager.getAdapter().setCustomHitbox(modeledEntity.getUnderlyingEntity(), modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() < modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ() ? (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() : (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ(), (float) modeledEntity.getSkeletonBlueprint().getHitbox().getHeight(), true);
    }

    /**
     * Creates a packet-only Interaction entity for click detection.
     * This entity is invisible to players but receives their clicks.
     * Should be called after the model is spawned.
     * Only created for entities WITHOUT an underlying entity (props).
     * Dynamic entities with underlying entities use OBB-based detection instead.
     */
    public void createPacketInteractionEntity() {
        if (packetInteractionEntity != null) return;
        if (modeledEntity.getLocation() == null) return;
        // Don't create for entities with underlying entities - they use OBB-based detection
        if (modeledEntity.getUnderlyingEntity() != null) return;

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
                packetInteractionEntity.displayTo(viewerUUID);
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
    private void updatePacketInteractionEntityPosition() {
        if (packetInteractionEntity == null) return;
        Location location = modeledEntity.getLocation();
        if (location == null) return;
        packetInteractionEntity.teleport(location);
    }

    /**
     * Shows the packet interaction entity to a player.
     * Should be called when a player starts viewing the model.
     */
    public void showPacketInteractionEntityTo(Player player) {
        if (packetInteractionEntity == null) return;
        packetInteractionEntity.displayTo(player.getUniqueId());
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
