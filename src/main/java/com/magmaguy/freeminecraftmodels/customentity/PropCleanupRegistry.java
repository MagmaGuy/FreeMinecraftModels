package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class PropCleanupRegistry {
    private static File file;
    private static FileConfiguration fileConfiguration;

    private PropCleanupRegistry() {
    }

    public static void initialize() {
        file = new File(MetadataHandler.PLUGIN.getDataFolder(), "props.yml");
        fileConfiguration = YamlConfiguration.loadConfiguration(file);
    }

    public static void shutdown() {
        file = null;
        fileConfiguration = null;
    }

    public static void register(PropEntity propEntity) {
        if (propEntity == null || propEntity.getUnderlyingEntity() == null) return;
        register(propEntity.getUnderlyingEntity());
    }

    public static void register(Entity entity) {
        if (entity == null || entity.getWorld() == null) return;
        ensureInitialized();
        String path = path(entity.getUniqueId());
        fileConfiguration.set(path + ".world", entity.getWorld().getName());
        fileConfiguration.set(path + ".chunkX", entity.getLocation().getBlockX() >> 4);
        fileConfiguration.set(path + ".chunkZ", entity.getLocation().getBlockZ() >> 4);
        save();
    }

    public static void unregister(UUID uuid) {
        if (uuid == null) return;
        ensureInitialized();
        fileConfiguration.set(path(uuid), null);
        save();
    }

    public static int clearRegisteredProps() {
        return clearRegisteredProps(entity -> true);
    }

    public static int clearRegisteredProps(Predicate<Entity> removalFilter) {
        ensureInitialized();
        ConfigurationSection section = fileConfiguration.getConfigurationSection("props");
        if (section == null) return 0;

        int removed = 0;
        for (String uuidString : new ArrayList<>(section.getKeys(false))) {
            UUID uuid = parseUuid(uuidString);
            if (uuid == null) continue;

            String path = path(uuid);
            World world = Bukkit.getWorld(fileConfiguration.getString(path + ".world", ""));
            if (world == null) continue;

            int chunkX = fileConfiguration.getInt(path + ".chunkX");
            int chunkZ = fileConfiguration.getInt(path + ".chunkZ");
            Chunk chunk = world.getChunkAt(chunkX, chunkZ);
            boolean foundEntity = false;
            boolean removedEntity = false;
            for (Entity entity : chunk.getEntities()) {
                if (!entity.getUniqueId().equals(uuid)) continue;
                foundEntity = true;
                if (!removalFilter.test(entity)) break;
                entity.remove();
                removedEntity = true;
                removed++;
                break;
            }
            if (!foundEntity || removedEntity) unregister(uuid);
        }
        return removed;
    }

    public static int clearLoadedUnregisteredProps() {
        return clearLoadedUnregisteredProps(Set.of());
    }

    public static int clearLoadedUnregisteredProps(Set<UUID> excludedUuids) {
        return clearLoadedUnregisteredProps(excludedUuids, armorStand -> true);
    }

    public static int clearLoadedUnregisteredProps(Set<UUID> excludedUuids, Predicate<ArmorStand> removalFilter) {
        int removed = 0;
        List<UUID> removedUuids = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            for (Chunk loadedChunk : world.getLoadedChunks()) {
                for (Entity entity : loadedChunk.getEntities()) {
                    if (!(entity instanceof ArmorStand armorStand)) continue;
                    if (!PropEntity.isPropEntity(armorStand)) continue;
                    if (excludedUuids.contains(armorStand.getUniqueId())) continue;
                    if (!removalFilter.test(armorStand)) continue;
                    armorStand.remove();
                    removedUuids.add(armorStand.getUniqueId());
                    removed++;
                }
            }
        }
        for (UUID removedUuid : removedUuids) {
            unregister(removedUuid);
        }
        return removed;
    }

    private static UUID parseUuid(String uuidString) {
        try {
            return UUID.fromString(uuidString);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static String path(UUID uuid) {
        return "props." + uuid;
    }

    private static void ensureInitialized() {
        if (fileConfiguration == null) initialize();
    }

    private static void save() {
        try {
            fileConfiguration.save(file);
        } catch (IOException exception) {
            exception.printStackTrace();
        }
    }
}
