package com.magmaguy.freeminecraftmodels.customentity.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlowViewersTest {

    @Test
    void gameBelowTheFloorIsSlow() {
        assertTrue(SlowViewers.isSlow(40, 30, false));
        assertFalse(SlowViewers.isSlow(30, 30, false));
    }

    @Test
    void slowGameStaysSlowUntilAQuarterAboveTheFloor() {
        // 30 ms is 33 frames a second: fast enough to stay out, too slow to leave.
        assertTrue(SlowViewers.isSlow(30, 30, true));
        assertFalse(SlowViewers.isSlow(25, 30, true));
    }

    @Test
    void gameWithoutAnEstimateKeepsUp() {
        assertFalse(SlowViewers.isSlow(Double.NaN, 30, true));
    }
}
