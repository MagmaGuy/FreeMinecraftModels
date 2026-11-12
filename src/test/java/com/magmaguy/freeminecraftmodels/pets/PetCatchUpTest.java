package com.magmaguy.freeminecraftmodels.pets;

import org.junit.jupiter.api.Test;

import static com.magmaguy.freeminecraftmodels.pets.PetCatchUp.NEVER;
import static com.magmaguy.freeminecraftmodels.pets.PetCatchUp.Verdict.BRING;
import static com.magmaguy.freeminecraftmodels.pets.PetCatchUp.Verdict.NEAR;
import static com.magmaguy.freeminecraftmodels.pets.PetCatchUp.Verdict.WAIT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** When the watchdog brings a pet that fell behind to its owner, and when it leaves the pet to its own behaviors. */
class PetCatchUpTest {
    private static final double TELEPORT = 40D;

    @Test
    void aPetWithinItsTeleportDistanceIsLeftAlone() {
        assertEquals(NEAR, PetCatchUp.verdict(true, 12D, TELEPORT, false, false, NEVER, 1000L));
        assertEquals(NEAR, PetCatchUp.verdict(true, TELEPORT, TELEPORT, false, false, NEVER, 1000L));
        // One that came back within it by itself is forgotten, however long it had been far.
        assertEquals(NEAR, PetCatchUp.verdict(true, 12D, TELEPORT, false, false, 100L, 1000L));
        // A body that does not tick but is near is nobody's worry.
        assertEquals(NEAR, PetCatchUp.verdict(true, 12D, TELEPORT, false, true, NEVER, 1000L));
    }

    @Test
    void aPetFirstSeenFarGetsOneWatchdogPeriodToCatchUpByItself() {
        // follow_owner teleports a ticking pet within a few ticks; the watchdog does not race it.
        assertEquals(WAIT, PetCatchUp.verdict(true, 300D, TELEPORT, false, false, NEVER, 1000L));
        assertEquals(WAIT, PetCatchUp.verdict(true, 300D, TELEPORT, false, false, 1000L, 1019L));
        assertEquals(BRING, PetCatchUp.verdict(true, 300D, TELEPORT, false, false, 1000L, 1020L));
    }

    @Test
    void aBodyThatDoesNotTickIsBroughtAtOnce() {
        // Its Mind will not run: there is nothing to wait for.
        assertEquals(BRING, PetCatchUp.verdict(true, 300D, TELEPORT, false, true, NEVER, 1000L));
    }

    @Test
    void aPetIsNeverBroughtToAnOwnerItCannotBeWithNorFromUnderItsRider() {
        // Dead, or a spectator: wherever the owner is, the pet waits.
        assertEquals(NEAR, PetCatchUp.verdict(false, 300D, TELEPORT, false, true, 100L, 1000L));
        assertEquals(NEAR, PetCatchUp.verdict(true, 300D, TELEPORT, true, true, 100L, 1000L));
    }

    @Test
    void aNewBodyIsPutBesideAnOwnerOnlyWithGroundCloseUnderThem() {
        // Standing on it, or a few blocks over it.
        assertTrue(PetMotion.lands(64D, 64D, PetMotion.LANDING_DEPTH));
        assertTrue(PetMotion.lands(67.5D, 64D, PetMotion.LANDING_DEPTH));
        // Falling or flying: groundBelow answers the bottom of its search when it finds nothing.
        assertFalse(PetMotion.lands(100D, 96D, PetMotion.LANDING_DEPTH));
    }
}
