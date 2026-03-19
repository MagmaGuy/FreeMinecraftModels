package com.magmaguy.freeminecraftmodels.listeners;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.customentity.core.MountSeat;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.persistence.PersistentDataType;

/**
 * Listens for players dismounting from mount-seat ArmorStands
 * and cleans up the corresponding {@link MountSeat}.
 */
public class MountDismountListener implements Listener {

    @EventHandler
    public void onEntityDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (!(event.getDismounted() instanceof ArmorStand armorStand)) return;
        if (!armorStand.getPersistentDataContainer().has(MountSeat.MOUNT_SEAT_KEY, PersistentDataType.BYTE)) return;

        // Find the ModeledEntity that owns this mount armor stand
        for (ModeledEntity modeledEntity : ModeledEntity.getLoadedModeledEntities()) {
            MountPointManager manager = modeledEntity.getMountPointManager();
            if (manager == null) continue;
            if (manager.isMountArmorStand(armorStand)) {
                manager.handleDismount(player, armorStand);
                return;
            }
        }
    }
}
