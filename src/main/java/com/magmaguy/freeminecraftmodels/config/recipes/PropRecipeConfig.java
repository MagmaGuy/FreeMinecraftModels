package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.magmacore.config.CustomConfigFields;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PropRecipeConfig extends CustomConfigFields {

    @Getter
    private String modelId = "";
    @Getter
    private List<String> shape = new ArrayList<>();
    @Getter
    private Map<Character, Material> parsedIngredients = new LinkedHashMap<>();

    public PropRecipeConfig(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    /**
     * Creates a new PropRecipeConfig programmatically (e.g. from the craftify UI).
     * Sets up the file configuration with the provided values and saves to the recipes folder.
     */
    public static PropRecipeConfig create(String modelId, List<String> shape, Map<Character, Material> ingredients, File recipesFolder) {
        if (!recipesFolder.exists()) recipesFolder.mkdirs();
        File file = new File(recipesFolder, modelId + ".yml");
        PropRecipeConfig config = new PropRecipeConfig(file.getName(), true);
        org.bukkit.configuration.file.YamlConfiguration fileConfig = new org.bukkit.configuration.file.YamlConfiguration();
        fileConfig.set("isEnabled", true);
        fileConfig.set("model_id", modelId);
        fileConfig.set("shape", shape);
        for (Map.Entry<Character, Material> entry : ingredients.entrySet()) {
            fileConfig.set("ingredients." + entry.getKey(), entry.getValue().name());
        }
        config.setFileConfiguration(fileConfig);
        config.setFile(file);
        config.processConfigFields();
        return config;
    }

    @Override
    public void processConfigFields() {
        this.isEnabled = processBoolean("isEnabled", isEnabled, true, true);
        this.modelId = processString("model_id", modelId, "", true);
        this.shape = processStringList("shape", shape, new ArrayList<>(), true);

        // Ingredients are a map section — process manually since CustomConfigFields
        // doesn't have a built-in processMap method
        parsedIngredients.clear();
        if (fileConfiguration != null && fileConfiguration.getConfigurationSection("ingredients") != null) {
            for (String key : fileConfiguration.getConfigurationSection("ingredients").getKeys(false)) {
                String materialName = fileConfiguration.getString("ingredients." + key);
                Material mat = Material.matchMaterial(materialName != null ? materialName : "");
                if (mat != null && !key.isEmpty()) {
                    parsedIngredients.put(key.charAt(0), mat);
                }
            }
        }
    }

    public ShapedRecipe registerRecipe() {
        if (modelId == null || modelId.isEmpty()) return null;
        NamespacedKey recipeKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);

        ItemStack output = ModelItemFactory.createModelItem(modelId, Material.PAPER);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, output);
        recipe.shape(shape.toArray(new String[0]));
        for (Map.Entry<Character, Material> entry : parsedIngredients.entrySet()) {
            recipe.setIngredient(entry.getKey(), entry.getValue());
        }

        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(recipe);
        return recipe;
    }

    public List<String> getShapeList() {
        return shape;
    }

    public void unregisterRecipe() {
        if (modelId == null || modelId.isEmpty()) return;
        NamespacedKey recipeKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);
        Bukkit.removeRecipe(recipeKey);
    }
}
