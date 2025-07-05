package com.magmaguy.freeminecraftmodels.customentity;

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
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    public static void shutdown() {
        if (clock != null) clock.cancel();
    }

    public static void tick() {
        // Create a copy of the collection before iterating
        new ArrayList<>(ModeledEntity.getLoadedModeledEntities()).forEach(ModeledEntity::tick);
    }
}
