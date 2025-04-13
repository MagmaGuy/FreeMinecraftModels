package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.*;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DynamicEntity extends ModeledEntity implements ModeledEntityInterface {
    @Getter
    private static final List<DynamicEntity> dynamicEntities = new ArrayList<>();
    @Getter
    private final String name = "default";
    private BukkitTask skeletonSync = null;

    // Contact damage properties
    @Getter
    @Setter
    private boolean damagesOnContact = true;

    // Contact damage detection is integrated into the entity's internal clock

    private static NamespacedKey namespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "DynamicEntity");

    public static boolean isDynamicEntity(LivingEntity livingEntity) {
        if (livingEntity == null) return false;
        return livingEntity.getPersistentDataContainer().has(namespacedKey, PersistentDataType.BYTE);
    }

    public static DynamicEntity getDynamicEntity(LivingEntity livingEntity) {
        for (DynamicEntity dynamicEntity : dynamicEntities)
            if (dynamicEntity.getLivingEntity().equals(livingEntity))
                return dynamicEntity;
        return null;
    }

    //Coming soon
    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        dynamicEntities.add(this);
        super.getSkeleton().setDynamicEntity(this);
    }

    public static void shutdown() {
        dynamicEntities.forEach(DynamicEntity::remove);
        dynamicEntities.clear();
    }

    //safer since it can return null
    @Nullable
    public static DynamicEntity create(String entityID, LivingEntity livingEntity) {
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return null;
        DynamicEntity dynamicEntity = new DynamicEntity(entityID, livingEntity.getLocation());
        dynamicEntity.spawn(livingEntity);
        livingEntity.setVisibleByDefault(false);
        Bukkit.getOnlinePlayers().forEach(player -> {
            if (player.getLocation().getWorld().equals(dynamicEntity.getLocation().getWorld())) {
                player.hideEntity(MetadataHandler.PLUGIN, livingEntity);
            }
        });

        livingEntity.getPersistentDataContainer().set(namespacedKey, PersistentDataType.BYTE, (byte) 0);
        return dynamicEntity;
    }

    public void spawn(LivingEntity entity) {
        super.livingEntity = entity;
        RegisterModelEntity.registerModelEntity(entity, getSkeletonBlueprint().getModelName());
        super.spawn();
        syncSkeletonWithEntity();
        setHitbox();
    }

    private void syncSkeletonWithEntity() {
        skeletonSync = new BukkitRunnable() {
            // Counter to throttle collision checks for performance
            private int collisionCheckCounter = 0;

            @Override
            public void run() {
                if (livingEntity == null || !livingEntity.isValid()) {
                    remove();
                    cancel();
                    return;
                }

                // Update skeleton position and rotation
                Location entityLocation = livingEntity.getLocation();
                entityLocation.setYaw(NMSManager.getAdapter().getBodyRotation(livingEntity));
                getSkeleton().setCurrentLocation(entityLocation);
                getSkeleton().setCurrentHeadPitch(livingEntity.getEyeLocation().getPitch());
                getSkeleton().setCurrentHeadYaw(livingEntity.getEyeLocation().getYaw());

                // Handle contact damage as part of the entity's internal clock
                if (damagesOnContact) {
                    // Check collision every other tick for performance (still very responsive)
                    collisionCheckCounter++;
                    if (collisionCheckCounter >= 2) {
                        collisionCheckCounter = 0;
                        checkPlayerCollisions();
                    }
                }
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    @Override
    public void remove() {
        super.remove();
        if (livingEntity != null)
            livingEntity.remove();
        if (skeletonSync != null) skeletonSync.cancel();
    }

    private void setHitbox() {
        if (getSkeletonBlueprint().getHitbox() == null) return;
        NMSManager.getAdapter().setCustomHitbox(super.livingEntity, (float) getSkeletonBlueprint().getHitbox().getWidth(), (float) getSkeletonBlueprint().getHitbox().getHeight(), true);
    }

    @Override
    public BoundingBox getHitbox() {
        if (livingEntity == null) return null;
        return livingEntity.getBoundingBox();
    }

    @Override
    public void damage(Player player, double damage) {
        if (livingEntity == null) return;
        OBBHitDetection.applyDamage = true;
        player.attack(livingEntity);
        getSkeleton().tint();
    }

    @Override
    public void damage(Player player) {
        if (livingEntity == null) return;
        Logger.debug("damaged");
        player.attack(livingEntity);
        getSkeleton().tint();
    }

    @Override
    public World getWorld() {
        if (livingEntity == null || !livingEntity.isValid()) return null;
        return livingEntity.getWorld();
    }

    @Override
    public Location getLocation() {
        if (livingEntity == null) return null;
        return livingEntity.getLocation();
    }

    /**
     * Checks for collisions with nearby players and applies damage
     */
    private void checkPlayerCollisions() {
        if (livingEntity == null || !livingEntity.isValid()) {
            return;
        }

        // Check for nearby players (within 5 blocks)
        List<Player> nearbyPlayers = livingEntity.getWorld().getPlayers().stream()
                .filter(player -> player.getLocation().distanceSquared(livingEntity.getLocation()) < 25)
                .collect(Collectors.toList());

        // For each nearby player, check collision and apply damage if colliding
        for (Player player : nearbyPlayers) {
            if (isPlayerColliding(player)) {
                livingEntity.attack(player);
            }
        }
    }

    /**
     * Checks if a player is colliding with this entity
     */
    private boolean isPlayerColliding(Player player) {
        // Get fresh OBB for entity
        OrientedBoundingBox entityOBB = ModeledEntityOBBExtension.getOBB(this);

        // Get player's bounding box
        BoundingBox playerBB = player.getBoundingBox();

        // Check for collision
        return isAABBCollidingWithOBB(playerBB, entityOBB);
    }

    /**
     * Checks if an AABB is colliding with an OBB
     */
    private boolean isAABBCollidingWithOBB(BoundingBox aabb, OrientedBoundingBox obb) {
        // Get AABB center and half-extents
        Vector3f aabbCenter = new Vector3f(
                (float) ((aabb.getMinX() + aabb.getMaxX()) / 2),
                (float) ((aabb.getMinY() + aabb.getMaxY()) / 2),
                (float) ((aabb.getMinZ() + aabb.getMaxZ()) / 2)
        );

        Vector3f aabbHalfExtents = new Vector3f(
                (float) ((aabb.getMaxX() - aabb.getMinX()) / 2),
                (float) ((aabb.getMaxY() - aabb.getMinY()) / 2),
                (float) ((aabb.getMaxZ() - aabb.getMinZ()) / 2)
        );

        // Get OBB center and half-extents
        Vector3f obbCenter = obb.getCenter();
        Vector3f obbHalfExtents = obb.getHalfExtents();

        // Calculate distance between centers
        float distX = Math.abs(obbCenter.x - aabbCenter.x);
        float distY = Math.abs(obbCenter.y - aabbCenter.y);
        float distZ = Math.abs(obbCenter.z - aabbCenter.z);

        // Check if distances are less than sum of half-extents
        // This is a simplified collision check that works well for most cases
        return distX < (obbHalfExtents.x + aabbHalfExtents.x) &&
                distY < (obbHalfExtents.y + aabbHalfExtents.y) &&
                distZ < (obbHalfExtents.z + aabbHalfExtents.z);
    }

}