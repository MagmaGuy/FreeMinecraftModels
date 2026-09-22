package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.config.ConfigurationEngine;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Recipe;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class PropRecipeManager {

    private static final Map<String, PropRecipeConfig> loadedRecipes = new HashMap<>();

    public static void initialize() {
        activateCatalog(prepareCatalog());
    }

    public static Map<String, PropRecipeConfig> prepareCatalog() {
        Map<String, PropRecipeConfig> candidate = new java.util.LinkedHashMap<>();
        File folder = getRecipesFolder();
        try {
            java.nio.file.Files.createDirectories(folder.toPath());
            try (var paths = java.nio.file.Files.walk(folder.toPath())) {
                for (var path : paths.filter(java.nio.file.Files::isRegularFile)
                        .filter(file -> file.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                    PropRecipeConfig config = new PropRecipeConfig(path.getFileName().toString(), true);
                    YamlConfiguration yaml = new YamlConfiguration();
                    yaml.load(path.toFile());
                    config.setFileConfiguration(yaml);
                    config.setFile(path.toFile());
                    config.processConfigFields();
                    if (!config.isEnabled() || config.getModelId().isEmpty()) continue;
                    PropRecipeConfig previous = candidate.putIfAbsent(config.getModelId(), config);
                    if (previous != null) throw new IllegalArgumentException("Duplicate recipe model "
                            + config.getModelId() + " in " + previous.getFile() + " and " + path);
                }
            }
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            throw new IllegalStateException("Cannot prepare prop recipes", failure);
        }
        return java.util.Collections.unmodifiableMap(candidate);
    }

    public static void activateCatalog(Map<String, PropRecipeConfig> prepared) {
        Map<String, PropRecipeConfig> candidate = new java.util.LinkedHashMap<>();
        for (PropRecipeConfig config : prepared.values()) {
            // Model aliases belong to the newly published registry, not the old catalog
            // that was still live while YAML was prepared on the worker.
            config.resolveModelId();
            PropRecipeConfig previous = candidate.putIfAbsent(config.getModelId(), config);
            if (previous != null) throw new IllegalArgumentException("Duplicate recipe model "
                    + config.getModelId() + " in " + previous.getFile() + " and " + config.getFile());
        }
        Map<org.bukkit.NamespacedKey, Recipe> previous = new java.util.LinkedHashMap<>();
        for (PropRecipeConfig config : loadedRecipes.values()) previous.put(config.recipeKey(), Bukkit.getRecipe(config.recipeKey()));
        for (PropRecipeConfig config : candidate.values()) previous.putIfAbsent(config.recipeKey(), Bukkit.getRecipe(config.recipeKey()));
        try {
            for (PropRecipeConfig config : loadedRecipes.values()) config.unregisterRecipe();
            for (PropRecipeConfig config : candidate.values()) config.registerRecipe();
        } catch (RuntimeException failure) {
            previous.forEach((key, recipe) -> {
                try {
                    if (Bukkit.getRecipe(key) != null && !Bukkit.removeRecipe(key))
                        throw new IllegalStateException("Cannot remove failed recipe " + key);
                    if (recipe != null && !Bukkit.addRecipe(recipe))
                        throw new IllegalStateException("Cannot restore recipe " + key);
                } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            });
            throw failure;
        }
        loadedRecipes.clear();
        loadedRecipes.putAll(candidate);
        if (!loadedRecipes.isEmpty()) Logger.info("Loaded " + loadedRecipes.size() + " prop recipe(s).");
    }

    public static void shutdown() {
        for (PropRecipeConfig config : loadedRecipes.values()) {
            config.unregisterRecipe();
        }
        loadedRecipes.clear();
    }

    public static boolean addRecipe(PropRecipeConfig config) {
        Recipe previous = null;
        boolean registered = false;
        try {
            config.resolveModelId();
            previous = Bukkit.getRecipe(config.recipeKey());
            config.registerRecipe();
            registered = true;
            ConfigurationEngine.fileSaverCustomValues(config.getFileConfiguration(), config.getFile());
            loadedRecipes.put(config.getModelId(), config);
            return true;
        } catch (Exception e) {
            if (registered) {
                try {
                    config.restoreRecipe(previous);
                } catch (RuntimeException restorationFailure) {
                    e.addSuppressed(restorationFailure);
                    Logger.warn("Could not restore recipe for " + config.getModelId() + ": " + restorationFailure.getMessage());
                }
            }
            Logger.warn("Failed to save/register recipe for " + config.getModelId() + ": " + e.getMessage());
            return false;
        }
    }

    public static File getRecipesFolder() {
        return new File(MetadataHandler.PLUGIN.getDataFolder(), "recipes");
    }

    public static Map<String, PropRecipeConfig> getLoadedRecipes() {
        return loadedRecipes;
    }

    /** The shop retains the catalog even when its entries cannot be crafted. */
    public static PropRecipeConfig getCraftableRecipe(String modelId) {
        PropRecipeConfig recipe = loadedRecipes.get(modelId);
        return recipe != null && recipe.isRegistered() ? recipe : null;
    }
}
