package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeConfig;
import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays a spatial recipe layout for a craftable model item.
 * Shows the 3x3 crafting grid on the left, an arrow in the middle,
 * and the output item on the right.
 */
public class RecipeDetailMenu {

    private static final HashMap<Inventory, RecipeDetailMenu> openMenus = new HashMap<>();

    // Layout slots (see design):
    // Row 1: [Back at 0]
    // Row 2: (blank)
    // Row 3: grid row 1 at slots 19, 20, 21
    // Row 4: grid row 2 at slots 28, 29, 30 — arrow at 32 — output at 34
    // Row 5: grid row 3 at slots 37, 38, 39
    private static final int BACK_SLOT = 0;
    private static final int[] GRID_ROW_1 = {19, 20, 21};
    private static final int[] GRID_ROW_2 = {28, 29, 30};
    private static final int[] GRID_ROW_3 = {37, 38, 39};
    private static final int[][] GRID_ROWS = {GRID_ROW_1, GRID_ROW_2, GRID_ROW_3};
    private static final int ARROW_SLOT = 32;
    private static final int OUTPUT_SLOT = 34;

    private final Player player;
    private final String modelId;

    public RecipeDetailMenu(Player player, String modelId) {
        this.player = player;
        this.modelId = modelId;

        String title = ChatColorConverter.convert(
                "&8Recipe: &6" + ModelItemFactory.formatModelName(modelId));
        Inventory inventory = Bukkit.createInventory(null, 54, title);

        populate(inventory);
        openMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populate(Inventory inventory) {
        // Back button
        inventory.setItem(BACK_SLOT, ModelMenuHelper.buildBackItem());

        PropRecipeConfig recipe = PropRecipeManager.getLoadedRecipes().get(modelId);
        if (recipe == null) return;

        // Render 3x3 crafting grid
        List<String> shape = recipe.getShapeList();
        Map<Character, Material> ingredients = recipe.getParsedIngredients();

        for (int row = 0; row < shape.size() && row < 3; row++) {
            String rowStr = shape.get(row);
            for (int col = 0; col < rowStr.length() && col < 3; col++) {
                char c = rowStr.charAt(col);
                Material mat = ingredients.get(c);
                ItemStack slotItem;
                if (mat != null) {
                    slotItem = ItemStackGenerator.generateItemStack(mat,
                            "&f" + formatMaterialName(mat), List.of());
                } else {
                    slotItem = ItemStackGenerator.generateItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                            "&8Empty", List.of());
                }
                inventory.setItem(GRID_ROWS[row][col], slotItem);
            }
        }

        // Arrow
        inventory.setItem(ARROW_SLOT, ModelMenuHelper.buildArrowRightItem());

        // Output item
        inventory.setItem(OUTPUT_SLOT, ModelItemFactory.createModelItem(modelId, Material.PAPER));
    }

    private static String formatMaterialName(Material material) {
        String[] words = material.name().split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (!sb.isEmpty()) sb.append(" ");
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    public static void registerEvents(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            Inventory inventory = event.getInventory();
            RecipeDetailMenu menu = openMenus.get(inventory);
            if (menu == null) return;

            event.setCancelled(true);

            if (event.getClickedInventory() != inventory) return;

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;

            int slot = event.getRawSlot();

            if (slot == BACK_SLOT) {
                openMenus.remove(inventory);
                new CraftableItemsMenu((Player) event.getWhoClicked());
            }
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            openMenus.remove(event.getInventory());
        }
    }
}
