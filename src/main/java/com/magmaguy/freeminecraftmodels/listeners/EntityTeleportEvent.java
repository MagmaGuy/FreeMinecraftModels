package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

public class EntityTeleportEvent implements Listener {
    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onEntityTeleport(org.bukkit.event.entity.EntityTeleportEvent event) {
        if (event.getEntity() instanceof LivingEntity livingEntity && DynamicEntity.isDynamicEntity(livingEntity)) {
            DynamicEntity dynamicEntity = DynamicEntity.getDynamicEntity(livingEntity);
            dynamicEntity.teleport(event.getTo(), false);
        }
    }
}
