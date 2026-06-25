package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

public class ModeledEntitiesClock {
    private static BukkitTask clock = null;

    private ModeledEntitiesClock() {
    }

    public static void start() {
        shutdown();
        clock = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimerAsynchronously(MetadataHandler.PLUGIN, 0, 1);
    }

    public static void shutdown() {
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
    }

    public static void tick() {
        NMSAdapter adapter = NMSManager.getAdapter();
        if (!NMSManager.isEnabled() || adapter == null) return;

        AbstractPacketBundle abstractPacketBundle = adapter.createPacketBundle();
        ModeledEntity.getLoadedModeledEntities().forEach(modeledEntity -> modeledEntity.tick(abstractPacketBundle));
        abstractPacketBundle.send();
    }
}
