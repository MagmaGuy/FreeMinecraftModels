package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.Location;

/** Compatibility facade for the shared MagmaCore location serializer. */
public final class ConfigurationLocation {
    private ConfigurationLocation() {
    }

    public static String deserialize(String worldName, double x, double y, double z, float pitch, float yaw) {
        return com.magmaguy.magmacore.util.ConfigurationLocation.deserialize(worldName, x, y, z, pitch, yaw);
    }

    public static String deserialize(Location location) {
        return com.magmaguy.magmacore.util.ConfigurationLocation.deserialize(location);
    }

    public static Location serialize(String locationString) {
        return com.magmaguy.magmacore.util.ConfigurationLocation.serialize(locationString);
    }

    public static Location serialize(String locationString, boolean silent) {
        return com.magmaguy.magmacore.util.ConfigurationLocation.serialize(locationString, silent);
    }

    public static String worldName(String locationString) {
        return com.magmaguy.magmacore.util.ConfigurationLocation.worldName(locationString);
    }

    public static void shutdown() {
        com.magmaguy.magmacore.util.ConfigurationLocation.shutdown();
    }
}
