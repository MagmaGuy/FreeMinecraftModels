package com.magmaguy.freeminecraftmodels.customentity.core;

import org.bukkit.util.Vector;

import java.util.Objects;

/**
 * Traverses non-occluding block cells while preserving a strict upper bound on
 * the number of transparent blocks crossed.
 */
final class BlockVisibilityRay {
    private static final double EXIT_EPSILON = 1.0E-4;
    private static final double MIN_DISTANCE_SQUARED = 1.0E-18;

    private BlockVisibilityRay() {
    }

    static boolean isVisible(Vector origin,
                             Vector target,
                             int maxTransparentBlocks,
                             Tracer tracer) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(tracer, "tracer");
        if (maxTransparentBlocks < 0) {
            throw new IllegalArgumentException("maxTransparentBlocks must be non-negative");
        }

        Vector currentOrigin = origin.clone();
        int transparentBlocksCrossed = 0;

        while (true) {
            Vector toTarget = target.clone().subtract(currentOrigin);
            double distanceSquared = toTarget.lengthSquared();
            if (distanceSquared <= MIN_DISTANCE_SQUARED) return true;

            double distance = Math.sqrt(distanceSquared);
            Vector direction = toTarget.multiply(1.0 / distance);
            Hit hit = tracer.trace(currentOrigin.clone(), direction.clone(), distance);

            if (hit == null) return true;
            if (hit.occluding()) return false;
            if (transparentBlocksCrossed >= maxTransparentBlocks) return false;

            Vector nextOrigin = advancePastBlockCell(hit, direction);
            double forwardProgress = nextOrigin.clone().subtract(currentOrigin).dot(direction);
            if (forwardProgress <= 0.0) return false;

            // The target lies within the transparent cell that was hit. Crossing
            // the cell boundary would move beyond it, so the point is visible.
            if (forwardProgress >= distance) return true;

            currentOrigin = nextOrigin;
            transparentBlocksCrossed++;
        }
    }

    private static Vector advancePastBlockCell(Hit hit, Vector normalizedDirection) {
        Objects.requireNonNull(hit, "hit");
        Objects.requireNonNull(normalizedDirection, "normalizedDirection");

        Vector hitPosition = hit.position();
        double exitDistance = Math.min(
                axisExitDistance(hitPosition.getX(), hit.blockX(), normalizedDirection.getX()),
                Math.min(
                        axisExitDistance(hitPosition.getY(), hit.blockY(), normalizedDirection.getY()),
                        axisExitDistance(hitPosition.getZ(), hit.blockZ(), normalizedDirection.getZ())
                )
        );
        if (!Double.isFinite(exitDistance)) {
            throw new IllegalArgumentException("Ray direction must have a non-zero component");
        }

        // hitPosition is already a private copy (Hit#position() clones), so it can
        // be mutated in place; the direction still needs a defensive clone because
        // the caller reuses it after this returns.
        return hitPosition.add(
                normalizedDirection.clone().multiply(exitDistance + EXIT_EPSILON)
        );
    }

    private static double axisExitDistance(double coordinate, int blockCoordinate, double direction) {
        if (direction == 0.0) return Double.POSITIVE_INFINITY;

        double exitBoundary = direction > 0.0 ? blockCoordinate + 1.0 : blockCoordinate;
        double distance = (exitBoundary - coordinate) / direction;
        if (distance < -EXIT_EPSILON) return Double.POSITIVE_INFINITY;
        return Math.max(0.0, distance);
    }

    @FunctionalInterface
    interface Tracer {
        Hit trace(Vector origin, Vector normalizedDirection, double maxDistance);
    }

    record Hit(Vector position, int blockX, int blockY, int blockZ, boolean occluding) {
        Hit {
            Objects.requireNonNull(position, "position");
            position = position.clone();
        }

        @Override
        public Vector position() {
            return position.clone();
        }
    }
}
