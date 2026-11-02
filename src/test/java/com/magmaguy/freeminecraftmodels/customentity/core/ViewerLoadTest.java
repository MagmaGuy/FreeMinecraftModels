package com.magmaguy.freeminecraftmodels.customentity.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewerLoadTest {

    @Test
    void gameBelowTheFloorIsSlow() {
        assertTrue(ViewerLoad.isSlowClient(40, 30, false));
        assertFalse(ViewerLoad.isSlowClient(30, 30, false));
    }

    @Test
    void slowGameStaysSlowUntilAQuarterAboveTheFloor() {
        // 30 ms is 33 frames a second: fast enough to stay out, too slow to leave.
        assertTrue(ViewerLoad.isSlowClient(30, 30, true));
        assertFalse(ViewerLoad.isSlowClient(25, 30, true));
    }

    @Test
    void unknownGameIsNotSlow() {
        assertFalse(ViewerLoad.isSlowClient(Double.NaN, 30, true));
    }

    @Test
    void loadThresholdHasItsOwnMargin() {
        assertTrue(ViewerLoad.isBusy(640, 640, false));
        assertFalse(ViewerLoad.isBusy(639, 640, false));
        assertTrue(ViewerLoad.isBusy(480, 640, true));
        assertFalse(ViewerLoad.isBusy(479, 640, true));
    }
}
