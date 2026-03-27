package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

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
        scanRecipeDirectory(recipesFolder);
        if (!loadedRecipes.isEmpty()) {
            Logger.info("Loaded " + loadedRecipes.size() + " prop recipe(s).");
        }
    }

    private static void scanRecipeDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                scanRecipeDirectory(file);
                continue;
            }
            if (!file.getName().endsWith(".yml")) continue;

            PropRecipeConfig config = new PropRecipeConfig(file.getName(), true);
            FileConfiguration fileConfig = YamlConfiguration.loadConfiguration(file);
            config.setFileConfiguration(fileConfig);
            config.setFile(file);
            config.processConfigFields();

            if (!config.isEnabled() || config.getModelId().isEmpty()) {
                continue;
            }

            try {
                config.registerRecipe();
                loadedRecipes.put(config.getModelId(), config);
            } catch (Exception e) {
                Logger.warn("Failed to register recipe for " + config.getModelId() + ": " + e.getMessage());
            }
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
            ConfigurationEngine.fileSaverCustomValues(config.getFileConfiguration(), config.getFile());
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
