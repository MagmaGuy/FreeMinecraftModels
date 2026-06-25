package com.magmaguy.freeminecraftmodels.entities;

import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.RegisterModelEntity;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

public class ModelArmorStand {
    private ModelArmorStand() {
    }

    public static ArmorStand generate(Location location, Bone bone) {
        return (ArmorStand) location.getWorld().spawn(location, EntityType.ARMOR_STAND.getEntityClass(),
                entity -> applyFeatures((ArmorStand) entity, bone));
    }

    private static void applyFeatures(ArmorStand armorStand, Bone bone) {
        armorStand.setGravity(false);
        armorStand.setMarker(true);
        armorStand.setPersistent(false);
        armorStand.setVisible(false);
        //This should only really be true for name tags and maybe other utility bones later on
        if (bone.getBoneBlueprint().getCubeBlueprintChildren().isEmpty() ||
                !bone.getBoneBlueprint().isDisplayModel()) {
            RegisterModelEntity.registerModelArmorStand(armorStand, bone.getBoneBlueprint().getBoneName());
            return;
        }
        ItemStack leatherHorseArmor = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        LeatherArmorMeta itemMeta = (LeatherArmorMeta) leatherHorseArmor.getItemMeta();
        itemMeta.setColor(Color.WHITE);
        if (bone.getBoneBlueprint().getModelID() != null)
            if (VersionChecker.serverVersionOlderThan(21, 4)) {
                itemMeta.setCustomModelData(Integer.valueOf(bone.getBoneBlueprint().getModelID()));
            } else {
                itemMeta.setItemModel(NamespacedKey.fromString(bone.getBoneBlueprint().getModelID()));
            }

        leatherHorseArmor.setItemMeta(itemMeta);
        armorStand.setHelmet(leatherHorseArmor);
        RegisterModelEntity.registerModelArmorStand(armorStand, bone.getBoneBlueprint().getBoneName());
    }
}
