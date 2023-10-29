package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.Chunk;
import org.bukkit.Location;

import java.util.Objects;
import java.util.UUID;
import java.util.Vector;

public class ChunkHasher {
    public static int hash(Chunk chunk) {
        return Objects.hash(chunk.getX(), chunk.getZ(), chunk.getWorld().getUID());
    }

    //pseudo-chunks - prevent it form having to load the chunk
    public static int hash(int x, int z, UUID worldUUID) {
        return Objects.hash(x, z, worldUUID);
    }

    public static int hash(Location location) {
        return Objects.hash(location.getBlockX() >> 4, location.getBlockZ() >> 4, location.getWorld().getUID());
    }

    public static Vector hash(double x, double z) {
        Vector vector = new Vector(2);
        vector.addElement(x);
        vector.addElement(z);
        return vector;
    }

    public static boolean isSameChunk(Chunk chunk, int hashedChunk) {
        return hash(chunk) == hashedChunk;
    }
}
