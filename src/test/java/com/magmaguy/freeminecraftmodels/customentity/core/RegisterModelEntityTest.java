package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegisterModelEntityTest extends MockBukkitTestSupport {

    @Test
    void registerModelArmorStandStoresModelIdInPersistentData() {
        WorldMock world = server.addSimpleWorld("models_world");
        ArmorStand armorStand = world.spawn(new Location(world, 0, 64, 0), ArmorStand.class);

        RegisterModelEntity.registerModelArmorStand(armorStand, "test_model");

        assertTrue(RegisterModelEntity.isModelEntity(armorStand));
        assertEquals("test_model", armorStand.getPersistentDataContainer()
                .get(RegisterModelEntity.ENTITY_KEY, PersistentDataType.STRING));
    }
}
