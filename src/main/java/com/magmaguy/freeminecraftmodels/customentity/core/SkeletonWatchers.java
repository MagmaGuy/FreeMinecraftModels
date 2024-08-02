package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.CopyOnWriteArraySet;

public class SkeletonWatchers implements Listener {
    private final Skeleton skeleton;
    private final Set<UUID> viewers = new CopyOnWriteArraySet<>();
    private BukkitTask tick;

    public SkeletonWatchers(Skeleton skeleton) {
        this.skeleton = skeleton;
        tick();
    }

    public void remove() {
        tick.cancel();
    }

    private void tick() {
        tick = new BukkitRunnable() {
            @Override
            public void run() {
                updateWatcherList();
                // sendPackets(); moved to the bone
            }
        }.runTaskTimerAsynchronously(MetadataHandler.PLUGIN, 0, 1);
    }

    private void updateWatcherList() {
        List<UUID> newPlayers = new ArrayList<>();
        for (Player player : skeleton.getCurrentLocation().getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(skeleton.getCurrentLocation()) < Math.pow(2, Bukkit.getSimulationDistance() * 16D)) {
                newPlayers.add(player.getUniqueId());
                if (!viewers.contains(player.getUniqueId())) {
                    displayTo(player);
                }
            }
        }

        List<UUID> toRemove = new ArrayList<>();
        for (UUID viewer : viewers) {
            if (!newPlayers.contains(viewer)) {
                toRemove.add(viewer);
            }
        }
        viewers.removeAll(toRemove);
        toRemove.forEach(this::hideFrom);
    }

    private void displayTo(Player player) {
        viewers.add(player.getUniqueId());
        skeleton.getBones().forEach(bone -> bone.displayTo(player));
    }

    private void hideFrom(UUID uuid) {
        viewers.remove(uuid);
        skeleton.getBones().forEach(bone -> bone.hideFrom(uuid));
    }

    public void reset() {
        Set<UUID> tempViewers = Collections.synchronizedSet(new HashSet<>(viewers));
        tempViewers.forEach(viewer -> {
            hideFrom(viewer);
            displayTo(Bukkit.getPlayer(viewer));
        });
    }

    public void sendPackets(Bone bone) {
        if (viewers.isEmpty()) return;
        bone.sendUpdatePacket();
    }
}
