package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.LegacyHitDetection;
import com.magmaguy.freeminecraftmodels.customentity.core.ModeledEntityInterface;
import com.magmaguy.freeminecraftmodels.customentity.core.RegisterModelEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

public class DynamicEntity extends ModeledEntity implements ModeledEntityInterface {
    @Getter
    private static final List<DynamicEntity> dynamicEntities = new ArrayList<>();
    @Getter
    private final String name = "default";
    private BukkitTask skeletonSync = null;

    //Coming soon
    public DynamicEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        dynamicEntities.add(this);
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
//        livingEntity.setVisibleByDefault(false);
        livingEntity.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0));

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
            @Override
            public void run() {
                if (livingEntity == null || !livingEntity.isValid()) {
                    remove();
                    cancel();
                    return;
                }
                getSkeleton().setCurrentLocation(livingEntity.getLocation());
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
        if (getSkeletonBlueprint().getHitbox() != null)
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
        LegacyHitDetection.setEntityDamageBypass(true);
        livingEntity.damage(damage, player);
        getSkeleton().tint();
    }

    @Override
    public World getWorld() {
        if (livingEntity == null || !livingEntity.isValid()) return null;
        return livingEntity.getWorld();
    }
}
