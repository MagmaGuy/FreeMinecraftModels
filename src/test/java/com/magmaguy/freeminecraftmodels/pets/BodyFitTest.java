package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyFitTest {
    /** The penguin's two ridden bodies as measured in game: the nautilus it swims as and the horse it walks as. */
    private static final double NAUTILUS = 0.438D, NAUTILUS_TALL = 0.476D, HORSE = 0.55D, HORSE_TALL = 0.63D;
    /** A ridden horse body with its rider's head room: 0.48 wide up to 1.8 blocks above the body's feet. */
    private static final BodyFit.Shape RIDDEN = new BodyFit.Shape(HORSE, HORSE_TALL, 0.48D, HORSE_TALL, 1.8D);
    /** The body nobody steers: the box of the penguin model's hitbox bone. */
    private static final BodyFit.Shape UNRIDDEN = BodyFit.Shape.of(0.68D, 1.08D);

    private static BoundingBox nautilus(double x, double y, double z) {
        return new BoundingBox(x - NAUTILUS / 2D, y, z - NAUTILUS / 2D, x + NAUTILUS / 2D, y + NAUTILUS_TALL, z + NAUTILUS / 2D);
    }

    /** A world of collision boxes, each listed under the block it belongs to, as a block's own shape is. */
    private static final class World implements RideCeiling.Blocks {
        private final Map<String, List<BoundingBox>> cells = new HashMap<>();

        World full(int x, int y, int z) {
            return box(x, y, z, new BoundingBox(x, y, z, x + 1, y + 1, z + 1));
        }

        /** A floor of full blocks at y, from (x0, z0) to (x1, z1). */
        World floor(int y, int x0, int z0, int x1, int z1) {
            for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) full(x, y, z);
            return this;
        }

        /** A wall of full blocks in the column x, from y0 to y1 and z0 to z1. */
        World wallX(int x, int y0, int y1, int z0, int z1) {
            for (int y = y0; y <= y1; y++) for (int z = z0; z <= z1; z++) full(x, y, z);
            return this;
        }

        World box(int x, int y, int z, BoundingBox box) {
            cells.computeIfAbsent(x + "," + y + "," + z, key -> new ArrayList<>()).add(box);
            return this;
        }

        @Override
        public List<BoundingBox> boxes(int x, int y, int z) {
            return cells.getOrDefault(x + "," + y + "," + z, List.of());
        }
    }

    @Test
    void aBodyWithRoomStaysWhereItIs() {
        World bank = new World().floor(63, -3, -3, 3, 3);
        Vector spot = BodyFit.nearest(0.5D, 64D, 0.5D, RIDDEN, nautilus(0.5D, 64D, 0.5D), bank);
        assertEquals(new Vector(0.5D, 64D, 0.5D), spot);
    }

    @Test
    void aBodyStandingOnTheFloorIsNotInIt() {
        assertTrue(BodyFit.fits(0.5D, 64D, 0.5D, RIDDEN, new World().floor(63, -3, -3, 3, 3)));
    }

    @Test
    void theWiderHorseBodyIsMovedOffTheWallTheNautilusLandedAgainst() {
        // Frost's report: dashed out of the water, the nautilus comes down pressed against a wall at x 3.
        World bank = new World().floor(63, -3, -3, 3, 3).wallX(3, 64, 68, -3, 3);
        double pressed = 3D - NAUTILUS / 2D;
        assertFalse(BodyFit.fits(pressed, 64D, 0.5D, RIDDEN, bank));
        Vector spot = BodyFit.nearest(pressed, 64D, 0.5D, RIDDEN, nautilus(pressed, 64D, 0.5D), bank);
        assertNotNull(spot);
        assertTrue(BodyFit.fits(spot.getX(), spot.getY(), spot.getZ(), RIDDEN, bank));
        // Straight back off the wall, by the difference between the two bodies and no more.
        assertEquals(3D - HORSE / 2D - BodyFit.CLEAR, spot.getX(), 1.0E-9D);
        assertEquals(64D, spot.getY(), 1.0E-9D);
        assertEquals(0.5D, spot.getZ(), 1.0E-9D);
    }

    @Test
    void aBodyInACornerIsMovedOffBothWalls() {
        World corner = new World().floor(63, -3, -3, 3, 3).wallX(3, 64, 68, -3, 3);
        for (int x = -3; x <= 3; x++) for (int y = 64; y <= 68; y++) corner.full(x, y, 3);
        double pressed = 3D - NAUTILUS / 2D;
        Vector spot = BodyFit.nearest(pressed, 64D, pressed, RIDDEN, nautilus(pressed, 64D, pressed), corner);
        assertNotNull(spot);
        assertTrue(BodyFit.fits(spot.getX(), spot.getY(), spot.getZ(), RIDDEN, corner));
        assertEquals(3D - HORSE / 2D - BodyFit.CLEAR, spot.getX(), 1.0E-9D);
        assertEquals(3D - HORSE / 2D - BodyFit.CLEAR, spot.getZ(), 1.0E-9D);
    }

    @Test
    void aRiderWhoseHeadIsUnderAnOverhangIsMovedOutFromUnderIt() {
        // A ledge one block over the bank at x 2: the nautilus slid under it, its rider's head inside the ledge.
        World ledge = new World().floor(63, -3, -3, 3, 3).wallX(3, 64, 68, -3, 3);
        for (int z = -3; z <= 3; z++) ledge.full(2, 65, z);
        double under = 2.6D;
        assertFalse(BodyFit.fits(under, 64D, 0.5D, RIDDEN, ledge));
        Vector spot = BodyFit.nearest(under, 64D, 0.5D, RIDDEN, nautilus(under, 64D, 0.5D), ledge);
        assertNotNull(spot);
        assertTrue(BodyFit.fits(spot.getX(), spot.getY(), spot.getZ(), RIDDEN, ledge));
        // Back out the way it came in, on the same floor: the rider's head room clears the ledge's edge at x 2.
        assertEquals(2D - 0.48D / 2D - BodyFit.CLEAR, spot.getX(), 1.0E-9D);
        assertEquals(64D, spot.getY(), 1.0E-9D);
    }

    @Test
    void aBodyUnderALowCeilingInWaterIsMovedDown() {
        // No floor near: a nautilus against the underside of a block, where the taller body does not fit.
        World ceiling = new World().floor(66, -3, -3, 3, 3);
        double top = 66D - NAUTILUS_TALL;
        Vector spot = BodyFit.nearest(0.5D, top, 0.5D, UNRIDDEN, nautilus(0.5D, top, 0.5D), ceiling);
        assertNotNull(spot);
        assertEquals(66D - 1.08D - BodyFit.CLEAR, spot.getY(), 1.0E-9D);
        assertEquals(0.5D, spot.getX(), 1.0E-9D);
    }

    @Test
    void aSpotWithNoRoomWithinReachIsRefused() {
        // A crawlspace one block high: the nautilus fits, the unridden body is too tall anywhere near.
        World crawlspace = new World().floor(63, -4, -4, 4, 4).floor(65, -4, -4, 4, 4);
        assertNull(BodyFit.nearest(0.5D, 64D, 0.5D, UNRIDDEN, nautilus(0.5D, 64D, 0.5D), crawlspace));
    }

    @Test
    void aBodyIsNotPutOnTheFarSideOfAThinWall() {
        // The nautilus stands in a gap 0.5 blocks wide, between a half-block-thick wall behind it and a pane-thin
        // wall at x 3 in front of it. The ridden body is too wide for the gap, and the only room is past the pane.
        World gap = new World().floor(63, -3, -3, 6, 3);
        for (int y = 64; y <= 68; y++)
            for (int z = -3; z <= 3; z++) {
                gap.box(3, y, z, new BoundingBox(3D, y, z, 3.125D, y + 1, z + 1));
                gap.box(2, y, z, new BoundingBox(2D, y, z, 2.5D, y + 1, z + 1));
            }
        double between = 2.75D;
        assertTrue(BodyFit.colliders(nautilus(between, 64D, 0.5D), gap).isEmpty());
        assertNull(BodyFit.nearest(between, 64D, 0.5D, RIDDEN, nautilus(between, 64D, 0.5D), gap));
        // Without the body it had, the search has no path to check and takes the far side.
        assertNotNull(BodyFit.nearest(between, 64D, 0.5D, RIDDEN, null, gap));
    }

    @Test
    void aFenceIsAsTallAsItsCollisionBox() {
        // A fence post in the block under the box's lowest block: its collision box reaches half a block up into it.
        World fence = new World().floor(63, -3, -3, 3, 3)
                .box(1, 64, 0, new BoundingBox(1.375D, 64D, 0.375D, 1.625D, 65.5D, 0.625D));
        BoundingBox over = new BoundingBox(1.3D, 65.2D, 0.3D, 1.7D, 65.6D, 0.7D);
        assertEquals(1, BodyFit.colliders(over, fence).size());
    }

    @Test
    void theUnriddenBodyGrowsOffTheWallItsRiddenBodyStoodAgainst() {
        // Getting off beside a wall: the body takes its model's bigger box back.
        World bank = new World().floor(63, -3, -3, 3, 3).wallX(3, 64, 68, -3, 3);
        double pressed = 3D - HORSE / 2D;
        BoundingBox ridden = new BoundingBox(pressed - HORSE / 2D, 64D, 0.5D - HORSE / 2D, pressed + HORSE / 2D,
                64D + HORSE_TALL, 0.5D + HORSE / 2D);
        Vector spot = BodyFit.nearest(pressed, 64D, 0.5D, UNRIDDEN, ridden, bank);
        assertNotNull(spot);
        assertEquals(3D - 0.68D / 2D - BodyFit.CLEAR, spot.getX(), 1.0E-9D);
    }
}
