package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.ClientFrameLatency;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

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
 *
 * <p>A player whose game runs below {@link DefaultConfig#riddenBoneUpdateIntervalSlowClientFps} frames a second is busy
 * too, whatever their load: a struggling game gains most from fewer packets. {@link ClientFrameLatency} estimates each
 * watching player's frame time from ping round trips; until it has an estimate, only the load counts.</p>
 */
public final class ViewerLoad {

    // A busy player stays busy until their load drops below this share of the threshold, so a load near the threshold
    // does not switch the interval back and forth.
    private static final double RELEASE_SHARE = 0.75;
    // A slow game stays slow until its frame rate climbs this far above the floor.
    private static final double SLOW_CLIENT_RELEASE = 1.25;

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

        int slowClientFps = DefaultConfig.riddenBoneUpdateIntervalSlowClientFps;
        Set<UUID> previouslyBusy = busyPlayers;
        Set<UUID> busy = new HashSet<>();
        loads.forEach((viewer, load) -> {
            boolean wasBusy = previouslyBusy.contains(viewer);
            boolean slowClient = false;
            if (slowClientFps > 0) {
                Player player = Bukkit.getPlayer(viewer);
                if (player != null) ClientFrameLatency.track(player);
                slowClient = isSlowClient(ClientFrameLatency.frameMillis(viewer), slowClientFps, wasBusy);
            }
            if (slowClient || isBusy(load, threshold, wasBusy)) busy.add(viewer);
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

    /** Whether a game with this estimated frame time is too slow, given whether its player was busy before. */
    static boolean isSlowClient(double frameMillis, int fps, boolean wasBusy) {
        if (Double.isNaN(frameMillis)) return false;
        return frameMillis >= 1000.0 / (wasBusy ? fps * SLOW_CLIENT_RELEASE : fps);
    }

    public static void clear() {
        busyPlayers = Set.of();
    }
}
