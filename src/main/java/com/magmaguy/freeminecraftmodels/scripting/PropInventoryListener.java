package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/** Owns one writable inventory per prop; player sessions only identify its viewers. */
public class PropInventoryListener implements Listener {
    private record TrackedInventory(Inventory inventory, ArmorStand armorStand, Set<UUID> viewers) {}
    private static final Map<UUID, TrackedInventory> trackedInventories = new HashMap<>();
    private static final Map<ArmorStand, TrackedInventory> propInventories = new IdentityHashMap<>();
    private static PropInventoryListener listener;

    public static void openInventory(Player player, ArmorStand armorStand, int size, String title) {
        if (!player.isOnline() || !armorStand.isValid()) return;
        initialize();
        TrackedInventory tracked = propInventories.get(armorStand);
        boolean created = tracked == null;
        if (created) {
            String data = armorStand.getPersistentDataContainer().get(LuaPropTable.INVENTORY_KEY, PersistentDataType.STRING);
            ItemStack[] saved = data == null ? null : LuaPropTable.deserializeInventory(data);
            if (data != null && saved == null) throw new IllegalStateException("Cannot read stored prop inventory");
            // A changed row count must not silently discard previously stored slots.
            int capacity = Math.max(size, saved == null ? 0 : saved.length);
            Inventory inventory = Bukkit.createInventory(null, capacity, title);
            if (saved != null) for (int i = 0; i < saved.length; i++) inventory.setItem(i, saved[i]);
            tracked = new TrackedInventory(inventory, armorStand, new HashSet<>());
            propInventories.put(armorStand, tracked);
        }
        boolean opened = false;
        try {
            var view = player.openInventory(tracked.inventory());
            opened = view != null && player.getOpenInventory().getTopInventory() == tracked.inventory();
            if (opened) {
                propInventories.put(armorStand, tracked);
                tracked.viewers().add(player.getUniqueId());
                trackedInventories.put(player.getUniqueId(), tracked);
            }
        } finally {
            if (!opened && created && tracked.viewers().isEmpty()) propInventories.remove(armorStand, tracked);
        }
    }

    public static boolean isViewingInventory(UUID playerUUID, ArmorStand armorStand) {
        TrackedInventory tracked = trackedInventories.get(playerUUID);
        return tracked != null && tracked.armorStand() == armorStand;
    }

    public static void initialize() {
        if (listener != null) return;
        listener = new PropInventoryListener();
        Bukkit.getPluginManager().registerEvents(listener, MetadataHandler.PLUGIN);
    }

    private static void save(TrackedInventory tracked) {
        String data = LuaPropTable.serializeInventory(tracked.inventory());
        if (data == null) throw new IllegalStateException("Cannot save prop inventory");
        tracked.armorStand().getPersistentDataContainer().set(LuaPropTable.INVENTORY_KEY, PersistentDataType.STRING, data);
    }

    /** Save before closing, so destruction reads the current shared contents. */
    public static void closeAndUntrackAll(ArmorStand armorStand) {
        TrackedInventory tracked = propInventories.get(armorStand);
        if (tracked == null) return;
        save(tracked);
        propInventories.remove(armorStand, tracked);
        for (UUID viewer : tracked.viewers()) trackedInventories.remove(viewer, tracked);
        tracked.viewers().clear();
        for (var viewer : new ArrayList<>(tracked.inventory().getViewers()))
            if (viewer.getOpenInventory().getTopInventory() == tracked.inventory()) viewer.closeInventory();
    }

    public static void shutdown() {
        for (ArmorStand armorStand : new ArrayList<>(propInventories.keySet())) closeAndUntrackAll(armorStand);
        if (listener != null) HandlerList.unregisterAll(listener);
        listener = null;
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        TrackedInventory tracked = trackedInventories.get(uuid);
        if (tracked == null || event.getInventory() != tracked.inventory()) return;
        trackedInventories.remove(uuid, tracked);
        tracked.viewers().remove(uuid);
        if (tracked.viewers().isEmpty()) {
            save(tracked);
            propInventories.remove(tracked.armorStand(), tracked);
        }
    }
}
