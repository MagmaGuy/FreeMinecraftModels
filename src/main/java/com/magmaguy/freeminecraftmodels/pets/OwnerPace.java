package com.magmaguy.freeminecraftmodels.pets;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Whether an owner is on the move: somewhere in the last {@link #WINDOW_TICKS} they were {@link #MOVING_BLOCKS} or more
 * from where they are now. Standing about, turning round or pottering on one spot is not moving, and they stop moving
 * once they have kept within that of one spot for the whole window. A world change starts afresh.
 */
final class OwnerPace {
    static final int SAMPLE_TICKS = 5, WINDOW_TICKS = 60;
    static final double MOVING_BLOCKS = 3D;

    private record Sample(long tick, String world, double x, double y, double z) {
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private boolean moving;

    /** Records where the owner is at this tick. */
    void sample(long tick, String world, double x, double y, double z) {
        Sample now = new Sample(tick, world, x, y, z);
        Sample last = samples.peekLast();
        if (last != null && !Objects.equals(last.world(), world)) samples.clear();
        while (!samples.isEmpty() && samples.peekFirst().tick() < tick - WINDOW_TICKS) samples.pollFirst();
        samples.addLast(now);
        moving = false;
        for (Sample before : samples) {
            double dx = before.x() - x, dy = before.y() - y, dz = before.z() - z;
            if (dx * dx + dy * dy + dz * dz >= MOVING_BLOCKS * MOVING_BLOCKS) {
                moving = true;
                break;
            }
        }
    }

    boolean moving() {
        return moving;
    }
}
