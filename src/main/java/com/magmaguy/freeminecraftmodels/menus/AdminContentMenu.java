package com.magmaguy.freeminecraftmodels.menus;

import com.magmaguy.freeminecraftmodels.content.FMMPackage;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class AdminContentMenu {

    private static final HashMap<Inventory, AdminContentMenu> openMenus = new HashMap<>();

    private final Player player;
    private final List<FMMPackage> packs;
    private final Inventory inventory;
    private int page;

    public AdminContentMenu(Player player) {
        this.player = player;
        this.page = 0;
        this.packs = FMMPackage.getFmmPackages().values().stream()
                .filter(pack -> pack.getContentPackageConfigFields().isEnabled())
                .sorted(Comparator.comparing(pack ->
                        ChatColor.stripColor(ChatColorConverter.convert(pack.getDisplayName()))))
                .collect(Collectors.toList());
        this.inventory = Bukkit.createInventory(null, 54,
                ChatColorConverter.convert("&8FMM Admin - Content Packs"));
        populate();
        openMenus.put(inventory, this);
        player.openInventory(inventory);
    }

    private void populate() {
        inventory.clear();

        int start = page * ModelMenuHelper.ITEMS_PER_PAGE;
        int end = Math.min(start + ModelMenuHelper.ITEMS_PER_PAGE, packs.size());

        for (int i = start; i < end; i++) {
            int slotIndex = i - start;
            inventory.setItem(ModelMenuHelper.CONTENT_SLOTS[slotIndex],
                    ModelMenuHelper.buildPackItem(packs.get(i)));
        }

        if (page > 0) {
            inventory.setItem(ModelMenuHelper.PREV_SLOT,
                    ModelMenuHelper.buildNavItem("&ePrevious Page"));
        }

        int totalPages = (int) Math.ceil((double) packs.size() / ModelMenuHelper.ITEMS_PER_PAGE);
        if (page < totalPages - 1) {
            inventory.setItem(ModelMenuHelper.NEXT_SLOT,
                    ModelMenuHelper.buildNavItem("&eNext Page"));
        }
    }

    public static void registerEvents(Plugin plugin) {
        Bukkit.getPluginManager().registerEvents(new Events(), plugin);
    }

    public static class Events implements Listener {

        @EventHandler
        public void onInventoryClick(InventoryClickEvent event) {
            Inventory clicked = event.getInventory();
            AdminContentMenu menu = openMenus.get(clicked);
            if (menu == null) return;

            event.setCancelled(true);

            if (event.getClickedInventory() != clicked) return;

            ItemStack item = event.getCurrentItem();
            if (item == null) return;

            int slot = event.getRawSlot();
            Player player = (Player) event.getWhoClicked();

            if (slot == ModelMenuHelper.PREV_SLOT && menu.page > 0) {
                menu.page--;
                menu.populate();
                return;
            }

            int totalPages = (int) Math.ceil((double) menu.packs.size() / ModelMenuHelper.ITEMS_PER_PAGE);
            if (slot == ModelMenuHelper.NEXT_SLOT && menu.page < totalPages - 1) {
                menu.page++;
                menu.populate();
                return;
            }

            // Check if a content slot was clicked
            int start = menu.page * ModelMenuHelper.ITEMS_PER_PAGE;
            for (int i = 0; i < ModelMenuHelper.CONTENT_SLOTS.length; i++) {
                if (ModelMenuHelper.CONTENT_SLOTS[i] == slot) {
                    int packIndex = start + i;
                    if (packIndex < menu.packs.size()) {
                        openMenus.remove(clicked);
                        new AdminModelListMenu(player, menu.packs.get(packIndex));
                    }
                    return;
                }
            }
        }

        @EventHandler
        public void onInventoryClose(InventoryCloseEvent event) {
            openMenus.remove(event.getInventory());
        }
    }
}
