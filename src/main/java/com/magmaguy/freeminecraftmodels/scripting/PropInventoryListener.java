package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PropInventoryListener implements Listener {

    private record TrackedInventory(Inventory inventory, ArmorStand armorStand) {}

    private static final Map<UUID, TrackedInventory> trackedInventories = new ConcurrentHashMap<>();
    private static boolean initialized = false;

    public static void trackInventory(UUID playerUUID, Inventory inventory, ArmorStand armorStand) {
        trackedInventories.put(playerUUID, new TrackedInventory(inventory, armorStand));
    }

    /**
     * Returns true if the player currently has a prop inventory open for the given armor stand.
     */
    public static boolean isViewingInventory(UUID playerUUID, ArmorStand armorStand) {
        TrackedInventory tracked = trackedInventories.get(playerUUID);
        return tracked != null && tracked.armorStand.equals(armorStand);
    }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        Bukkit.getPluginManager().registerEvents(new PropInventoryListener(), MetadataHandler.PLUGIN);
    }

    /**
     * Saves all currently open prop inventories and resets state.
     * Called on plugin disable/reload to avoid data loss.
     */
    public static synchronized void shutdown() {
        for (Map.Entry<UUID, TrackedInventory> entry : trackedInventories.entrySet()) {
            TrackedInventory tracked = entry.getValue();
            String data = LuaPropTable.serializeInventory(tracked.inventory);
            if (data != null && tracked.armorStand.isValid()) {
                tracked.armorStand.getPersistentDataContainer()
                        .set(LuaPropTable.INVENTORY_KEY, PersistentDataType.STRING, data);
            }
        }
        trackedInventories.clear();
        initialized = false;
    }

    /**
     * Closes all inventories tracked for the given armor stand and removes them from tracking.
     * This prevents the InventoryCloseEvent from re-saving data after contents have been dropped.
     * Must be called BEFORE dropping items to avoid duplication.
     */
    public static void closeAndUntrackAll(ArmorStand armorStand) {
        Iterator<Map.Entry<UUID, TrackedInventory>> it = trackedInventories.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, TrackedInventory> entry = it.next();
            if (entry.getValue().armorStand.equals(armorStand)) {
                // Close the inventory for the viewer — this fires InventoryCloseEvent,
                // but we remove from the map first so the event handler won't save
                Inventory inv = entry.getValue().inventory;
                it.remove();
                for (HumanEntity viewer : new ArrayList<>(inv.getViewers())) {
                    viewer.closeInventory();
                }
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        TrackedInventory tracked = trackedInventories.remove(uuid);
        if (tracked == null) return;
        if (event.getInventory() != tracked.inventory) return;

        // Serialize and save to PDC
        String data = LuaPropTable.serializeInventory(tracked.inventory);
        if (data != null && tracked.armorStand.isValid()) {
            tracked.armorStand.getPersistentDataContainer()
                    .set(LuaPropTable.INVENTORY_KEY, PersistentDataType.STRING, data);
        }
    }
}
