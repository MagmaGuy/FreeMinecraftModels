package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.checkerframework.checker.nullness.qual.Nullable;

public class StaticEntity extends ModeledEntity {
    protected StaticEntity(String entityID, Location targetLocation) {
        super(entityID, targetLocation);
        setLeftClickCallback((player, entity) -> entity.damage(player, 1));
    }

    @Nullable
    public static StaticEntity create(String entityID, Location targetLocation) {
        FileModelConverter fileModelConverter = FileModelConverter.getModel(entityID);
        if (fileModelConverter == null) return null;
        StaticEntity staticEntity = new StaticEntity(entityID, targetLocation);
        staticEntity.spawn();
        return staticEntity;
    }
}
