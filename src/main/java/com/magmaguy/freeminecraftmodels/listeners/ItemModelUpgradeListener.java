package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DisplayModelRegistry;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Gives authored FMM items the display model they were made without. An item keeps the item
 * model stamped when it was created, so a wand handed out while the bundled wand had no model,
 * or before a server added one, stayed a vanilla blaze rod. Items that already name a model are
 * left alone.
 */
public class ItemModelUpgradeListener implements Listener {

    /** Adds the registered display model to an authored FMM item that has none. */
    static boolean upgrade(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta() || VersionChecker.serverVersionOlderThan(21, 4))
            return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null || meta.hasItemModel()) return false;
        String itemId = meta.getPersistentDataContainer().get(ItemScriptManager.ITEM_ID_KEY, PersistentDataType.STRING);
        if (itemId == null || !DisplayModelRegistry.hasDisplayModel(itemId)) return false;
        NamespacedKey model = NamespacedKey.fromString("freeminecraftmodels:display/" + itemId);
        if (model == null) return false;
        meta.setItemModel(model);
        return item.setItemMeta(meta);
    }

    private static void upgradeSlot(PlayerInventory inventory, int slot) {
        ItemStack item = inventory.getItem(slot);
        if (upgrade(item)) inventory.setItem(slot, item);
    }

    private static void upgradeHands(Player player) {
        PlayerInventory inventory = player.getInventory();
        upgradeSlot(inventory, inventory.getHeldItemSlot());
        ItemStack offHand = inventory.getItemInOffHand();
        if (upgrade(offHand)) inventory.setItemInOffHand(offHand);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        PlayerInventory inventory = event.getPlayer().getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++)
            if (upgrade(contents[slot])) inventory.setItem(slot, contents[slot]);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        upgradeSlot(event.getPlayer().getInventory(), event.getNewSlot());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        // The swap is applied after the event, so upgrade the hands on the next tick.
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            if (player.isOnline()) upgradeHands(player);
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        // A picked-up item can land in the selected slot without a held-slot change.
        Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
            if (player.isOnline()) upgradeHands(player);
        });
    }
}
