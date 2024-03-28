package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;

public class RegisterModelEntity {
    public static final NamespacedKey ARMOR_STAND_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "armor_stand");
    public static final NamespacedKey ENTITY_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "entity");

    private RegisterModelEntity() {
    }

    public static void registerModelArmorStand(ArmorStand armorStand, String name) {
        armorStand.getPersistentDataContainer().set(ENTITY_KEY, PersistentDataType.STRING, name);
    }

    public static void registerModelEntity(Entity entity, String name) {
        entity.getPersistentDataContainer().set(ENTITY_KEY, PersistentDataType.STRING, name);
    }

    public static boolean isModelArmorStand(Entity entity) {
        return entity.getPersistentDataContainer().getKeys().contains(ARMOR_STAND_KEY);
    }

    public static boolean isModelEntity(Entity entity) {
        return entity.getPersistentDataContainer().getKeys().contains(ENTITY_KEY);
    }
}
