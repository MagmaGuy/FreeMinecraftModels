package com.magmaguy.freeminecraftmodels.config.recipes;

import com.magmaguy.freeminecraftmodels.MockBukkitTestSupport;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropRecipeConfigTest extends MockBukkitTestSupport {

    @AfterEach
    void clearRecipes() {
        PropRecipeManager.shutdown();
    }

    @Test
    void addRecipePersistsAndRegistersPlacementItemRecipe() {
        File recipesFolder = PropRecipeManager.getRecipesFolder();
        Map<Character, Material> ingredients = new LinkedHashMap<>();
        ingredients.put('A', Material.OAK_PLANKS);
        ingredients.put('B', Material.STICK);
        ingredients.put('C', Material.PAPER);

        PropRecipeConfig config = PropRecipeConfig.create(
                "market_stall",
                List.of("AB", " C"),
                ingredients,
                recipesFolder);

        PropRecipeManager.addRecipe(config);

        NamespacedKey key = new NamespacedKey(plugin, "prop_recipe_market_stall");
        ShapedRecipe recipe = assertInstanceOf(ShapedRecipe.class, Bukkit.getRecipe(key));
        ItemStack result = recipe.getResult();

        assertTrue(new File(recipesFolder, "market_stall.yml").isFile());
        assertEquals(config, PropRecipeManager.getLoadedRecipes().get("market_stall"));
        assertEquals(List.of("AB", " C"), config.getShapeList());
        assertEquals(Material.OAK_PLANKS, config.getParsedIngredients().get('A'));
        assertNotNull(result.getItemMeta());
        assertEquals("market_stall", result.getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "model_id"), PersistentDataType.STRING));
    }
}
