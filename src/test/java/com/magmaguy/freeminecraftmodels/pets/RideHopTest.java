package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RideHopTest {
    /** A ridden horse body as the penguin's is: 0.55 wide, 0.63 tall, its rider's head room up to 1.8. */
    private static final BodyFit.Shape RIDDEN = new BodyFit.Shape(0.55D, 0.63D, 0.48D, 0.63D, 1.8D);

    /**
     * A beach along +x: a floor at y 62 under shallows (its top at 63) up to x 3, then a shore whose top is at 64 from
     * x 4 on. Plus a full block at each of the given coordinates.
     */
    private static RideCeiling.Blocks beach(int[]... extra) {
        List<int[]> full = new ArrayList<>(List.of(extra));
        for (int x = -6; x <= 20; x++) full.add(new int[]{x, x >= 4 ? 63 : 62, 0});
        return (x, y, z) -> {
            for (int[] at : full)
                if (at[0] == x && at[1] == y && at[2] == z) return List.of(new BoundingBox(x, y, z, x + 1, y + 1, z + 1));
            return List.of();
        };
    }

    @Test
    void aHopOverOpenShallowsOntoTheShoreIsClear() {
        // From the shallows 2.5 blocks short of the shore, at a swim's speed with the default lift: it comes down on
        // the shore, with nothing over it.
        assertTrue(RideHop.clear(1.5D, 63D, 0.5D, 0.6D, 0.42D, 0D, RIDDEN, beach()));
    }

    @Test
    void aBankItCouldStepOntoOnlyStopsItsTravel() {
        // Right in front of a shore one block up: its box meets the shore's face on the way up, as it would walking
        // into it, and it rises on beside the face.
        assertTrue(RideHop.clear(3.4D, 63D, 0.5D, 0.6D, 0.42D, 0D, RIDDEN, beach()));
        assertTrue(RideHop.clear(3.4D, 63D, 0.5D, 0.6D, 0.2D, 0D, RIDDEN, beach()));
    }

    @Test
    void aBankTallerThanAStepMeansNoHop() {
        // The same spot with the shore two blocks up.
        assertFalse(RideHop.clear(3.4D, 63D, 0.5D, 0.6D, 0.42D, 0D, RIDDEN, beach(new int[]{4, 64, 0})));
    }

    @Test
    void aStepWithALedgeAtHeadHeightBehindItMeansNoHop() {
        // The shore one block up, and a block over it where the rider's head would be when the body meets its face.
        assertFalse(RideHop.clear(3.4D, 63D, 0.5D, 0.6D, 0.42D, 0D, RIDDEN, beach(new int[]{3, 65, 0})));
    }

    @Test
    void aLedgeOverTheRidersHeadMeansNoHop() {
        // A block 0.2 over the rider's head (their head's top at 64.8, the block from 65): any rise meets it.
        assertFalse(RideHop.clear(1.5D, 63D, 0.5D, 0.6D, 0.42D, 0D, RIDDEN, beach(new int[]{1, 65, 0}, new int[]{2, 65, 0})));
    }

    @Test
    void aWallAcrossTheFlightMeansNoHop() {
        // A wall two blocks ahead, as tall as the rider: the flight ends in it while still rising or level.
        assertFalse(RideHop.clear(0.5D, 63D, 0.5D, 0.6D, 0.42D, 0D, RIDDEN,
                beach(new int[]{2, 63, 0}, new int[]{2, 64, 0}, new int[]{2, 65, 0})));
    }

    @Test
    void aLedgeFurtherAlongTheFlightMeansNoHop() {
        // Nothing over where it starts, a ledge at head height a block and a half on.
        assertFalse(RideHop.clear(0.5D, 63D, 0.5D, 0.6D, 0.42D, 0D, RIDDEN, beach(new int[]{2, 65, 0})));
    }

    @Test
    void thePaceOutlastsTheBeachsSteps() {
        // Swimming in at 0.6 a tick, then stopped by the beach's steps for 15 ticks at a tenth of a block.
        RideHop.Pace pace = new RideHop.Pace();
        for (int tick = 0; tick < 10; tick++) pace.step(0.6D, 0D);
        assertEquals(0.6D, pace.blocks(), 1.0E-9D);
        for (int tick = 0; tick < 15; tick++) pace.step(0D, 0.1D);
        assertEquals(0.6D, pace.blocks(), 1.0E-9D);
        // A second after its last fast step it is gone, down to the step it still makes.
        for (int tick = 0; tick < 5; tick++) pace.step(0.1D, 0D);
        assertEquals(0.1D, pace.blocks(), 1.0E-9D);
        pace.clear();
        assertEquals(0D, pace.blocks());
    }

    @Test
    void theLiftFollowsTheSpeedItSwamInWith() {
        assertEquals(0.42D, RideHop.lift(0.42D, 0.7D, 0.7D), 1.0E-9D);
        assertEquals(0.42D, RideHop.lift(0.42D, 0.9D, 0.7D), 1.0E-9D);
        assertEquals(0.21D, RideHop.lift(0.42D, 0.35D, 0.7D), 1.0E-9D);
        assertEquals(0D, RideHop.lift(0.42D, 0D, 0.7D));
        assertEquals(0D, RideHop.lift(0D, 0.7D, 0.7D));
    }
}
