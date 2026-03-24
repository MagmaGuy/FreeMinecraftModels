package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.config.recipes.PropRecipeManager;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Player-facing, view-only menu that displays all craftable FMM items across
 * every loaded content pack. Supports pagination but does not give items on click.
 */
public class CraftableItemsMenu {

    private static final HashMap<Inventory, CraftableItemsMenu> openMenus = new HashMap<>();

    private final Player player;
    private final List<FileModelConverter> craftableModels;
    private int page;

    public CraftableItemsMenu(Player player) {
        this.player = player;
        this.page = 0;

        Set<String> recipeIds = PropRecipeManager.getLoadedRecipes().keySet();
        this.craftableModels = FileModelConverter.getConvertedFileModels().values().stream()
                .filter(converter -> recipeIds.contains(converter.getID()))
                .sorted(Comparator.comparing(FileModelConverter::getID))
                .collect(Collectors.toList());

        open();
    }

    private void open() {
        Inventory inventory = Bukkit.createInventory(null, 54,
                ChatColorConverter.convert("&8FMM - Craftable Items"));

        populate(inventory);

        openMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populate(Inventory inventory) {
        inventory.clear();

        int start = page * ModelMenuHelper.ITEMS_PER_PAGE;
        int end = Math.min(start + ModelMenuHelper.ITEMS_PER_PAGE, craftableModels.size());

        for (int i = start; i < end; i++) {
            int slotIndex = i - start;
            inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[slotIndex],
                    ModelMenuHelper.buildModelItem(craftableModels.get(i), false));
        }

        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT,
                    ModelMenuHelper.buildPrevPageItem());
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) craftableModels.size() / ModelMenuHelper.ITEMS_PER_PAGE));
        if (page < totalPages - 1) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT,
                    ModelMenuHelper.buildNextPageItem());
        }
    }

    public static void registerEvents(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            Inventory inventory = event.getInventory();
            CraftableItemsMenu menu = openMenus.get(inventory);
            if (menu == null) return;

            event.setCancelled(true);

            if (event.getClickedInventory() != inventory) return;

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;

            int slot = event.getRawSlot();

            if (slot == ModelMenuHelper.PREV_SLOT && menu.page > 0) {
                menu.page--;
                menu.populate(inventory);
            } else if (slot == ModelMenuHelper.NEXT_SLOT) {
                int totalPages = Math.max(1, (int) Math.ceil(
                        (double) menu.craftableModels.size() / ModelMenuHelper.ITEMS_PER_PAGE));
                if (menu.page < totalPages - 1) {
                    menu.page++;
                    menu.populate(inventory);
                }
            } else {
                // Check if a content slot was clicked — open recipe detail
                int start = menu.page * ModelMenuHelper.ITEMS_PER_PAGE;
                for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
                    if (ModelMenuHelper.CONTENT_SLOTS[i] == slot) {
                        int modelIndex = start + i;
                        if (modelIndex < menu.craftableModels.size()) {
                            openMenus.remove(inventory);
                            new RecipeDetailMenu((Player) event.getWhoClicked(),
                                    menu.craftableModels.get(modelIndex).getID());
                        }
                        return;
                    }
                }
            }
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            openMenus.remove(event.getInventory());
        }
    }
}
