package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Campfire;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * Campfires near a point, from the block entities of the loaded chunks around it. Each chunk's list is kept for the
 * caller's search interval, on the pet clock ({@link PetManager#ticks()}), and shared by every pet, so a crowd of pets
 * scans a chunk once per interval. Server thread only.
 */
public final class CampfireIndex {
    private record Entry(long expiresAt, int[] positions) {
    }

    private static final Map<World, Map<Long, Entry>> WORLDS = new WeakHashMap<>();

    private CampfireIndex() {
    }

    /** The nearest campfire within the radius the filter accepts, or null. */
    public static Block nearest(Location centre, double radius, int keepTicks, Predicate<Block> accepts) {
        World world = centre.getWorld();
        if (world == null) return null;
        long now = PetManager.ticks();
        Map<Long, Entry> chunks = WORLDS.computeIfAbsent(world, ignored -> new HashMap<>());
        double best = radius * radius;
        Block found = null;
        int minX = (int) Math.floor(centre.getX() - radius) >> 4, maxX = (int) Math.floor(centre.getX() + radius) >> 4;
        int minZ = (int) Math.floor(centre.getZ() - radius) >> 4, maxZ = (int) Math.floor(centre.getZ() + radius) >> 4;
        for (int chunkX = minX; chunkX <= maxX; chunkX++) {
            for (int chunkZ = minZ; chunkZ <= maxZ; chunkZ++) {
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;
                long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
                Entry entry = chunks.get(key);
                if (entry == null || entry.expiresAt() <= now) {
                    entry = new Entry(now + keepTicks, scan(world.getChunkAt(chunkX, chunkZ)));
                    chunks.put(key, entry);
                }
                int[] positions = entry.positions();
                for (int index = 0; index < positions.length; index += 3) {
                    double dx = positions[index] + 0.5D - centre.getX();
                    double dy = positions[index + 1] + 0.5D - centre.getY();
                    double dz = positions[index + 2] + 0.5D - centre.getZ();
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance >= best) continue;
                    Block block = world.getBlockAt(positions[index], positions[index + 1], positions[index + 2]);
                    if (!accepts.test(block)) continue;
                    best = distance;
                    found = block;
                }
            }
        }
        return found;
    }

    private static int[] scan(Chunk chunk) {
        List<Integer> positions = new ArrayList<>();
        for (BlockState state : chunk.getTileEntities()) {
            if (!(state instanceof Campfire)) continue;
            positions.add(state.getX());
            positions.add(state.getY());
            positions.add(state.getZ());
        }
        int[] packed = new int[positions.size()];
        for (int index = 0; index < packed.length; index++) packed[index] = positions.get(index);
        return packed;
    }

    static void prune(long now) {
        for (Map<Long, Entry> chunks : WORLDS.values()) chunks.values().removeIf(entry -> entry.expiresAt() <= now);
    }

    static void clear() {
        WORLDS.clear();
    }
}
