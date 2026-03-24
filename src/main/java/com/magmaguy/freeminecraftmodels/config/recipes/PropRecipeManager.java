package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class PropRecipeManager {

    private static final Map<String, PropRecipeConfig> loadedRecipes = new HashMap<>();

    public static void initialize() {
        loadedRecipes.clear();
        File recipesFolder = getRecipesFolder();
        if (!recipesFolder.exists()) {
            recipesFolder.mkdirs();
            return;
        }
        File[] files = recipesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) return;
        for (File file : files) {
            PropRecipeConfig config = PropRecipeConfig.load(file);
            if (config == null) {
                Logger.warn("Failed to load recipe from " + file.getName());
                continue;
            }
            try {
                config.registerRecipe();
                loadedRecipes.put(config.getModelId(), config);
            } catch (Exception e) {
                Logger.warn("Failed to register recipe for " + config.getModelId() + ": " + e.getMessage());
            }
        }
        if (!loadedRecipes.isEmpty()) {
            Logger.info("Loaded " + loadedRecipes.size() + " prop recipe(s).");
        }
    }

    public static void shutdown() {
        for (PropRecipeConfig config : loadedRecipes.values()) {
            config.unregisterRecipe();
        }
        loadedRecipes.clear();
    }

    public static void addRecipe(PropRecipeConfig config) {
        PropRecipeConfig existing = loadedRecipes.get(config.getModelId());
        if (existing != null) {
            existing.unregisterRecipe();
        }
        try {
            config.save(getRecipesFolder());
            config.registerRecipe();
            loadedRecipes.put(config.getModelId(), config);
        } catch (Exception e) {
            Logger.warn("Failed to save/register recipe for " + config.getModelId() + ": " + e.getMessage());
        }
    }

    public static File getRecipesFolder() {
        return new File(MetadataHandler.PLUGIN.getDataFolder(), "recipes");
    }

    public static Map<String, PropRecipeConfig> getLoadedRecipes() {
        return loadedRecipes;
    }
}
