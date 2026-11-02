package com.magmaguy.freeminecraftmodels.customentity.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trying the point a player last saw a model by first must not change whether the model is in sight: for every
 * combination of visible points and every remembered point, some point is found exactly when one is visible, and no
 * point is traced twice.
 */
class SightPointOrderTest {
    @Test
    void theRememberedPointChangesOnlyTheOrder() {
        int points = SkeletonWatchers.SIGHT_POINTS;
        assertEquals(5, points);
        for (int mask = 0; mask < 1 << points; mask++) {
            for (int remembered = -1; remembered < points; remembered++) {
                int visibleMask = mask;
                List<Integer> traced = new ArrayList<>();
                int found = SkeletonWatchers.firstVisiblePoint(remembered < 0 ? null : remembered, point -> {
                    traced.add(point);
                    return (visibleMask & 1 << point) != 0;
                });
                String label = "visible " + Integer.toBinaryString(mask) + ", remembered " + remembered;
                assertEquals(mask != 0, found >= 0, label);
                if (found >= 0) assertTrue((mask & 1 << found) != 0, label);
                assertEquals(traced.size(), traced.stream().distinct().count(), label);
                if (remembered >= 0) assertEquals(remembered, traced.get(0), label);
                if (remembered >= 0 && (mask & 1 << remembered) != 0) assertEquals(1, traced.size(), label);
                if (mask == 0) assertEquals(points, traced.size(), label);
            }
        }
    }

    @Test
    void withoutARememberedPointTheCenterGoesFirst() {
        List<Integer> traced = new ArrayList<>();
        assertEquals(-1, SkeletonWatchers.firstVisiblePoint(null, point -> traced.add(point) && false));
        assertEquals(List.of(0, 1, 2, 3, 4), traced);
    }
}
