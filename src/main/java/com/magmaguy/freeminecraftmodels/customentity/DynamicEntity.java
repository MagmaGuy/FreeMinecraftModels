package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.*;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.*;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.BoundingBox;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class DynamicEntity extends ModeledEntity implements ModeledEntityInterface {
    @Getter
    private static final List<DynamicEntity> dynamicEntities = new ArrayList<>();
    private static final NamespacedKey namespacedKey = new NamespacedKey(MetadataHandler.PLUGIN, "DynamicEntity");
    @Getter
    private final String name = "default";
    boolean oneTimeDamageWarning = false;
    // Contact damage detection is integrated into the entity's internal clock
    // Contact damage properties
    @Getter
    @Setter
    private boolean damagesOnContact = true;
    @Getter
    private int customDamage = 1;

    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        dynamicEntities.add(this);
        super.getSkeleton().setDynamicEntity(this);
    }

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

    /**
     * This value only gets used if damagesOnContact is set to true and the entity doesn ot have an attack damage attribute
     *
     * @param customDamage
     */
    public void setCustomDamage(int customDamage) {
        this.customDamage = customDamage;
    }

    public void spawn(LivingEntity entity) {
        super.livingEntity = entity;
        RegisterModelEntity.registerModelEntity(entity, getSkeletonBlueprint().getModelName());
        super.spawn();
        syncSkeletonWithEntity();
        setHitbox();
        ItemDisplay display = getLocation().getWorld().spawn(getLocation(), ItemDisplay.class);
        display.setItemStack(new ItemStack(Material.CACTUS));
    }

    @Override
    public void tick() {
        super.tick();
        syncSkeletonWithEntity();
    }

    private void syncSkeletonWithEntity() {
        if (livingEntity == null || !livingEntity.isValid()) {
            remove();
            return;
        }

        // Update skeleton position and rotation
        Location entityLocation = livingEntity.getLocation();
        entityLocation.setYaw(NMSManager.getAdapter().getBodyRotation(livingEntity));
        getSkeleton().setCurrentLocation(entityLocation);
        getSkeleton().setCurrentHeadPitch(livingEntity.getEyeLocation().getPitch());
        getSkeleton().setCurrentHeadYaw(livingEntity.getEyeLocation().getYaw());

        //todo: might want to run every other tick for performance
        if (damagesOnContact) checkPlayerCollisions();
    }

    @Override
    public void remove() {
        super.remove();
        if (livingEntity != null)
            livingEntity.remove();
    }

    private void setHitbox() {
        if (getSkeletonBlueprint().getHitbox() == null) return;
        NMSManager.getAdapter().setCustomHitbox(super.livingEntity, getSkeletonBlueprint().getHitbox().getWidthX() < getSkeletonBlueprint().getHitbox().getWidthZ() ? (float) getSkeletonBlueprint().getHitbox().getWidthX() : (float) getSkeletonBlueprint().getHitbox().getWidthZ(), (float) getSkeletonBlueprint().getHitbox().getHeight(), true);
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
        livingEntity.damage(damage, player);
        OBBHitDetection.applyDamage = false;
        getSkeleton().tint();
        if (!livingEntity.isValid()) remove();
    }

    @Override
    public void damage(Player player) {
        if (livingEntity == null) return;
        OBBHitDetection.applyDamage = true;
        player.attack(livingEntity);
        OBBHitDetection.applyDamage = false;
        getSkeleton().tint();
        if (!livingEntity.isValid()) remove();
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
                if (livingEntity.getAttribute(AttributeManager.getAttribute("generic_attack_damage")) != null)
                    livingEntity.attack(player);
                else {
                    //This is not ideal, the underlying entity should have a damage attribute
                    player.damage(customDamage, livingEntity);
                    if (!oneTimeDamageWarning) {
                        Logger.info("Damaged player " + player.getName() + " for " + customDamage + " damage using custom damage value!");
                        oneTimeDamageWarning = true;
                    }
                }
            }
        }
    }

    /**
     * Checks if a player is colliding with this entity
     */
    private boolean isPlayerColliding(Player player) {
        // Get fresh OBB for entity
        OrientedBoundingBox entityOBB = OrientedBoundingBoxRayTracer.createOBB(this);

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