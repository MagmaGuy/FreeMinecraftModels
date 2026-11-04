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

    /** The front's pitch in degrees, up positive, for a model facing south. */
    private static double pitch(Quaterniond rotation) {
        return rotation == null ? 0D : Math.toDegrees(Math.asin(rotation.transform(new Vector3d(0, 0, 1)).y));
    }

    @Test
    void anExactSwimPitchesInStepWithItsWave() {
        // A penguin swimming south at 0.1 blocks per tick on a 0.5-block wave every 40 ticks, its mover reporting
        // its velocity: the nose points up most while it rises fastest, is level at the crest and points down most
        // while it dips fastest, so it glides along its wave rather than rocking behind it.
        TravelOrientation swim = new TravelOrientation(TravelOrientation.Style.FRONT_FIRST, 0.3D, 2.5D, 35D, 120D);
        double omega = Math.PI * 2D / 40D;
        double fastestRise = 0D, crest = 0D, fastestDip = 0D, highest = 0D;
        for (int tick = 0; tick < 200; tick++) {
            double climb = 0.25D * omega * Math.cos(omega * tick);
            z += 0.1D;
            y += climb;
            double pitch = pitch(tilt.step(x, y, z, new Vector3d(0D, climb, 0.1D), 0F, swim, true));
            if (tick < 120) continue;
            int phase = tick % 40;
            if (phase == 0) fastestRise = pitch;
            if (phase == 10) crest = pitch;
            if (phase == 20) fastestDip = pitch;
            highest = Math.max(highest, pitch);
        }
        assertTrue(highest > 12D, "the nose rises with the wave: at most " + highest);
        assertEquals(highest, fastestRise, 1.5D, "the nose points up most while it rises fastest");
        assertEquals(0D, crest, 2D, "level at the crest");
        assertEquals(-highest, fastestDip, 1.5D, "and points down most while it dips fastest");
    }

    @Test
    void anExactSwimStillTurnsNoFasterThanItsTurnSpeed() {
        TravelOrientation slow = new TravelOrientation(TravelOrientation.Style.FRONT_FIRST, 0.3D, 2.5D, 35D, 40D);
        double previous = 0D;
        for (int tick = 0; tick < 30; tick++) {
            z += 0.1D;
            y += 0.1D;
            double pitch = pitch(tilt.step(x, y, z, new Vector3d(0D, 0.1D, 0.1D), 0F, slow, true));
            assertTrue(pitch - previous <= 2D + 1.0E-6D, "turned " + (pitch - previous) + " degrees in a tick");
            previous = pitch;
        }
        assertEquals(35D, previous, 0.5D, "and reaches its lean");
    }
}
