package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

public class DynamicEntity extends ModeledEntity {
    @Getter
    private static final List<DynamicEntity> staticEntities = new ArrayList<>();
    @Getter
    private final String name = "default";
    private BukkitTask skeletonSync = null;

    //Coming soon
    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
    }

    public static void shutdown() {
        staticEntities.forEach(DynamicEntity::remove);
        staticEntities.clear();
    }

    //safer since it can return null
    @Nullable
    public static DynamicEntity create(String entityID, Location targetLocation, Entity entity) {
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return null;
        DynamicEntity dynamicEntity = new DynamicEntity(entityID, targetLocation);
        dynamicEntity.spawn(entity);
        ((LivingEntity)entity).addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0));
        return dynamicEntity;
    }

    public void spawn(Entity entity) {
        super.entity = entity;
        super.spawn();
        syncSkeletonWithEntity();
    }

    private void syncSkeletonWithEntity() {
        skeletonSync = new BukkitRunnable() {
            @Override
            public void run() {
                if (entity == null || !entity.isValid()) {
                    cancel();
                    return;
                }
                getSkeleton().setCurrentLocation(entity.getLocation());
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    @Override
    public void remove() {
        super.remove();
        entity.remove();
        if (skeletonSync != null) skeletonSync.cancel();
    }

}
