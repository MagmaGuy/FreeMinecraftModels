package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.customentity.core.ModeledEntityInterface;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

public class StaticEntity extends ModeledEntity implements ModeledEntityInterface {
    @Getter
    private static final List<StaticEntity> staticEntities = new ArrayList<>();
    @Getter
    private final String name = "default";
    @Getter
    private final double health = -1;
    @Getter
    private BoundingBox hitbox = null;

    protected StaticEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        staticEntities.add(this);
    }

    public static void shutdown() {
        staticEntities.forEach(StaticEntity::remove);
        staticEntities.clear();
    }

    //safer since it can return null
    @Nullable
    public static StaticEntity create(String entityID, Location targetLocation) {
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return null;
        StaticEntity staticEntity = new StaticEntity(entityID, targetLocation);
        staticEntity.spawn();
        return staticEntity;
    }

    @Override
    public void spawn() {
        super.spawn();
        if (getSkeletonBlueprint().getHitbox() != null) {
            double halfWidthX = getSkeletonBlueprint().getHitbox().getWidthX() / 2D;
            double halfWidthZ = getSkeletonBlueprint().getHitbox().getWidthZ() / 2D;
            double height = getSkeletonBlueprint().getHitbox().getHeight();
            Vector modelOffset = getSkeletonBlueprint().getHitbox().getModelOffset();
            Location hitboxLocation = getSpawnLocation().add(modelOffset);
            hitbox = new BoundingBox(hitboxLocation.getX() - halfWidthX, hitboxLocation.getY(), hitboxLocation.getZ() - halfWidthZ,
                    hitboxLocation.getX() + halfWidthX, hitboxLocation.getY() + height, hitboxLocation.getZ() + halfWidthZ);
        }
    }

    @Override
    public void damage(Player player, double damage) {
        //If the health is -1, then the entity is not meant to be damageable.
        if (health == -1) return;
        else remove();
    }

    @Override
    public void damage(Player player) {
        //If the health is -1, then the entity is not meant to be damageable.
        if (health == -1) return;
        else remove();
    }

    @Override
    public World getWorld() {
        Location spawnLocation = getSpawnLocation();
        if (spawnLocation == null) return null;
        return spawnLocation.getWorld();
    }

    @Override
    public void tick() {
        super.tick();
        syncSkeletonWithEntity();
    }

    private void syncSkeletonWithEntity() {
        //todo: maybe implement this in the future
//        if (damagesOnContact) checkPlayerCollisions();
    }
}
