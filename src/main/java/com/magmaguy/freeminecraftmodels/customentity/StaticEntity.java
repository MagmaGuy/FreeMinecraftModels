package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.dataconverter.Bone;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.Skeleton;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import lombok.Getter;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
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

    private void armorStandInitializer(Location targetLocation, Bone bone) {
        if (!bone.getCubeChildren().isEmpty()) {
            ArmorStand armorStand = (ArmorStand) targetLocation.getWorld().spawnEntity(targetLocation, EntityType.ARMOR_STAND);
            armorStand.setGravity(false);
            armorStand.setMarker(true);
            armorStand.teleport(targetLocation.clone().add(0.5, 0, 0.5));
            armorStand.setPersistent(false);
            ItemStack leatherHorseArmor = new ItemStack(Material.LEATHER_HORSE_ARMOR);
            LeatherArmorMeta itemMeta = (LeatherArmorMeta) leatherHorseArmor.getItemMeta();
            itemMeta.setColor(Color.WHITE);
            itemMeta.setCustomModelData(bone.getModelID());
            leatherHorseArmor.setItemMeta(itemMeta);
            armorStand.setHelmet(leatherHorseArmor);
            armorStandList.add(armorStand);
        }
        if (!bone.getBoneChildren().isEmpty())
            bone.getBoneChildren().forEach(boneChild -> armorStandInitializer(targetLocation, boneChild));
    }

    public void remove() {
        for (ArmorStand armorStand : armorStandList) armorStand.remove();
    }
}
