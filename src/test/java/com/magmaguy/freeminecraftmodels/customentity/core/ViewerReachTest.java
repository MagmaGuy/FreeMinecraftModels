package com.magmaguy.freeminecraftmodels.customentity.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A player is admitted inside the view distance and kept a little past it, and the ridden body's tracking range must
 * cover both: the body gate admits 3 blocks inside the range and keeps to 1 block inside it.
 */
class ViewerReachTest {
    private static final double BODY_ADMIT_MARGIN = 3;
    private static final double BODY_KEEP_MARGIN = 1;

    @Test
    void aViewerIsKeptPastTheDistanceItWasAdmittedAt() {
        assertEquals(60, SkeletonWatchers.reachFor(60, false));
        assertEquals(64, SkeletonWatchers.reachFor(60, true));
    }

    @Test
    void theBodyIsTrackedPastWhereEveryViewerIsKept() {
        for (int viewDistance : new int[]{10, 32, 60, 128, 256}) {
            int range = SkeletonWatchers.bodyTrackingRangeFor(viewDistance);
            assertTrue(SkeletonWatchers.reachFor(viewDistance, false) <= range - BODY_ADMIT_MARGIN,
                    "a new viewer inside " + viewDistance + " blocks passes the body gate");
            assertTrue(SkeletonWatchers.reachFor(viewDistance, true) <= range - BODY_KEEP_MARGIN,
                    "a viewer kept past " + viewDistance + " blocks stays inside the body gate");
        }
        assertEquals(66, SkeletonWatchers.bodyTrackingRangeFor(60));
    }
}
