package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.commands.ReloadCommand;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Bukkit;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ModeledEntityManager {
    // Thread-safe storage for all modeled entities
    private static final Set<ModeledEntity> loadedModeledEntities = ConcurrentHashMap.newKeySet();

    private ModeledEntityManager() {
    }

    /**
     * Returns combined lists of all ModeledEntities (dynamic and static).
     *
     * @return Returns all ModeledEntities currently instanced by the plugin
     */
    public static HashSet<ModeledEntity> getAllEntities() {
        return new HashSet<>(ModeledEntity.getLoadedModeledEntities());
    }

    /**
     * Returns whether a model exists by a given name
     *
     * @param modelName Name to check
     * @return Whether the model exists
     */
    public static boolean modelExists(String modelName) {
        return FileModelConverter.getConvertedFileModels().containsKey(modelName);
    }

    /**
     * Returns the list of dynamic entities currently instanced by the plugin
     *
     * @return The list of currently instanced dynamic entities
     */
    public static HashMap<UUID, DynamicEntity> getDynamicEntities() {
        return DynamicEntity.getDynamicEntities();
    }

    public static HashMap<UUID, PropEntity> propEntities() {
        return PropEntity.getPropEntities();
    }

    /**
     * Safely handles reloading the plugin, importing new data and reinitializing models
     */
    public static void reload() {
        ReloadCommand.reloadPlugin(Bukkit.getConsoleSender());
    }
}