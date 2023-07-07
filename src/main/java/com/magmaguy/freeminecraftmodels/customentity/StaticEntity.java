package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.dataconverter.Bone;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.Skeleton;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

public class StaticEntity {
    @Getter
    private static final List<StaticEntity> staticEntities = new ArrayList<>();
    static
    @Getter
    private Skeleton skeleton = null;
    @Getter
    private final String entityID;
    private final ArrayList<ArmorStand> armorStandList = new ArrayList<>();
    @Getter
    private final String name = "default";

    private StaticEntity(String entityID, Location targetLocation) {
        this.entityID = entityID;
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return;
        skeleton = fileModelConverter.getSkeleton();
        skeleton.getMainModel().forEach(bone -> armorStandInitializer(targetLocation, bone));
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
        Developer.warn("ID " + entityID + " was null: " + (fileModelConverter == null));
        if (fileModelConverter == null) return null;
        return new StaticEntity(entityID, targetLocation);
    }

    private static boolean isNameTag(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(Bone.nameTagKey, PersistentDataType.BYTE);
    }

    private void armorStandInitializer(Location targetLocation, Bone bone) {
        if (!bone.getCubeChildren().isEmpty() || bone.isNameTag()) {
            ArmorStand armorStand = bone.generateDisplay(targetLocation);
            armorStandList.add(armorStand);
            if (bone.isNameTag()) armorStand.setMarker(false);
        }
        if (!bone.getBoneChildren().isEmpty())
            bone.getBoneChildren().forEach(boneChild -> armorStandInitializer(targetLocation, boneChild));
    }

    public void remove() {
        for (ArmorStand armorStand : armorStandList) armorStand.remove();
        armorStandList.clear();
    }

    /**
     * Sets the custom name that is visible in-game on the entity
     *
     * @param name Name to set
     */
    public void setName(String name) {
        armorStandList.forEach(armorStand -> {
            if (isNameTag(armorStand))
                armorStand.setCustomName(name);

        });
    }

    /**
     * Default is false
     *
     * @param display Sets whether the name is visible
     */
    public void setNameVisible(boolean display) {
        armorStandList.forEach(armorStand -> {
            if (isNameTag(armorStand))
                armorStand.setCustomNameVisible(display);
        });
    }

    /**
     * Returns the name tag locations. Useful if you want to add more text above or below them.
     * Not currently guaranteed to be the exact location.
     *
     * @return
     */
    public List<Location> getNameTagLocations() {
        List<Location> locations = new ArrayList<>();
        armorStandList.forEach(armorStand -> {
            if (isNameTag(armorStand)) locations.add(armorStand.getLocation().add(new Vector(0, 1.9875, 0)));
        });
        return locations;
    }
}
