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
