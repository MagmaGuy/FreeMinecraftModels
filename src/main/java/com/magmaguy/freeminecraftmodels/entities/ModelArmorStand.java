package com.magmaguy.freeminecraftmodels.entities;

import com.magmaguy.freeminecraftmodels.dataconverter.Bone;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.Vector;

public class ModelArmorStand {
    private ModelArmorStand() {
    }

    public static ArmorStand generate(Location location, Bone bone) {
        location.setDirection(new Vector(0, 0, -1));
        return (ArmorStand) location.getWorld().spawn(location, EntityType.ARMOR_STAND.getEntityClass(),
                entity -> applyFeatures((ArmorStand) entity, bone));
    }

    private static void applyFeatures(ArmorStand armorStand,  Bone bone) {
        armorStand.setGravity(false);
        armorStand.setMarker(true);
        armorStand.setPersistent(false);
        armorStand.setVisible(false);
        //This should only really be true for name tags and maybe other utility bones later on
        if (bone.getCubeChildren().isEmpty()) return;
        ItemStack leatherHorseArmor = new ItemStack(Material.LEATHER_HORSE_ARMOR);
        LeatherArmorMeta itemMeta = (LeatherArmorMeta) leatherHorseArmor.getItemMeta();
        itemMeta.setColor(Color.WHITE);
        Developer.debug("attempting model data");
        if (bone.getModelID() != null) {
            itemMeta.setCustomModelData(bone.getModelID());
            Developer.debug("assigning model data");
        }
        leatherHorseArmor.setItemMeta(itemMeta);
        armorStand.setHelmet(leatherHorseArmor);
    }
}
