package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.freeminecraftmodels.utils.ModelItemFactory;
import com.magmaguy.magmacore.util.ChatColorConverter;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Admin sub-menu listing all models and custom items in a pack or folder.
 */
public class AdminModelListMenu {

    private static final HashMap<Inventory, AdminModelListMenu> openMenus = new HashMap<>();

    private final Player player;
    private final List<ListEntry> entries;
    private final Inventory inventory;
    private int page;

    /** Open from a package. */
    public AdminModelListMenu(Player player, FMMPackage pack) {
        this(player, pack.getContentPackageConfigFields().getName(),
                ModelMenuHelper.getModelsForPack(pack), ModelMenuHelper.getItemsForPack(pack));
    }

    /** Open from a folder with models only (backwards compat). */
    public AdminModelListMenu(Player player, String title, List<FileModelConverter> models) {
        this(player, title, models, Collections.emptyList());
    }

    /** Open from a folder with models + custom items. */
    public AdminModelListMenu(Player player, String title, List<FileModelConverter> models, List<String> customItemIds) {
        this.player = player;
        this.page = 0;

        // Build unified entry list: models (pure props) first, then custom items.
        // Models that are also custom items appear only in the items section.
        Set<String> itemIds = new HashSet<>(customItemIds);
        this.entries = new ArrayList<>();
        for (FileModelConverter model : models) {
            if (!itemIds.contains(model.getID())) {
                entries.add(new ModelEntry(model));
            }
        }
        for (String itemId : customItemIds) {
            entries.add(new CustomItemEntry(itemId));
        }

        this.inventory = Bukkit.createInventory(null, 54,
                ChatColorConverter.convert("&8FMM Admin - " + title));

        populateInventory();
        openMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populateInventory() {
        inventory.clear();

        inventory.setItem(ModelMenuHelper.BACK_SLOT, ModelMenuHelper.buildBackItem());

        int startIndex = page * ModelMenuHelper.ITEMS_PER_PAGE;
        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
            int entryIndex = startIndex + i;
            if (entryIndex < entries.size()) {
                inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[i], entries.get(entryIndex).buildDisplayItem());
            } else {
                inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[i], null);
            }
        }

        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT, ModelMenuHelper.buildPrevPageItem());
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / ModelMenuHelper.ITEMS_PER_PAGE));
        if (page < totalPages - 1) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT, ModelMenuHelper.buildNextPageItem());
        }
    }

    private void handleClick(int slot) {
        if (slot == ModelMenuHelper.BACK_SLOT) {
            openMenus.remove(inventory);
            new AdminContentMenu(player);
            return;
        }

        if (slot == ModelMenuHelper.PREV_SLOT && page > 0) {
            page--;
            populateInventory();
            return;
        }

        int totalPages = Math.max(1, (int) Math.ceil((double) entries.size() / ModelMenuHelper.ITEMS_PER_PAGE));
        if (slot == ModelMenuHelper.NEXT_SLOT && page < totalPages - 1) {
            page++;
            populateInventory();
            return;
        }

        int contentIndex = -1;
        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
            if (ModelMenuHelper.CONTENT_SLOTS[i] == slot) {
                contentIndex = i;
                break;
            }
        }

        if (contentIndex < 0) return;

        int entryIndex = page * ModelMenuHelper.ITEMS_PER_PAGE + contentIndex;
        if (entryIndex >= entries.size()) return;

        entries.get(entryIndex).onClick(player);
    }

    public static void registerEvents(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {
        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            Inventory clickedInventory = event.getInventory();
            AdminModelListMenu menu = openMenus.get(clickedInventory);
            if (menu == null) return;

            event.setCancelled(true);
            if (event.getClickedInventory() != clickedInventory) return;

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null) return;

            menu.handleClick(event.getSlot());
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            openMenus.remove(event.getInventory());
        }
    }

    // ── Entry types ─────────────────────────────────────────────────

    private interface ListEntry {
        ItemStack buildDisplayItem();
        void onClick(Player player);
    }

    private static class ModelEntry implements ListEntry {
        private final FileModelConverter model;

        ModelEntry(FileModelConverter model) { this.model = model; }

        @Override
        public ItemStack buildDisplayItem() {
            return ModelMenuHelper.buildModelItem(model, true);
        }

        @Override
        public void onClick(Player player) {
            player.getInventory().addItem(ModelItemFactory.createModelItem(model.getID(), Material.PAPER));
        }
    }

    private static class CustomItemEntry implements ListEntry {
        private final String itemId;

        CustomItemEntry(String itemId) { this.itemId = itemId; }

        @Override
        public ItemStack buildDisplayItem() {
            return ModelMenuHelper.buildCustomItemDisplayItem(itemId);
        }

        @Override
        public void onClick(Player player) {
            com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields config =
                    ItemScriptManager.getItemDefinitions().get(itemId);
            if (config != null) {
                player.getInventory().addItem(
                        com.magmaguy.freeminecraftmodels.utils.ModelItemFactory.createCustomItem(itemId, config));
            }
        }
    }
}
