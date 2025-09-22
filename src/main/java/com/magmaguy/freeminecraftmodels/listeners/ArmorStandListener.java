package com.magmaguy.freeminecraftmodels.listeners;

import org.bukkit.entity.ArmorStand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

import static com.magmaguy.freeminecraftmodels.customentity.PropEntity.getPropEntityID;
import static com.magmaguy.freeminecraftmodels.customentity.PropEntity.respawnPropEntityFromArmorStand;

public class ArmorStandListener implements Listener {
    public static boolean bypass = false;

    @EventHandler
    public void onArmorStandSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.CUSTOM) return;
        if (bypass) {
            bypass = false;
            return;
        }

        if (event.getEntity() instanceof ArmorStand armorStand) {
            String propEntityID = getPropEntityID(armorStand);
            if (propEntityID == null) return;
            respawnPropEntityFromArmorStand(propEntityID, armorStand);
        }
    }
}
