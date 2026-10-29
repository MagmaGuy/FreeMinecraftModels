package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Wild pets waiting to come back, by world and chunk. A pet body is never saved with the world, since without
 * FreeMinecraftModels it would be a bare mob. So a wild pet whose chunk unloads, or whose body is lost, waits here as an
 * entry (species, colour, place, home, health, a baby's birth time and its stats), and comes back near players once its
 * chunk is loaded: wild pets stay where they live, as vanilla's farm animals do. The manager keeps each chunk's entries
 * in that chunk's persistent data, which Minecraft saves with the chunk ({@link #encodeAll}, {@link #decodeAll});
 * this store only holds them in memory. The older data/wild_pets.yml is read once ({@link #readLegacy}).
 */
final class WildPetStore {
    /**
     * One waiting pet. Its chunk is the one holding its place. health 0 means full; bornAt (epoch milliseconds) 0 an
     * adult or a baby from before births were kept; stats 0 a pet from before stats were kept, which rolls on its way
     * back. Those are what older entries read as.
     */
    record Entry(String species, String model, boolean baby, double x, double y, double z, double homeX, double homeY,
                 double homeZ, double health, long bornAt, double statHealth, double statSpeed, double statSting,
                 double statPoison) {
        Entry(String species, String model, boolean baby, double x, double y, double z, double homeX, double homeY,
              double homeZ) {
            this(species, model, baby, x, y, z, homeX, homeY, homeZ, 0D, 0L, 0D, 0D, 0D, 0D);
        }

        Entry(String species, String model, boolean baby, double x, double y, double z, double homeX, double homeY,
              double homeZ, double health, long bornAt, PetStats stats) {
            this(species, model, baby, x, y, z, homeX, homeY, homeZ, health, bornAt, stats == null ? 0D : stats.health,
                    stats == null ? 0D : stats.speed, stats == null ? 0D : stats.sting, stats == null ? 0D : stats.poison);
        }

        Location at(World world) {
            return new Location(world, x, y, z);
        }

        Location home(World world) {
            return new Location(world, homeX, homeY, homeZ);
        }

        /** Its stats, or null for a pet from before they were kept. */
        PetStats stats() {
            return statHealth > 0D ? new PetStats(statHealth, statSpeed, statSting, statPoison) : null;
        }

        String encode() {
            return String.join(";", species, model, String.valueOf(baby), number(x), number(y), number(z), number(homeX),
                    number(homeY), number(homeZ), number(health), String.valueOf(bornAt), factor(statHealth),
                    factor(statSpeed), factor(statSting), factor(statPoison));
        }

        /** The entry a line holds, or null when the line is not one. Lines of 9 and 11 fields are older entries. */
        static Entry decode(String line) {
            String[] parts = line.split(";", -1);
            int length = parts.length;
            if ((length != 9 && length != 11 && length != 15) || parts[0].isEmpty() || parts[1].isEmpty()) return null;
            try {
                return new Entry(parts[0], parts[1], Boolean.parseBoolean(parts[2]), Double.parseDouble(parts[3]),
                        Double.parseDouble(parts[4]), Double.parseDouble(parts[5]), Double.parseDouble(parts[6]),
                        Double.parseDouble(parts[7]), Double.parseDouble(parts[8]),
                        length >= 11 ? Double.parseDouble(parts[9]) : 0D, length >= 11 ? Long.parseLong(parts[10]) : 0L,
                        length == 15 ? Double.parseDouble(parts[11]) : 0D, length == 15 ? Double.parseDouble(parts[12]) : 0D,
                        length == 15 ? Double.parseDouble(parts[13]) : 0D, length == 15 ? Double.parseDouble(parts[14]) : 0D);
            } catch (NumberFormatException malformed) {
                return null;
            }
        }

        private static String number(double value) {
            return String.format(Locale.ROOT, "%.2f", value);
        }

        private static String factor(double value) {
            return String.format(Locale.ROOT, "%.4f", value);
        }
    }

    private final Map<UUID, Map<Long, List<Entry>>> byWorld = new HashMap<>();

    /** Keeps a pet until its chunk loads and a player comes near. */
    void put(UUID world, Entry entry) {
        long chunk = WildSpawner.chunkKey((int) Math.floor(entry.x()) >> 4, (int) Math.floor(entry.z()) >> 4);
        byWorld.computeIfAbsent(world, ignored -> new HashMap<>()).computeIfAbsent(chunk, ignored -> new ArrayList<>()).add(entry);
    }

    /** Hands over the pets waiting in a chunk; they leave the store. */
    List<Entry> take(UUID world, int cx, int cz) {
        Map<Long, List<Entry>> chunks = byWorld.get(world);
        List<Entry> entries = chunks == null ? null : chunks.remove(WildSpawner.chunkKey(cx, cz));
        return entries == null ? List.of() : entries;
    }

    /** The pets waiting in a chunk, which stay in the store. */
    List<Entry> peek(UUID world, int cx, int cz) {
        Map<Long, List<Entry>> chunks = byWorld.get(world);
        List<Entry> entries = chunks == null ? null : chunks.get(WildSpawner.chunkKey(cx, cz));
        return entries == null ? List.of() : List.copyOf(entries);
    }

    /** Whether pets wait in this chunk. */
    boolean has(UUID world, int cx, int cz) {
        Map<Long, List<Entry>> chunks = byWorld.get(world);
        return chunks != null && chunks.containsKey(WildSpawner.chunkKey(cx, cz));
    }

    /** The worlds with pets waiting. */
    Set<UUID> worlds() {
        return Set.copyOf(byWorld.keySet());
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

    /** A chunk's entries as its persistent data keeps them: one line each. */
    static String encodeAll(List<Entry> entries) {
        return String.join("\n", entries.stream().map(Entry::encode).toList());
    }

    /** The entries a chunk's persistent data holds; lines that are not entries are skipped. */
    static List<Entry> decodeAll(String stored) {
        List<Entry> entries = new ArrayList<>();
        if (stored == null || stored.isBlank()) return entries;
        for (String line : stored.split("\n")) {
            Entry entry = Entry.decode(line);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    /**
     * The entries the older data/wild_pets.yml holds, by world, or none when there is no file. A file that cannot be
     * read is reported and left alone.
     */
    static Map<UUID, List<Entry>> readLegacy(File file) {
        Map<UUID, List<Entry>> byWorld = new HashMap<>();
        if (file == null || !file.isFile()) return byWorld;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException failure) {
            Logger.warn("Could not read the wild pets in " + file + "; they stay in the file: " + failure.getMessage());
            return byWorld;
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
                    if (entry != null) byWorld.computeIfAbsent(world, ignored -> new ArrayList<>()).add(entry);
                }
        }
        return byWorld;
    }
}
