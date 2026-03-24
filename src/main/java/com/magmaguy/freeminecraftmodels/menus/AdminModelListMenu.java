package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
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

import java.util.HashMap;
import java.util.List;

public class AdminModelListMenu {

    private static final HashMap<Inventory, AdminModelListMenu> openMenus = new HashMap<>();

    private final Player player;
    private final FMMPackage pack;
    private final List<FileModelConverter> models;
    private final Inventory inventory;
    private int page;

    public AdminModelListMenu(Player player, FMMPackage pack) {
        this(player, pack, 0);
    }

    public AdminModelListMenu(Player player, FMMPackage pack, int page) {
        this.player = player;
        this.pack = pack;
        this.page = page;
        this.models = ModelMenuHelper.getModelsForPack(pack);

        String packName = pack.getContentPackageConfigFields().getName();
        String title = ChatColorConverter.convert("&8FMM Admin - " + packName);
        this.inventory = Bukkit.createInventory(null, 54, title);

        populateInventory();
        openMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populateInventory() {
        inventory.clear();

        // Back button
        inventory.setItem(ModelMenuHelper.BACK_SLOT, ModelMenuHelper.buildBackItem());

        // Content items for current page
        int startIndex = page * ModelMenuHelper.ITEMS_PER_PAGE;
        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
            int modelIndex = startIndex + i;
            if (modelIndex < models.size()) {
                inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[i],
                        ModelMenuHelper.buildModelItem(models.get(modelIndex), true));
            } else {
                inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[i], null);
            }
        }

        // Previous page arrow
        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT, ModelMenuHelper.buildPrevPageItem());
        }

        // Next page arrow
        int totalPages = (int) Math.ceil((double) models.size() / ModelMenuHelper.ITEMS_PER_PAGE);
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

        int totalPages = (int) Math.ceil((double) models.size() / ModelMenuHelper.ITEMS_PER_PAGE);
        if (slot == ModelMenuHelper.NEXT_SLOT && page < totalPages - 1) {
            page++;
            populateInventory();
            return;
        }

        // Check if clicked slot is a content slot
        int contentIndex = -1;
        for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
            if (ModelMenuHelper.CONTENT_SLOTS[i] == slot) {
                contentIndex = i;
                break;
            }
        }

        if (contentIndex < 0) return;

        int modelIndex = page * ModelMenuHelper.ITEMS_PER_PAGE + contentIndex;
        if (modelIndex >= models.size()) return;

        String modelId = models.get(modelIndex).getID();

        player.getInventory().addItem(ModelItemFactory.createModelItem(modelId, Material.PAPER));
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

            // Ignore clicks in the player's own inventory
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
}
