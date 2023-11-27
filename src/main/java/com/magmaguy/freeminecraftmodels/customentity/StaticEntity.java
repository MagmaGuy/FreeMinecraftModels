package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.customentity.core.ModeledEntityInterface;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.utils.ChunkHasher;
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

    private StaticEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        staticEntities.add(this);
        chunkHash = ChunkHasher.hash(targetLocation);
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
            double halfWidth = getSkeletonBlueprint().getHitbox().getWidth() / 2D;
            double height = getSkeletonBlueprint().getHitbox().getHeight();
            Vector modelOffset = getSkeletonBlueprint().getHitbox().getModelOffset();
            Location hitboxLocation = getSpawnLocation().add(modelOffset);
            hitbox = new BoundingBox(hitboxLocation.getX() - halfWidth, hitboxLocation.getY(), hitboxLocation.getZ() - halfWidth,
                    hitboxLocation.getX() + halfWidth, hitboxLocation.getY() + height, hitboxLocation.getZ() + halfWidth);
        }
    }

    @Override
    public void damage(Player player, double damage) {
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
}
