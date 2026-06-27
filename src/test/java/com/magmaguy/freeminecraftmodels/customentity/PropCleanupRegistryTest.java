package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.File;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropCleanupRegistryTest extends MockBukkitTestSupport {

    @AfterEach
    void shutDownRegistry() {
        PropCleanupRegistry.shutdown();
        PropEntity.shutdown();
    }

    @Test
    void registerPersistsPropEntityChunkLocation() {
        WorldMock world = server.addSimpleWorld("props_world");
        ArmorStand armorStand = world.spawn(new Location(world, 33.5, 64, -17.5), ArmorStand.class);

        PropCleanupRegistry.initialize();
        PropCleanupRegistry.register(armorStand);

        File propsFile = new File(plugin.getDataFolder(), "props.yml");
        YamlConfiguration props = YamlConfiguration.loadConfiguration(propsFile);
        String path = "props." + armorStand.getUniqueId();

        assertEquals("props_world", props.getString(path + ".world"));
        assertEquals(2, props.getInt(path + ".chunkX"));
        assertEquals(-2, props.getInt(path + ".chunkZ"));
    }

    @Test
    void clearRegisteredPropsRemovesTrackedEntityAndClearsRecord() {
        WorldMock world = server.addSimpleWorld("cleanup_world");
        ArmorStand armorStand = world.spawn(new Location(world, 1, 64, 1), ArmorStand.class);

        PropCleanupRegistry.initialize();
        PropCleanupRegistry.register(armorStand);

        assertEquals(1, PropCleanupRegistry.clearRegisteredProps(entity -> true));

        File propsFile = new File(plugin.getDataFolder(), "props.yml");
        YamlConfiguration props = YamlConfiguration.loadConfiguration(propsFile);
        assertFalse(props.contains("props." + armorStand.getUniqueId()));
        assertTrue(armorStand.isDead());
    }

    @Test
    void clearLoadedUnregisteredPropsOnlyRemovesMarkedPropArmorStands() {
        WorldMock world = server.addSimpleWorld("loaded_cleanup_world");
        NamespacedKey propKey = PropEntity.propNamespacedKey;
        ArmorStand prop = world.spawn(new Location(world, 1, 64, 1), ArmorStand.class);
        ArmorStand excludedProp = world.spawn(new Location(world, 2, 64, 2), ArmorStand.class);
        ArmorStand normalArmorStand = world.spawn(new Location(world, 3, 64, 3), ArmorStand.class);
        world.loadChunk(0, 0);
        prop.getPersistentDataContainer().set(propKey, PersistentDataType.STRING, "chair");
        excludedProp.getPersistentDataContainer().set(propKey, PersistentDataType.STRING, "table");

        PropCleanupRegistry.initialize();

        assertEquals(1, PropCleanupRegistry.clearLoadedUnregisteredProps(Set.of(excludedProp.getUniqueId())));
        assertTrue(prop.isDead());
        assertFalse(excludedProp.isDead());
        assertFalse(normalArmorStand.isDead());
    }
}
