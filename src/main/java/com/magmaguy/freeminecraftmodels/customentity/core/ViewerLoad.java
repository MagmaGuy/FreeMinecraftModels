package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which models slow their idle and walk poses, from how many bone updates each Java player receives.
 *
 * <p>A player's load is the bone updates per tick sent by the models they watch, counted as if every model sent
 * every tick. A player whose load reaches {@link DefaultConfig#riddenBoneUpdateIntervalLoadThreshold} is busy, and
 * every model a busy player watches sends its idle and walk poses every
 * {@link DefaultConfig#riddenBoneUpdateIntervalTicks} ticks. A model sends each pose to all its viewers at once, so one
 * busy viewer slows it for everyone watching it. The primary-thread viewer clock refreshes this after viewer lists
 * change.</p>
 */
public final class ViewerLoad {

    // A busy player stays busy until their load drops below this share of the threshold, so a load near the threshold
    // does not switch the interval back and forth.
    private static final double RELEASE_SHARE = 0.75;

    private static Set<UUID> busyPlayers = Set.of();

    private ViewerLoad() {
    }

    /** Recounts every Java player's load and flags the models busy players watch. Primary thread only. */
    public static void refresh(Collection<ModeledEntity> models) {
        int threshold = DefaultConfig.riddenBoneUpdateIntervalLoadThreshold;
        if (DefaultConfig.riddenBoneUpdateIntervalTicks <= 1 || threshold <= 0) {
            // Either nothing slows down, or everything does; no load to count.
            boolean slow = DefaultConfig.riddenBoneUpdateIntervalTicks > 1;
            busyPlayers = Set.of();
            for (ModeledEntity model : models) {
                Skeleton skeleton = model.getSkeleton();
                if (skeleton != null) skeleton.setWatchedByBusyPlayer(slow);
            }
            return;
        }

        Map<UUID, Integer> loads = new HashMap<>();
        for (ModeledEntity model : models) {
            Skeleton skeleton = model.getSkeleton();
            if (model.isRemoved() || skeleton == null) continue;
            int updates = skeleton.getPoseBoneUpdates();
            if (updates <= 0) continue;
            for (UUID viewer : skeleton.getSkeletonWatchers().getJavaViewers())
                loads.merge(viewer, updates, Integer::sum);
        }

        Set<UUID> previouslyBusy = busyPlayers;
        Set<UUID> busy = new HashSet<>();
        loads.forEach((player, load) -> {
            if (isBusy(load, threshold, previouslyBusy.contains(player))) busy.add(player);
        });
        busyPlayers = busy;

        for (ModeledEntity model : models) {
            Skeleton skeleton = model.getSkeleton();
            if (skeleton == null) continue;
            boolean watchedByBusyPlayer = false;
            if (!busy.isEmpty()) {
                for (UUID viewer : skeleton.getSkeletonWatchers().getJavaViewers()) {
                    if (busy.contains(viewer)) {
                        watchedByBusyPlayer = true;
                        break;
                    }
                }
            }
            skeleton.setWatchedByBusyPlayer(watchedByBusyPlayer);
        }
    }

    /** Whether a player with this load is busy, given whether they were busy at the last refresh. */
    static boolean isBusy(int load, int threshold, boolean wasBusy) {
        return wasBusy ? load >= Math.ceil(threshold * RELEASE_SHARE) : load >= threshold;
    }

    public static void clear() {
        busyPlayers = Set.of();
    }
}
