package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataType;

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

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        Bukkit.getPluginManager().registerEvents(new PropInventoryListener(), MetadataHandler.PLUGIN);
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
