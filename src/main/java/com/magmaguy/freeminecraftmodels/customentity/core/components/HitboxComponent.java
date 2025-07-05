package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import lombok.Getter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

public class HitboxComponent {
    private final ModeledEntity modeledEntity;
    @Getter
    private OrientedBoundingBox obbHitbox = null;

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

    public void tick(int tickCounter) {
        if (modeledEntity.getSkeletonBlueprint().getHitbox() == null) return;
        getObbHitbox().update(modeledEntity.getLocation());
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
        return getObbHitbox().isAABBCollidingWithOBB(player.getBoundingBox(), getObbHitbox());
    }

    public void setCustomHitboxOnUnderlyingEntity() {
        if (modeledEntity.getSkeletonBlueprint().getHitbox() == null) return;
        NMSManager.getAdapter().setCustomHitbox(modeledEntity.getUnderlyingEntity(), modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() < modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ() ? (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthX() : (float) modeledEntity.getSkeletonBlueprint().getHitbox().getWidthZ(), (float) modeledEntity.getSkeletonBlueprint().getHitbox().getHeight(), true);
    }
}
