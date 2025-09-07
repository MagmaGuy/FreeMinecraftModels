package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;

public class ModeledEntitiesClock {
    private static BukkitTask clock = null;

    private ModeledEntitiesClock() {
    }

    public static void start() {
        clock = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimerAsynchronously(MetadataHandler.PLUGIN, 0, 1);
    }

    public static void shutdown() {
        if (clock != null) clock.cancel();
    }

    public static void tick() {
        AbstractPacketBundle abstractPacketBundle = NMSManager.getAdapter().createPacketBundle();
        new ArrayList<>(ModeledEntity.getLoadedModeledEntities()).forEach(modeledEntity -> modeledEntity.tick(abstractPacketBundle));
        abstractPacketBundle.send();
    }
}
