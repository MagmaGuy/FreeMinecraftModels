package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.ClientFrameLatency;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Decides which models slow their idle and walk poses: the ones a Java player whose game is struggling watches.
 *
 * <p>Every Java player receiving bone updates is pinged, and {@link ClientFrameLatency} estimates their game's frame
 * time from the answers. A player whose game draws fewer than {@link DefaultConfig#riddenBoneUpdateIntervalSlowClientFps}
 * frames a second is slow until it climbs a quarter above that, and every model a slow player watches sends its idle
 * and walk poses every {@link DefaultConfig#riddenBoneUpdateIntervalTicks} ticks. A model sends each pose to all its
 * viewers at once, so one slow viewer slows it for everyone watching it. A game without an estimate yet keeps up. The
 * primary-thread viewer clock refreshes this after viewer lists change.</p>
 */
public final class SlowViewers {

    // A slow game stays slow until its frame rate climbs this far above the floor, so a game near the floor does not
    // switch the interval back and forth.
    private static final double RELEASE = 1.25;

    private static Set<UUID> slowPlayers = Set.of();

    private SlowViewers() {
    }

    /** Pings every Java player receiving bone updates and flags the models slow players watch. Primary thread only. */
    public static void refresh(Collection<ModeledEntity> models) {
        int floorFps = DefaultConfig.riddenBoneUpdateIntervalSlowClientFps;
        if (DefaultConfig.riddenBoneUpdateIntervalTicks <= 1 || floorFps <= 0) {
            slowPlayers = Set.of();
            for (ModeledEntity model : models) {
                Skeleton skeleton = model.getSkeleton();
                if (skeleton != null) skeleton.setWatchedBySlowPlayer(false);
            }
            return;
        }

        Set<UUID> viewers = new HashSet<>();
        for (ModeledEntity model : models) {
            Skeleton skeleton = model.getSkeleton();
            if (model.isRemoved() || skeleton == null || skeleton.getPoseBoneUpdates() <= 0) continue;
            viewers.addAll(skeleton.getSkeletonWatchers().getJavaViewers());
        }

        Set<UUID> previouslySlow = slowPlayers;
        Set<UUID> slow = new HashSet<>();
        for (UUID viewer : viewers) {
            Player player = Bukkit.getPlayer(viewer);
            if (player != null) ClientFrameLatency.track(player);
            if (isSlow(ClientFrameLatency.frameMillis(viewer), floorFps, previouslySlow.contains(viewer)))
                slow.add(viewer);
        }
        slowPlayers = slow;

        for (ModeledEntity model : models) {
            Skeleton skeleton = model.getSkeleton();
            if (skeleton == null) continue;
            boolean watchedBySlowPlayer = false;
            if (!slow.isEmpty()) {
                for (UUID viewer : skeleton.getSkeletonWatchers().getJavaViewers()) {
                    if (slow.contains(viewer)) {
                        watchedBySlowPlayer = true;
                        break;
                    }
                }
            }
            skeleton.setWatchedBySlowPlayer(watchedBySlowPlayer);
        }
    }

    /** Whether a game with this estimated frame time is slow, given whether it was slow at the last refresh. */
    static boolean isSlow(double frameMillis, int floorFps, boolean wasSlow) {
        if (Double.isNaN(frameMillis)) return false;
        return frameMillis >= 1000.0 / (wasSlow ? floorFps * RELEASE : floorFps);
    }

    public static void clear() {
        slowPlayers = Set.of();
    }
}
