package com.magmaguy.freeminecraftmodels.customentity.core;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Travel tilt: a model leans with its travel once it moves fast enough, never turns faster than its turn speed,
 * and eases back upright when it stops. Top-first models point their top along the travel; front-first models
 * pitch their front into climbs and dives and bank into turns.
 */
class TravelTiltTest {
    private static final TravelOrientation TOP_FIRST =
            new TravelOrientation(TravelOrientation.Style.TOP_FIRST, 0.6D, 4D, 180D, 180D);
    private static final TravelOrientation FRONT_FIRST =
            new TravelOrientation(TravelOrientation.Style.FRONT_FIRST, 0.6D, 4D, 180D, 180D);

    private final TravelTilt tilt = new TravelTilt();
    private double x, y, z;
    private Quaterniond last;

    /** Moves the body by the given blocks per tick for a number of ticks. */
    private Quaterniond travel(double dx, double dy, double dz, int ticks, float yaw, TravelOrientation settings) {
        for (int tick = 0; tick < ticks; tick++) {
            x += dx;
            y += dy;
            z += dz;
            last = tilt.step(x, y, z, yaw, settings, true);
        }
        return last;
    }

    private static Vector3d top(Quaterniond rotation) {
        return rotation == null ? new Vector3d(0, 1, 0) : rotation.transform(new Vector3d(0, 1, 0));
    }

    @Test
    void aModelAtRestStaysUpright() {
        assertNull(travel(0, 0, 0, 100, 0F, TOP_FIRST));
    }

    @Test
    void driftingBelowTheStartSpeedStaysUpright() {
        // 0.5 blocks per second.
        assertNull(travel(0.025D, 0, 0, 200, 0F, TOP_FIRST));
    }

    @Test
    void topFirstLaysItsTopIntoHorizontalTravel() {
        Vector3d top = top(travel(0.25D, 0, 0, 120, 0F, TOP_FIRST));
        assertEquals(1D, top.x, 0.02D, "the top points along the travel");
        assertEquals(0D, top.y, 0.05D);
    }

    @Test
    void topFirstTurnsUpsideDownToDive() {
        Vector3d top = top(travel(0, -0.25D, 0, 160, 0F, TOP_FIRST));
        assertEquals(-1D, top.y, 0.02D, "a dive turns the model over");
    }

    @Test
    void climbingStraightUpStaysUpright() {
        assertNull(travel(0, 0.25D, 0, 100, 0F, TOP_FIRST));
    }

    @Test
    void slowTravelLeansOnlyPartway() {
        // 2 blocks per second, between the start and full speeds.
        Vector3d top = top(travel(0.1D, 0, 0, 200, 0F, TOP_FIRST));
        double degrees = Math.toDegrees(Math.acos(top.y));
        assertTrue(degrees > 5D && degrees < 80D, "leans " + degrees + " degrees");
    }

    @Test
    void theTiltNeverTurnsFasterThanItsTurnSpeed() {
        TravelOrientation slow = new TravelOrientation(TravelOrientation.Style.TOP_FIRST, 0.6D, 4D, 180D, 40D);
        Quaterniond previous = new Quaterniond();
        for (int tick = 0; tick < 60; tick++) {
            Quaterniond now = travel(0.25D, 0, 0, 1, 0F, slow);
            Quaterniond current = now == null ? new Quaterniond() : now;
            double turned = Math.toDegrees(2D * Math.acos(Math.min(1D, Math.abs(previous.dot(current)))));
            assertTrue(turned <= 2D + 1.0E-6D, "turned " + turned + " degrees in one tick");
            previous = current;
        }
    }

    @Test
    void stoppingEasesBackUpright() {
        travel(0.25D, 0, 0, 120, 0F, TOP_FIRST);
        Quaterniond halfway = travel(0, 0, 0, 10, 0F, TOP_FIRST);
        assertNotNull(halfway, "it doesn't snap upright");
        assertNull(travel(0, 0, 0, 200, 0F, TOP_FIRST), "but it gets there");
    }

    @Test
    void aTeleportIsNotTravel() {
        travel(0, 0, 0, 5, 0F, TOP_FIRST);
        assertNull(travel(30D, 0, 0, 1, 0F, TOP_FIRST));
        assertNull(travel(0, 0, 0, 30, 0F, TOP_FIRST));
    }

    @Test
    void anUnfreeModelEasesUpright() {
        travel(0.25D, 0, 0, 120, 0F, TOP_FIRST);
        Quaterniond result = null;
        for (int tick = 0; tick < 200; tick++) {
            x += 0.25D;
            result = tilt.step(x, y, z, 0F, TOP_FIRST, false);
        }
        assertNull(result);
    }

    @Test
    void uprightNeverLeans() {
        assertNull(travel(0, -0.25D, 0.25D, 100, 0F, TravelOrientation.UPRIGHT));
    }

    @Test
    void maxTiltCapsTheLean() {
        TravelOrientation capped = new TravelOrientation(TravelOrientation.Style.TOP_FIRST, 0.6D, 4D, 30D, 180D);
        Vector3d top = top(travel(0.25D, 0, 0, 120, 0F, capped));
        assertEquals(30D, Math.toDegrees(Math.acos(top.y)), 0.5D);
    }

    @Test
    void frontFirstPitchesIntoAClimb() {
        // Flying south (+z) and climbing at 45 degrees.
        Quaterniond rotation = travel(0, 0.2D, 0.2D, 120, 0F, FRONT_FIRST);
        Vector3d front = rotation.transform(new Vector3d(0, 0, 1));
        assertEquals(45D, Math.toDegrees(Math.asin(front.y)), 1D, "the front points up the climb");
    }

    @Test
    void frontFirstBanksIntoARightTurn() {
        // Flying south while the model turns right (yaw rising toward west).
        float yaw = 0F;
        Quaterniond rotation = null;
        for (int tick = 0; tick < 60; tick++) {
            z += 0.25D;
            yaw += 2F;
            rotation = tilt.step(x, y, z, yaw, FRONT_FIRST, true);
        }
        assertNotNull(rotation);
        // Facing south, the right hand points west (-x): the top rolls that way.
        assertTrue(top(rotation).x < -0.2D, "top " + top(rotation));
    }

    @Test
    void frontFirstLevelFlightStaysLevel() {
        assertNull(travel(0, 0, 0.25D, 120, 0F, FRONT_FIRST));
    }
}
