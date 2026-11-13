package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * The footprints a march's leader leaves, newest last, which its followers walk in. Past the oldest footprint the trail
 * runs on straight, opposite the way the march first set off, so a line can form behind the leader before it moves.
 */
final class MarchTrail {
    /** A footprint is kept each time the leader is this far from the last one. */
    static final double FOOTPRINT = 0.25D;
    /** A body this far above or below a footprint, or nearer, is a step from it at most: a block, and a fall in hand. */
    static final double STEP = 1.25D;
    private final List<Vector> footprints = new ArrayList<>();
    private final double tailX, tailZ;

    /** A trail that starts where the march is called, its virtual tail opposite the unit heading (dx, dz). */
    MarchTrail(Vector start, double dx, double dz) {
        footprints.add(start.clone());
        tailX = -dx;
        tailZ = -dz;
    }

    /**
     * Keeps a footprint where the leader is, once it is {@link #FOOTPRINT} from the last one, and drops the oldest past
     * {@code keep} blocks of trail.
     */
    void step(Vector at, double keep) {
        if (at.distanceSquared(head()) < FOOTPRINT * FOOTPRINT) return;
        footprints.add(at.clone());
        double length = 0D;
        for (int index = footprints.size() - 1; index > 0; index--) {
            length += footprints.get(index).distance(footprints.get(index - 1));
            if (length >= keep) {
                footprints.subList(0, index - 1).clear();
                return;
            }
        }
    }

    /** The newest footprint. */
    Vector head() {
        return footprints.get(footprints.size() - 1);
    }

    /**
     * Where on the trail a body at {@code at} is, as blocks back from the newest footprint: going back from the
     * point {@code from} blocks back, the first place the trail comes within {@code within} blocks of it across and
     * {@link #STEP} of its height, at the nearest point of that pass. So where the trail doubles back on itself, a
     * body beside both stretches is on the one that leads to {@code from} soonest. -1 when the trail from there back
     * never comes that near, or has no footprint that far back: the straight run it goes on with past its oldest
     * footprint is none.
     */
    double nearest(Vector at, double from, double within) {
        double best = Double.MAX_VALUE, bestBack = -1D, back = 0D;
        Vector previous = head();
        for (int index = footprints.size() - 2; index >= 0; index--) {
            Vector next = footprints.get(index);
            double length = previous.distance(next);
            if (length > 0D && back + length >= from) {
                // Of this stretch between two footprints, the part that lies from blocks back or more.
                double least = Math.max(0D, (from - back) / length);
                double dx = next.getX() - previous.getX(), dz = next.getZ() - previous.getZ(), flat = dx * dx + dz * dz;
                double share = flat == 0D ? least
                        : ((at.getX() - previous.getX()) * dx + (at.getZ() - previous.getZ()) * dz) / flat;
                share = Math.max(least, Math.min(1D, share));
                double offX = previous.getX() + dx * share - at.getX(), offZ = previous.getZ() + dz * share - at.getZ();
                double off = offX * offX + offZ * offZ;
                double y = previous.getY() + (next.getY() - previous.getY()) * share;
                boolean near = off <= within * within && Math.abs(y - at.getY()) <= STEP;
                if (near && off < best) {
                    best = off;
                    bestBack = back + length * share;
                } else if (bestBack >= 0D && off > best + 1.0E-9D) {
                    // Past the nearest point of this pass: a later one, further back, is another stretch.
                    return bestBack;
                }
            }
            back += length;
            previous = next;
        }
        return bestBack;
    }

    /**
     * The point on the trail {@code across} blocks further on, toward the newest footprint, than the one
     * {@code back} blocks back, but no further on than the one {@code least} blocks back. The blocks further on are
     * counted across the ground: where the leader rose or dropped a step, two footprints lie a block apart one
     * over the other, and a point a block on by the trail's own length would be the top of that step, no way on at
     * all. The point {@code least} blocks back when {@code back} lies past the oldest footprint.
     */
    Vector ahead(double back, double across, double least) {
        // The stretch between two footprints the point back blocks back lies on, and that point.
        Vector point = null;
        int newer = footprints.size() - 1;
        double left = back;
        for (int index = footprints.size() - 2; index >= 0 && point == null; index--) {
            Vector from = footprints.get(index + 1), to = footprints.get(index);
            double length = from.distance(to);
            if (length >= left && length > 0D) {
                point = between(from, to, left / length);
                newer = index + 1;
            } else left -= length;
        }
        if (point == null) return behind(least);
        // From there toward the newest footprint, until it has covered across blocks of ground or come to least.
        double room = back - least, go = across;
        for (int index = newer; index < footprints.size(); index++) {
            Vector next = footprints.get(index);
            double length = point.distance(next);
            double flat = Math.hypot(next.getX() - point.getX(), next.getZ() - point.getZ());
            double share = Math.min(length > 0D ? room / length : 0D, flat > 0D ? go / flat : Double.MAX_VALUE);
            if (share <= 1D) return between(point, next, Math.max(0D, share));
            room -= length;
            go -= flat;
            point = next;
        }
        return point.clone();
    }

    private static Vector between(Vector from, Vector to, double share) {
        return new Vector(from.getX() + (to.getX() - from.getX()) * share, from.getY() + (to.getY() - from.getY()) * share,
                from.getZ() + (to.getZ() - from.getZ()) * share);
    }

    /** The point {@code distance} blocks back along the trail from its newest footprint. */
    Vector behind(double distance) {
        double left = distance;
        Vector previous = head();
        for (int index = footprints.size() - 2; index >= 0; index--) {
            Vector next = footprints.get(index);
            double length = previous.distance(next);
            if (length >= left && length > 0D) {
                double share = left / length;
                return new Vector(previous.getX() + (next.getX() - previous.getX()) * share,
                        previous.getY() + (next.getY() - previous.getY()) * share,
                        previous.getZ() + (next.getZ() - previous.getZ()) * share);
            }
            left -= length;
            previous = next;
        }
        return new Vector(previous.getX() + tailX * left, previous.getY(), previous.getZ() + tailZ * left);
    }
}
