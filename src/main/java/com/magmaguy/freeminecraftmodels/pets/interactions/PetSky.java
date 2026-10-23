package com.magmaguy.freeminecraftmodels.pets.interactions;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/** Day, night and rain as the interactions see them. */
public final class PetSky {
    // Day by these bounds, as wild spawning counts it; dusk and dawn fall on the night side.
    private static final long DAY_END = 12300L, DAY_START = 23850L;

    private PetSky() {
    }

    /** Whether the world has daylight now. Worlds without a sun count as night. */
    public static boolean isDay(World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) return false;
        long time = world.getTime();
        return time < DAY_END || time > DAY_START;
    }

    public static boolean isNight(World world) {
        return !isDay(world);
    }

    /** The last full-time tick of the night that holds {@code fullTime}. */
    public static long nightEnd(long fullTime) {
        return Math.floorDiv(fullTime, 24000L) * 24000L + DAY_START;
    }

    /** Whether rain falls on this spot: a storm, open sky above, and a biome warm and wet enough for rain. */
    public static boolean isRainingOn(Location location) {
        World world = location.getWorld();
        if (world == null || !world.hasStorm()) return false;
        if (!underOpenSky(location)) return false;
        Block block = location.getBlock();
        return block.getTemperature() >= 0.15D && block.getHumidity() > 0D;
    }

    /** Nothing that stops rain or blocks the view between this spot and the sky. */
    public static boolean underOpenSky(Location location) {
        World world = location.getWorld();
        return world != null && world.getHighestBlockYAt(location.getBlockX(), location.getBlockZ(), HeightMap.MOTION_BLOCKING)
                <= location.getBlockY();
    }
}
