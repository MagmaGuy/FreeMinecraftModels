package com.magmaguy.freeminecraftmodels.utils;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class ConfigurationLocationTest extends MockBukkitTestSupport {

    @AfterEach
    void resetLocationWarnings() {
        ConfigurationLocation.shutdown();
    }

    @Test
    void serializeReadsLoadedWorldCoordinatesAndRotation() {
        WorldMock world = server.addSimpleWorld("location_world");

        Location location = ConfigurationLocation.serialize("location_world,1.25,64.5,-3.75,90,45");

        assertSame(world, location.getWorld());
        assertEquals(1.25, location.getX());
        assertEquals(64.5, location.getY());
        assertEquals(-3.75, location.getZ());
        assertEquals(90.0f, location.getYaw());
        assertEquals(45.0f, location.getPitch());
    }

    @Test
    void serializeAllowsCoordinateOnlyLocationsWithoutAWorld() {
        Location location = ConfigurationLocation.serialize("1,2,3,180,15");

        assertNull(location.getWorld());
        assertEquals(1.0, location.getX());
        assertEquals(2.0, location.getY());
        assertEquals(3.0, location.getZ());
        assertEquals(180.0f, location.getYaw());
        assertEquals(15.0f, location.getPitch());
    }

    @Test
    void deserializeWritesConfigStringFromLocation() {
        WorldMock world = server.addSimpleWorld("roundtrip_world");
        Location location = new Location(world, 10.5, 70, -8.25, 270, 30);

        assertEquals("roundtrip_world,10.5,70.0,-8.25,30.0,270.0",
                ConfigurationLocation.deserialize(location));
    }

    @Test
    void invalidAndNullSentinelLocationsReturnNull() {
        assertNull(ConfigurationLocation.serialize(null));
        assertNull(ConfigurationLocation.serialize("null"));
    }
}
