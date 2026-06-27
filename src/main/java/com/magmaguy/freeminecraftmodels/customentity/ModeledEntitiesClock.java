package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.debug.PacketDiagnostics;
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

        // Refresh the per-world model-density snapshot before models tick, so each
        // SkeletonWatchers.updateWatcherList sees a consistent count this tick.
        com.magmaguy.freeminecraftmodels.customentity.core.ModelDensity.refresh();

        AbstractPacketBundle realBundle = adapter.createPacketBundle();

        // When the /fmm packetdebug sampler is armed, wrap the bundle so every
        // bone's move/metadata packet gets counted for this tick. Disarmed = the
        // wrapper is never created, so the normal hot path pays nothing.
        if (PacketDiagnostics.isArmed()) {
            PacketDiagnostics.CountingPacketBundle counting = PacketDiagnostics.wrap(realBundle);
            int loaded = ModeledEntity.getLoadedModeledEntities().size();
            // Capture direct (unbundled) sends that happen during this tick too, so the report
            // includes packets that bypass the bundle (hitbox teleports on non-bundled paths, etc.).
            PacketDiagnostics.beginDirectCapture(counting);
            try {
                ModeledEntity.getLoadedModeledEntities().forEach(modeledEntity -> modeledEntity.tick(counting));
                counting.send();
            } finally {
                PacketDiagnostics.endDirectCapture();
            }
            PacketDiagnostics.endTick(counting, loaded);
            return;
        }

        ModeledEntity.getLoadedModeledEntities().forEach(modeledEntity -> modeledEntity.tick(realBundle));
        realBundle.send();
    }
}
