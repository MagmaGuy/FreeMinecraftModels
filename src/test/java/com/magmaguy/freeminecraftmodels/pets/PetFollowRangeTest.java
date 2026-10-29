package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetFollowRangeTest {
    /** Samples an owner every SAMPLE_TICKS from tick start, at x = step(tick). */
    private static void walk(OwnerPace pace, long start, long end, java.util.function.LongToDoubleFunction x) {
        for (long tick = start; tick <= end; tick += OwnerPace.SAMPLE_TICKS) pace.sample(tick, "world", x.applyAsDouble(tick), 64, 0);
    }

    @Test
    void standingAboutIsNotMoving() {
        OwnerPace pace = new OwnerPace();
        walk(pace, 0, 200, tick -> 0D);
        assertFalse(pace.moving());
    }

    @Test
    void potteringOnOneSpotIsNotMoving() {
        OwnerPace pace = new OwnerPace();
        // Back and forth across two blocks.
        walk(pace, 0, 200, tick -> tick / 10 % 2 == 0 ? 0D : 2D);
        assertFalse(pace.moving());
    }

    @Test
    void walkingIsMovingUntilTheOwnerHasStoodStillForTheWindow() {
        OwnerPace pace = new OwnerPace();
        // Walking at 4.3 blocks a second for two seconds.
        walk(pace, 0, 40, tick -> tick * 0.215D);
        assertTrue(pace.moving());
        // Stopped at tick 40. The last spot 3 blocks back is tick 25's, which leaves the window after tick 85.
        double stopped = 40 * 0.215D;
        walk(pace, 45, 85, tick -> stopped);
        assertTrue(pace.moving(), "still on the move while a spot 3 blocks back is in the window");
        walk(pace, 90, 90, tick -> stopped);
        assertFalse(pace.moving());
    }

    @Test
    void aWorldChangeStartsAfresh() {
        OwnerPace pace = new OwnerPace();
        pace.sample(0, "world", 0, 64, 0);
        pace.sample(5, "world_nether", 100, 64, 0);
        assertFalse(pace.moving());
    }

    @Test
    void theRoamRangeWidensWithTheFollowRange() {
        // On the move (follow distance 5): 0.8 of it, under the roam radius.
        assertEquals(4D, PetControl.roamRange(5D, 5D), 1e-9);
        // Standing about (idle distance 20): 0.6 of it.
        assertEquals(12D, PetControl.roamRange(5D, 20D), 1e-9);
        // A roam radius larger than 0.6 of the range, still kept to 0.8 of it.
        assertEquals(14D, PetControl.roamRange(14D, 20D), 1e-9);
        assertEquals(16D, PetControl.roamRange(30D, 20D), 1e-9);
    }

    @Test
    void theIdleDistanceStaysBetweenTheFollowAndTeleportDistances() {
        assertEquals(20D, PetSpecies.idleDistance(5D, 20D, 40D), 1e-9);
        assertEquals(23D, PetSpecies.idleDistance(5D, 30D, 24D), 1e-9);
        assertEquals(5D, PetSpecies.idleDistance(5D, 2D, 40D), 1e-9);
    }

    @Test
    void behindIsOppositeTheFacing() {
        // Yaw 0 faces south (+z), so behind is north.
        Location south = new Location(null, 0, 64, 0, 0F, 0F);
        Location behind = PetMotion.behind(south, 4D);
        assertEquals(0D, behind.getX(), 1e-9);
        assertEquals(-4D, behind.getZ(), 1e-9);
        // Yaw 90 faces west (-x), so behind is east; looking straight down changes nothing.
        Location west = PetMotion.behind(new Location(null, 0, 64, 0, 90F, 90F), 4D);
        assertEquals(4D, west.getX(), 1e-9);
        assertEquals(0D, west.getZ(), 1e-9);
        assertEquals(64D, west.getY(), 1e-9);
    }
}
