package com.magmaguy.freeminecraftmodels.customentity.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/** Temporary check for turning inertia: braking without overshoot, size scaling, the vanilla cap and wrapping. */
@EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TEMP_TICKET_TESTS", matches = "true")
class TurnInertiaTemporaryTest {
    private static final double CAP = 30;
    private static final double ACCELERATION = 6;

    @Test
    void aHalfTurnSpinsUpCruisesAndBrakesOntoTheTargetWithoutOvershoot() {
        int ticks = ticksToTurn(0, 179, 1);
        // Continuous profile: 179/30 + 30/6 = 11 ticks.
        assertTrue(ticks >= 10 && ticks <= 13, "A one-block model turned 179 degrees in " + ticks + " ticks");
    }

    @Test
    void biggerModelsTurnSlowerBySquareRootOfSize() {
        int small = ticksToTurn(0, 179, 1);
        int big = ticksToTurn(0, 179, 4);
        double ratio = big / (double) small;
        assertTrue(ratio > 1.7 && ratio < 2.3, "size 4 took " + big + " ticks against " + small);
    }

    @Test
    void tinyModelsNeverTurnFasterThanVanilla() {
        TurnInertia inertia = new TurnInertia();
        inertia.reset(0);
        float previous = inertia.getYaw();
        for (int tick = 0; tick < 40; tick++) {
            float yaw = inertia.step(179, 0.01, 1000, 1000);
            assertTrue(Math.abs(TurnInertia.wrap(yaw - previous)) <= 90.0001, "step " + (yaw - previous));
            previous = yaw;
        }
        assertEquals(179, previous, 1e-4);
    }

    @Test
    void turnsTheShortWayAcrossTheSeam() {
        TurnInertia inertia = new TurnInertia();
        inertia.reset(170);
        for (int tick = 0; tick < 40; tick++) {
            float yaw = inertia.step(-170, 1, CAP, ACCELERATION);
            assertTrue(Math.abs(yaw) >= 165, "Crossed through the long way at " + yaw);
        }
        assertEquals(-170, inertia.getYaw(), 1e-4);
    }

    @Test
    void resetFacesTheTargetAtOnce() {
        TurnInertia inertia = new TurnInertia();
        inertia.reset(0);
        inertia.step(120, 1, CAP, ACCELERATION);
        inertia.reset(120);
        assertEquals(120, inertia.getYaw(), 1e-4);
        assertEquals(120, inertia.step(120, 1, CAP, ACCELERATION), 1e-4, "No leftover speed after a reset");
    }

    private static int ticksToTurn(float from, float target, double size) {
        TurnInertia inertia = new TurnInertia();
        inertia.reset(from);
        double previousRemaining = Math.abs(TurnInertia.wrap(target - from));
        for (int tick = 1; tick <= 200; tick++) {
            float yaw = inertia.step(target, size, CAP, ACCELERATION);
            double remaining = Math.abs(TurnInertia.wrap(target - yaw));
            assertTrue(remaining <= previousRemaining + 1e-4, "Overshot or turned back at tick " + tick);
            if (remaining < 1e-4) {
                // Braked onto the target: the last step is no bigger than one tick of deceleration.
                assertTrue(previousRemaining <= ACCELERATION / size + 1e-3,
                        "Landed from " + previousRemaining + " degrees away");
                return tick;
            }
            previousRemaining = remaining;
        }
        fail("Never reached the target");
        return -1;
    }
}
