package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wild pets waiting in unloaded chunks. A pet body is never saved with the world, since without FreeMinecraftModels
 * it would be a bare mob. So when a wild pet's chunk unloads, its species, model, place, home, health and a baby's
 * birth time go here, and the
 * pet comes back when the chunk loads again: wild pets stay where they live, as vanilla's farm animals do. The store
 * is written to {@code data/wild_pets.yml} when the worlds save and on shutdown.
 */
final class WildPetStore {
    /**
     * One waiting pet. Its chunk is the one holding its place. health 0 means full, and bornAt (epoch milliseconds) 0
     * an adult or a baby from before births were kept; both are what a line from before them reads as.
     */
    record Entry(String species, String model, boolean baby, double x, double y, double z, double homeX, double homeY,
                 double homeZ, double health, long bornAt) {
        Entry(String species, String model, boolean baby, double x, double y, double z, double homeX, double homeY,
              double homeZ) {
            this(species, model, baby, x, y, z, homeX, homeY, homeZ, 0D, 0L);
        }

        Location at(World world) {
            return new Location(world, x, y, z);
        }

        Location home(World world) {
            return new Location(world, homeX, homeY, homeZ);
        }

        String encode() {
            return String.join(";", species, model, String.valueOf(baby), number(x), number(y), number(z), number(homeX),
                    number(homeY), number(homeZ), number(health), String.valueOf(bornAt));
        }

        /** The entry a line holds, or null when the line is not one. */
        static Entry decode(String line) {
            String[] parts = line.split(";", -1);
            if ((parts.length != 9 && parts.length != 11) || parts[0].isEmpty() || parts[1].isEmpty()) return null;
            try {
                return new Entry(parts[0], parts[1], Boolean.parseBoolean(parts[2]), Double.parseDouble(parts[3]),
                        Double.parseDouble(parts[4]), Double.parseDouble(parts[5]), Double.parseDouble(parts[6]),
                        Double.parseDouble(parts[7]), Double.parseDouble(parts[8]),
                        parts.length == 11 ? Double.parseDouble(parts[9]) : 0D,
                        parts.length == 11 ? Long.parseLong(parts[10]) : 0L);
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        private static String number(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }

    private final File file;
    private final Map<UUID, Map<Long, List<Entry>>> byWorld = new HashMap<>();
    private boolean dirty;

    WildPetStore(File file) {
        this.file = file;
    }

    /** The store saved in the file, or an empty one when there is none yet. */
    static WildPetStore load(File file) {
        WildPetStore store = new WildPetStore(file);
        if (!file.isFile()) return store;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException failure) {
            Logger.warn("Could not read the wild pets in " + file + "; they stay in the file: " + failure.getMessage());
            return new WildPetStore(null);
        }
        for (String worldId : yaml.getKeys(false)) {
            ConfigurationSection chunks = yaml.getConfigurationSection(worldId);
            UUID world;
            try {
                world = UUID.fromString(worldId);
            } catch (IllegalArgumentException notAWorld) {
                continue;
            }
            if (chunks == null) continue;
            for (String chunk : chunks.getKeys(false))
                for (String line : chunks.getStringList(chunk)) {
                    Entry entry = Entry.decode(line);
                    if (entry != null) store.add(world, entry);
                }
        }
        store.dirty = false;
        return store;
    }

    /** Keeps a pet until its chunk loads. */
    void put(UUID world, Entry entry) {
        add(world, entry);
        dirty = true;
    }

    private void add(UUID world, Entry entry) {
        long chunk = WildSpawner.chunkKey((int) Math.floor(entry.x()) >> 4, (int) Math.floor(entry.z()) >> 4);
        byWorld.computeIfAbsent(world, ignored -> new HashMap<>()).computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(entry);
    }

    /** Hands over the pets waiting in a chunk; they leave the store. */
    List<Entry> take(UUID world, int cx, int cz) {
        Map<Long, List<Entry>> chunks = byWorld.get(world);
        List<Entry> entries = chunks == null ? null : chunks.remove(WildSpawner.chunkKey(cx, cz));
        if (entries == null) return List.of();
        dirty = true;
        return entries;
    }

    /** Whether pets wait in this chunk. */
    boolean has(UUID world, int cx, int cz) {
        Map<Long, List<Entry>> chunks = byWorld.get(world);
        return chunks != null && chunks.containsKey(WildSpawner.chunkKey(cx, cz));
    }

    /** The chunks of a world that have pets waiting. */
    Set<Long> chunks(UUID world) {
        Map<Long, List<Entry>> chunks = byWorld.get(world);
        return chunks == null ? Set.of() : Set.copyOf(chunks.keySet());
    }

    int size() {
        int size = 0;
        for (Map<Long, List<Entry>> chunks : byWorld.values()) for (List<Entry> entries : chunks.values()) size += entries.size();
        return size;
    }

    /** Writes the store when it changed, through a temporary file so a crash mid-write keeps the last good copy. */
    void save() {
        if (!dirty || file == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Wild pets waiting in unloaded chunks, put back when their chunk loads.",
                "Written by FreeMinecraftModels; edit it only while the server is stopped."));
        for (Map.Entry<UUID, Map<Long, List<Entry>>> world : byWorld.entrySet())
            for (Map.Entry<Long, List<Entry>> chunk : world.getValue().entrySet()) {
                if (chunk.getValue().isEmpty()) continue;
                long key = chunk.getKey();
                yaml.set(world.getKey() + "." + (int) (key >> 32) + "," + (int) key, chunk.getValue().stream().map(Entry::encode).toList());
            }
        try {
            Files.createDirectories(file.getParentFile().toPath());
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            yaml.save(temporary);
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException failure) {
            Logger.warn("Could not save the wild pets to " + file + ": " + failure.getMessage());
        }
    }
}
