package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    public static void shutdown() {
        clock.cancel();
    }

    public static void tick() {
        ModeledEntity.getLoadedModeledEntities().forEach(ModeledEntity::tick);
    }
}
