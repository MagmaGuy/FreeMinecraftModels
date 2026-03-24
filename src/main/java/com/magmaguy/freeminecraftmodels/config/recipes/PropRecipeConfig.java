package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class PropRecipeConfig {

    private final String modelId;
    private final List<String> shape;
    private final Map<Character, Material> ingredients;

    public PropRecipeConfig(String modelId, List<String> shape, Map<Character, Material> ingredients) {
        this.modelId = modelId;
        this.shape = shape;
        this.ingredients = ingredients;
    }

    public void save(File recipesFolder) throws IOException {
        if (!recipesFolder.exists()) recipesFolder.mkdirs();
        File file = new File(recipesFolder, modelId + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        config.set("model_id", modelId);
        config.set("shape", shape);
        for (Map.Entry<Character, Material> entry : ingredients.entrySet()) {
            config.set("ingredients." + entry.getKey(), entry.getValue().name());
        }
        config.save(file);
    }

    public static PropRecipeConfig load(File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        String modelId = config.getString("model_id");
        if (modelId == null) return null;
        List<String> shape = config.getStringList("shape");
        if (shape.isEmpty()) return null;
        Map<Character, Material> ingredients = new LinkedHashMap<>();
        if (config.getConfigurationSection("ingredients") != null) {
            for (String key : config.getConfigurationSection("ingredients").getKeys(false)) {
                Material mat = Material.matchMaterial(config.getString("ingredients." + key));
                if (mat != null) {
                    ingredients.put(key.charAt(0), mat);
                }
            }
        }
        return new PropRecipeConfig(modelId, shape, ingredients);
    }

    public ShapedRecipe registerRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);

        ItemStack output = ModelItemFactory.createModelItem(modelId, Material.PAPER);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, output);
        recipe.shape(shape.toArray(new String[0]));
        for (Map.Entry<Character, Material> entry : ingredients.entrySet()) {
            recipe.setIngredient(entry.getKey(), entry.getValue());
        }

        Bukkit.removeRecipe(recipeKey);
        Bukkit.addRecipe(recipe);
        return recipe;
    }

    public void unregisterRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);
        Bukkit.removeRecipe(recipeKey);
    }

    public String getModelId() { return modelId; }
    public List<String> getShapeList() { return shape; }
    public Map<Character, Material> getIngredients() { return ingredients; }
}
