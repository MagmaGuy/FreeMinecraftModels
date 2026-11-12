package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RideCeilingTest {
    /** A nautilus body scaled for a penguin's seat: about half a block each way, its feet at y. */
    private static BoundingBox body(double x, double y, double z) {
        return new BoundingBox(x - 0.24D, y, z - 0.24D, x + 0.24D, y + 0.52D, z + 0.24D);
    }

    /** Full blocks at the given coordinates. */
    private static RideCeiling.Blocks blocks(int[]... full) {
        Map<String, BoundingBox> world = new HashMap<>();
        for (int[] at : full)
            world.put(at[0] + "," + at[1] + "," + at[2], new BoundingBox(at[0], at[1], at[2], at[0] + 1, at[1] + 1, at[2] + 1));
        return (x, y, z) -> {
            BoundingBox box = world.get(x + "," + y + "," + z);
            return box == null ? List.of() : List.of(box);
        };
    }

    @Test
    void theIceOverABodyIsItsCeiling() {
        // Ice at y 62 over a body swimming at 60.2.
        assertEquals(62D, RideCeiling.above(body(0.5D, 60.2D, 0.5D), 0D, 0D, 68D, blocks(new int[]{0, 62, 0})));
    }

    @Test
    void openWaterHasNoCeiling() {
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.above(body(0.5D, 60.2D, 0.5D), 0D, 0D, 68D, blocks()));
    }

    @Test
    void aBlockBesideTheBodyIsNotOverIt() {
        // A wall in the next column, from below the body to above its rider: vanilla keeps the body's box out of it.
        RideCeiling.Blocks wall = blocks(new int[]{1, 60, 0}, new int[]{1, 61, 0}, new int[]{1, 62, 0});
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.above(body(0.7D, 60.2D, 0.5D), 0D, 0D, 68D, wall));
    }

    @Test
    void aBlockTheBodyStandsBesideAtItsOwnHeightIsNotACeiling() {
        // The body's own level: a block there starts under the body's top.
        assertEquals(Double.POSITIVE_INFINITY,
                RideCeiling.above(body(0.5D, 60.2D, 0.5D), 0D, 0D, 68D, blocks(new int[]{0, 60, 0})));
    }

    @Test
    void iceAheadCountsOnceTheBodyHeadsUnderIt() {
        RideCeiling.Blocks sheet = blocks(new int[]{3, 62, 0});
        BoundingBox body = body(0.5D, 60.2D, 0.5D);
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.above(body, 0D, 0D, 68D, sheet));
        assertEquals(62D, RideCeiling.above(body, 2.6D, 0D, 68D, sheet));
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.above(body, -2.6D, 0D, 68D, sheet));
    }

    @Test
    void aBankTheBodyHeadsForIsNotACeiling() {
        // Frost's dash out of the water: a bank 4 blocks ahead, its top block at y 61 above the body's top (60.72)
        // and under its rider's head. The body cannot get under it: the bank's face is in the way.
        RideCeiling.Blocks bank = blocks(new int[]{4, 59, 0}, new int[]{4, 60, 0}, new int[]{4, 61, 0},
                new int[]{5, 61, 0}, new int[]{6, 61, 0});
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.above(body(0.5D, 60.2D, 0.5D), 6D, 0D, 68D, bank));
    }

    @Test
    void nothingPastAWallIsACeiling() {
        // A wall 2 blocks ahead, and ice over open water past it: the body stops at the wall.
        RideCeiling.Blocks walled = blocks(new int[]{2, 60, 0}, new int[]{4, 62, 0});
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.above(body(0.5D, 60.2D, 0.5D), 5D, 0D, 68D, walled));
        // Without the wall the ice is over where it is heading.
        assertEquals(62D, RideCeiling.above(body(0.5D, 60.2D, 0.5D), 5D, 0D, 68D, blocks(new int[]{4, 62, 0})));
    }

    @Test
    void aLedgeTheBodyFitsUnderIsACeiling() {
        // A ledge 3 blocks ahead with open water under it at the body's own height: its rider's head would meet it.
        assertEquals(61D, RideCeiling.above(body(0.5D, 60.2D, 0.5D), 4D, 0D, 68D, blocks(new int[]{3, 61, 0})));
    }

    @Test
    void theLowestOfTwoCeilingsCounts() {
        assertEquals(62D, RideCeiling.above(body(0.5D, 60.2D, 0.5D), 0D, 0D, 68D,
                blocks(new int[]{0, 64, 0}, new int[]{0, 62, 0})));
    }

    @Test
    void aBlockPastTheReachIsLeftOut() {
        assertEquals(Double.POSITIVE_INFINITY,
                RideCeiling.above(body(0.5D, 60.2D, 0.5D), 0D, 0D, 62D, blocks(new int[]{0, 62, 0})));
    }

    @Test
    void theFloorUnderABodyIsTheTopOfTheBlockBelow() {
        assertEquals(60D, RideCeiling.below(body(0.5D, 60.2D, 0.5D), 2D, blocks(new int[]{0, 59, 0})));
        assertEquals(Double.NEGATIVE_INFINITY, RideCeiling.below(body(0.5D, 60.2D, 0.5D), 2D, blocks()));
    }

    @Test
    void openWaterNeedsNoBrake() {
        assertTrue(Double.isNaN(RideCeiling.brake(Double.POSITIVE_INFINITY, 0.7D, 0.07D, 2)));
    }

    @Test
    void aSlowClimbFarUnderTheIceIsLeftAlone() {
        // 3 blocks of room, climbing 0.1 a tick: the delay's climb leaves 2.6, of which a quarter is allowed.
        assertTrue(Double.isNaN(RideCeiling.brake(3D, 0.1D, 0.02D, 2)));
    }

    @Test
    void aFastClimbIsSlowedToAShareOfTheRoomLeft() {
        // Climbing 0.7 a tick with 2 blocks of room and 2 ticks of delay: 0.7 * 0.9 + 0.07 = 0.7 a tick, 1.4 over the
        // delay, 0.55 left after the margin, a quarter of it allowed, less the thrust the client adds itself.
        double sent = RideCeiling.brake(2D, 0.7D, 0.07D, 2);
        assertEquals(RideCeiling.GAIN * (2D - RideCeiling.MARGIN - 2D * 0.7D) - 0.07D, sent, 1.0E-9D);
    }

    @Test
    void pressedUpUnderTheIceTheBodyHoldsStill() {
        // Not moving, rider still steering up, with just the room the thrust would climb over the delay plus the
        // margin: what is sent cancels the thrust, so the body's next step is none.
        assertEquals(-0.07D, RideCeiling.brake(RideCeiling.MARGIN + 2D * 0.07D, 0D, 0.07D, 2), 1.0E-9D);
    }

    @Test
    void pressedUpCloserThanThatItIsEasedDown() {
        assertTrue(RideCeiling.brake(RideCeiling.MARGIN, 0D, 0.07D, 2) < -0.07D);
    }

    @Test
    void restingUnderTheIceWithoutSteeringUpNeedsNothing() {
        assertTrue(Double.isNaN(RideCeiling.brake(RideCeiling.MARGIN, 0D, 0D, 2)));
    }

    @Test
    void aHeadInTheIceIsSentDownButNeverThrown() {
        assertEquals(RideCeiling.GAIN * (-0.4D - RideCeiling.MARGIN), RideCeiling.brake(-0.4D, 0D, 0D, 2), 1.0E-9D);
        assertEquals(-RideCeiling.MAX_PUSH, RideCeiling.brake(-5D, 0D, 0D, 2), 1.0E-9D);
    }

    @Test
    void aSinkingBodyIsNotPushedFurther() {
        // Already sinking faster than it would be sent.
        assertTrue(Double.isNaN(RideCeiling.brake(-0.4D, -0.5D, 0D, 2)));
    }

    @Test
    void aClearHeadDoesNotDuck() {
        assertEquals(0D, RideCeiling.duck(0.3D, 5D));
        assertEquals(0D, RideCeiling.duck(0D, 5D));
    }

    @Test
    void aHeadInABlockDucksOutOfItAndAMarginMore() {
        assertEquals(1.3D + RideCeiling.MARGIN, RideCeiling.duck(-1.3D, 5D), 1.0E-9D);
    }

    @Test
    void aDuckStopsAtTheFloor() {
        assertEquals(0.4D, RideCeiling.duck(-1.3D, 0.4D), 1.0E-9D);
        assertEquals(0D, RideCeiling.duck(-1.3D, 0D));
    }
}
