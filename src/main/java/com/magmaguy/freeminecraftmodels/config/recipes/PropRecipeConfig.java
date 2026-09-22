package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.BowStateDetector;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.config.ShopConfig;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.magmacore.config.CustomConfigFields;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
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
    @Getter
    private boolean shopEnabled = true;
    @Getter
    private double shopPrice = 100.0;

    public PropRecipeConfig(String filename, boolean isEnabled) {
        super(filename, isEnabled);
    }

    /**
     * Creates a new PropRecipeConfig programmatically (e.g. from the craftify UI).
     * Prepares a detached configuration; the manager persists it after registration succeeds.
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

        this.shopEnabled = processBoolean("shopEnabled", shopEnabled, true, true);
        double defaultPrice = ShopConfig.getDefaultPrice() > 0 ? ShopConfig.getDefaultPrice() : 100.0;
        this.shopPrice = processDouble("shopPrice", defaultPrice, defaultPrice, true);
    }

    void resolveModelId() {
        String baseModelId = BowStateDetector.stripStateSuffix(modelId);
        if (!baseModelId.equals(modelId) && DisplayModelRegistry.hasDisplayModel(baseModelId)) modelId = baseModelId;
    }

    public ShapedRecipe registerRecipe() {
        if (modelId == null || modelId.isEmpty()) throw new IllegalArgumentException("Recipe model ID is missing");
        NamespacedKey recipeKey = recipeKey();

        ItemStack output = ModelItemFactory.createModelItem(modelId, Material.PAPER);

        ShapedRecipe recipe = new ShapedRecipe(recipeKey, output);
        recipe.shape(shape.toArray(new String[0]));
        for (Map.Entry<Character, Material> entry : parsedIngredients.entrySet()) {
            recipe.setIngredient(entry.getKey(), entry.getValue());
        }

        // Clear any previous registration even when crafting is disabled, including
        // recipes persisted into world data. The recipe
        // object is still built and returned either way, because the shop and
        // recipe menus render from it — purchase-only servers keep the catalog.
        Recipe previous = Bukkit.getRecipe(recipeKey);
        if (previous != null && !Bukkit.removeRecipe(recipeKey))
            throw new IllegalStateException("Could not remove the previous recipe " + recipeKey);
        try {
            if (DefaultConfig.registerCraftingRecipes && !Bukkit.addRecipe(recipe))
                throw new IllegalStateException("Server rejected recipe " + recipeKey);
        } catch (RuntimeException failure) {
            try {
                restoreRecipe(previous);
            } catch (RuntimeException restorationFailure) {
                failure.addSuppressed(restorationFailure);
                Logger.warn("Could not restore recipe " + recipeKey + ": " + restorationFailure.getMessage());
            }
            throw failure;
        }
        return recipe;
    }

    NamespacedKey recipeKey() {
        return new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);
    }

    void restoreRecipe(Recipe previous) {
        Bukkit.removeRecipe(recipeKey());
        if (previous != null && !Bukkit.addRecipe(previous))
            throw new IllegalStateException("Server rejected restoration of recipe " + recipeKey());
    }

    public List<String> getShapeList() {
        return shape;
    }

    /** Whether this catalog entry currently has a crafting recipe on the server. */
    public boolean isRegistered() {
        if (modelId == null || modelId.isEmpty()) return false;
        return Bukkit.getRecipe(new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId)) != null;
    }

    public void unregisterRecipe() {
        if (modelId == null || modelId.isEmpty()) return;
        NamespacedKey recipeKey = new NamespacedKey(MetadataHandler.PLUGIN, "prop_recipe_" + modelId);
        if (Bukkit.getRecipe(recipeKey) != null && !Bukkit.removeRecipe(recipeKey))
            throw new IllegalStateException("Cannot remove recipe " + recipeKey);
    }
}
