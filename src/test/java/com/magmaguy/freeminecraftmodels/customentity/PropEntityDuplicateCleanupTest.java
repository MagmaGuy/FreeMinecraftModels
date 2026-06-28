package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropEntityDuplicateCleanupTest extends MockBukkitTestSupport {

    @AfterEach
    void clearProps() {
        PropEntity.shutdown();
    }

    @Test
    void duplicateCleanupKeepsOnlyOneArmorStandPerModelPerBlock() {
        WorldMock world = server.addSimpleWorld("props_world");

        ArmorStand kept = spawnPropArmorStand(world, "chair", new Location(world, 3.25, 64.0, 4.25));
        ArmorStand duplicate = spawnPropArmorStand(world, "chair", new Location(world, 3.75, 64.9, 4.75));
        ArmorStand differentModel = spawnPropArmorStand(world, "table", new Location(world, 3.5, 64.0, 4.5));
        ArmorStand differentBlock = spawnPropArmorStand(world, "chair", new Location(world, 4.25, 64.0, 4.25));

        int removed = PropEntity.removeDuplicatePropsInChunk(kept.getLocation().getChunk());

        assertEquals(1, removed);
        assertTrue(kept.isValid() ^ duplicate.isValid());
        assertTrue(differentModel.isValid());
        assertTrue(differentBlock.isValid());
    }

    @Test
    void loadedPropLookupMatchesModelAndBlock() {
        WorldMock world = server.addSimpleWorld("lookup_world");

        spawnPropArmorStand(world, "chair", new Location(world, 3.25, 64.0, 4.25));

        assertTrue(PropEntity.hasLoadedPropOnSameBlock("chair", new Location(world, 3.75, 64.9, 4.75)));
        assertFalse(PropEntity.hasLoadedPropOnSameBlock("table", new Location(world, 3.75, 64.9, 4.75)));
        assertFalse(PropEntity.hasLoadedPropOnSameBlock("chair", new Location(world, 4.25, 64.0, 4.25)));
    }

    private ArmorStand spawnPropArmorStand(WorldMock world, String modelId, Location location) {
        ArmorStand armorStand = world.spawn(location, ArmorStand.class);
        armorStand.getPersistentDataContainer().set(PropEntity.propNamespacedKey, PersistentDataType.STRING, modelId);
        return armorStand;
    }
}
