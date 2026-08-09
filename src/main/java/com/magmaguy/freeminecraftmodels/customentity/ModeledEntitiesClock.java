package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.NMSAdapter;
import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.ModelDensity;
import com.magmaguy.freeminecraftmodels.debug.PacketDiagnostics;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Evoker;
import org.bukkit.entity.EvokerFangs;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * High-frequency model/packet clock. This is intentionally asynchronous: its packet-oriented
 * tick path has been profiled and integration-tested at scale. Do not move it to the primary
 * thread without equivalent performance and client-behavior benchmarks. Bukkit state touched by
 * model components must remain limited to the established thread-safe/read-only boundary.
 */
public class ModeledEntitiesClock {
    private static BukkitTask clock = null;
    private static BukkitTask evokerStateClock = null;
    private static BukkitTask hitboxClock = null;
    private static BukkitTask viewerStateClock = null;
    private static final int VIEWER_STATE_INTERVAL_TICKS = 4;
    private static final int PRIMARY_STATE_INTERVAL_TICKS = 2;
    private static final ReentrantReadWriteLock lifecycleLock =
            new ReentrantReadWriteLock();
    private static volatile boolean running;

    private ModeledEntitiesClock() {
    }

    public static void start() {
        shutdown();
        running = true;
        clock = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimerAsynchronously(MetadataHandler.PLUGIN, 0, 1);
        evokerStateClock = new BukkitRunnable() {
            @Override
            public void run() {
                tickEvokerState();
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
        // Registered FIRST so on ticks where both timers fire, hitbox contact
        // state is refreshed before viewer/visibility state.
        hitboxClock = new BukkitRunnable() {
            @Override
            public void run() {
                tickHitboxes();
            }
        }.runTaskTimer(
                MetadataHandler.PLUGIN,
                0,
                PRIMARY_STATE_INTERVAL_TICKS);
        viewerStateClock = new BukkitRunnable() {
            @Override
            public void run() {
                refreshViewerState();
            }
        }.runTaskTimer(
                MetadataHandler.PLUGIN,
                0,
                VIEWER_STATE_INTERVAL_TICKS);
    }

    public static void shutdown() {
        running = false;
        if (clock != null) {
            clock.cancel();
            clock = null;
        }
        if (evokerStateClock != null) {
            evokerStateClock.cancel();
            evokerStateClock = null;
        }
        if (hitboxClock != null) {
            hitboxClock.cancel();
            hitboxClock = null;
        }
        if (viewerStateClock != null) {
            viewerStateClock.cancel();
            viewerStateClock = null;
        }
        // Cancellation prevents future runs. The write barrier also waits for
        // any asynchronous tick that was already executing before callers
        // clear the live model/entity registries.
        lifecycleLock.writeLock().lock();
        try {
            // synchronization barrier only
        } finally {
            lifecycleLock.writeLock().unlock();
        }
        ModelDensity.clear();
    }

    /**
     * Runs all Evoker animation detection as one primary-thread phase. Fangs
     * are scanned once per relevant world and attributed to their actual owner,
     * instead of scheduling one task and nearby-entity scan per modeled Evoker.
     */
    private static void tickEvokerState() {
        if (!running) return;

        Map<World, Map<UUID, DynamicEntity>> evokersByWorld = new HashMap<>();
        Map<UUID, Evoker> evokersById = new HashMap<>();
        ModeledEntity.getLoadedModeledEntities().forEach(modeledEntity -> {
            if (!(modeledEntity instanceof DynamicEntity dynamicEntity)
                    || modeledEntity.isRemoved()) return;
            Evoker evoker = dynamicEntity.getEvokerForWatchdog();
            if (evoker == null) return;
            evokersByWorld
                    .computeIfAbsent(evoker.getWorld(), ignored -> new HashMap<>())
                    .put(evoker.getUniqueId(), dynamicEntity);
            evokersById.put(evoker.getUniqueId(), evoker);
        });

        for (Map.Entry<World, Map<UUID, DynamicEntity>> worldEntry : evokersByWorld.entrySet()) {
            Map<UUID, DynamicEntity> worldEvokers = worldEntry.getValue();
            Set<UUID> attackingEvokers = new HashSet<>();

            for (EvokerFangs fangs : worldEntry.getKey().getEntitiesByClass(EvokerFangs.class)) {
                Entity owner = fangs.getOwner();
                if (!(owner instanceof Evoker evoker)
                        || !worldEvokers.containsKey(evoker.getUniqueId())) continue;

                Location evokerLocation = evokersById.get(evoker.getUniqueId()).getLocation();
                Location fangsLocation = fangs.getLocation();
                if (Math.abs(evokerLocation.getX() - fangsLocation.getX()) <= 2
                        && Math.abs(evokerLocation.getY() - fangsLocation.getY()) <= 2
                        && Math.abs(evokerLocation.getZ() - fangsLocation.getZ()) <= 2) {
                    attackingEvokers.add(evoker.getUniqueId());
                }
            }

            worldEvokers.forEach((evokerId, dynamicEntity) ->
                    dynamicEntity.updateEvokerAttackState(attackingEvokers.contains(evokerId)));
        }
    }

    public static void tick() {
        lifecycleLock.readLock().lock();
        try {
            if (!running) return;
            NMSAdapter adapter = NMSManager.getAdapter();
            if (!NMSManager.isEnabled() || adapter == null) return;

            AbstractPacketBundle realBundle = adapter.createPacketBundle();

            // When the /fmm packetdebug sampler is armed, wrap the bundle so every
            // bone's move/metadata packet gets counted for this tick. Disarmed = the
            // wrapper is never created, so the normal hot path pays nothing.
            if (PacketDiagnostics.isArmed()) {
                PacketDiagnostics.CountingPacketBundle counting =
                        PacketDiagnostics.wrap(realBundle);
                int loaded = ModeledEntity.getLoadedModeledEntities().size();
                // Capture direct (unbundled) sends that happen during this tick too, so the report
                // includes packets that bypass the bundle (hitbox teleports on non-bundled paths, etc.).
                PacketDiagnostics.beginDirectCapture(counting);
                try {
                    ModeledEntity.getLoadedModeledEntities().forEach(
                            modeledEntity -> modeledEntity.tick(counting));
                    counting.send();
                } finally {
                    PacketDiagnostics.endDirectCapture();
                }
                PacketDiagnostics.endTick(counting, loaded);
                return;
            }

            ModeledEntity.getLoadedModeledEntities().forEach(
                    modeledEntity -> modeledEntity.tick(realBundle));
            realBundle.send();
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    /**
     * Refreshes contact-enabled hitboxes and fires contact callbacks on the
     * primary thread every {@value #PRIMARY_STATE_INTERVAL_TICKS} ticks.
     */
    private static void tickHitboxes() {
        if (!running) return;
        ModeledEntity.getLoadedModeledEntities().forEach(modeledEntity -> {
            if (!modeledEntity.isRemoved()) {
                modeledEntity.getHitboxComponent().tickPrimaryThread();
            }
        });
    }

    /**
     * Bukkit world, player, potion, and ray-trace state is not safe to inspect
     * from the high-frequency packet clock. Keep that clock asynchronous, but
     * refresh viewer membership and visibility on the primary thread every
     * {@value #VIEWER_STATE_INTERVAL_TICKS} ticks.
     */
    private static void refreshViewerState() {
        if (!running) return;
        ModelDensity.refresh();
        ModeledEntity.getLoadedModeledEntities().forEach(modeledEntity -> {
            if (!modeledEntity.isRemoved() &&
                    modeledEntity.getSkeleton() != null) {
                modeledEntity.getSkeleton().getSkeletonWatchers()
                        .tickPrimaryThread();
            }
        });
    }
}
