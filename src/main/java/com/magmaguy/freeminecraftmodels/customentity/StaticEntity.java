package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.utils.ChunkHasher;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

public class StaticEntity extends ModeledEntity {
    @Getter
    private static final List<StaticEntity> staticEntities = new ArrayList<>();
    private final ArrayList<ArmorStand> armorStandList = new ArrayList<>();
    @Getter
    private final String name = "default";


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
        StaticEntity staticEntity =  new StaticEntity(entityID, targetLocation);
        staticEntity.spawn();
        return staticEntity;
    }

}
