package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.ReloadHandler;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.List;

public class ModeledEntityManager {
    private ModeledEntityManager() {
    }

    /**
     * Returns combined lists of all ModeledEntities (dynamic and static).
     *
     * @return Returns all ModeledEntities currently instanced by the plugin
     */
    public static List<ModeledEntity> getAllEntities() {
        List<ModeledEntity> modeledEntities = new ArrayList<>();
        modeledEntities.addAll(StaticEntity.getStaticEntities());
        modeledEntities.addAll(DynamicEntity.getDynamicEntities());
        return modeledEntities;
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
     * Returns the list of static entities currently instanced by the plugin
     *
     * @return The list of currently instanced static entities
     */
    public static List<StaticEntity> getStaticEntities() {
        return StaticEntity.getStaticEntities();
    }

    /**
     * Returns the list of dynamic entities currently instanced by the plugin
     *
     * @return The list of currently instanced dynamic entities
     */
    public static List<DynamicEntity> getDynamicEntities() {
        return DynamicEntity.getDynamicEntities();
    }

    /**
     * Safely handles reloading the plugin, importing new data and reinitializing models
     */
    public static void reload() {
        ReloadHandler.reload(Bukkit.getConsoleSender());
    }
}
