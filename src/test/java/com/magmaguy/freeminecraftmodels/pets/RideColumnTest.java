package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The room a diving pet's rider takes up over its body: how far it is from the blocks beside it, and the brake. */
class RideColumnTest {
    /** A penguin's nautilus body: 0.44 wide and 0.48 tall, its feet at y. */
    private static BoundingBox body(double x, double y, double z) {
        return new BoundingBox(x - 0.22D, y, z - 0.22D, x + 0.22D, y + 0.48D, z + 0.22D);
    }

    /** The rider's column over that body: 0.24 either side, up to their head's top 1.8 over the body's feet. */
    private static BoundingBox column(double x, double y, double z) {
        return RideColumn.over(body(x, y, z), 0.24D, y + 1.8D);
    }

    private static RideCeiling.Blocks blocks(int[]... full) {
        Map<String, BoundingBox> world = new HashMap<>();
        for (int[] at : full)
            world.put(at[0] + "," + at[1] + "," + at[2], new BoundingBox(at[0], at[1], at[2], at[0] + 1, at[1] + 1, at[2] + 1));
        return (x, y, z) -> {
            BoundingBox box = world.get(x + "," + y + "," + z);
            return box == null ? List.of() : List.of(box);
        };
    }

    /** A wall of full blocks at x 5, from y 58 to 64, at z 0. */
    private static RideCeiling.Blocks wall() {
        return blocks(new int[]{5, 58, 0}, new int[]{5, 59, 0}, new int[]{5, 60, 0}, new int[]{5, 61, 0},
                new int[]{5, 62, 0}, new int[]{5, 63, 0}, new int[]{5, 64, 0});
    }

    @Test
    void theColumnStandsOverTheBodyFromItsTopToTheRidersHead() {
        BoundingBox column = column(3.5D, 60D, 0.5D);
        assertEquals(60.48D, column.getMinY(), 1.0E-9D);
        assertEquals(61.8D, column.getMaxY(), 1.0E-9D);
        assertEquals(3.26D, column.getMinX(), 1.0E-9D);
        assertEquals(3.74D, column.getMaxX(), 1.0E-9D);
    }

    @Test
    void theRoomToAWallIsFromTheColumnsOwnFace() {
        // The body's middle a block from the wall: its column's face is 0.76 from it.
        assertEquals(0.76D, RideColumn.room(column(4D, 60D, 0.5D), true, 1, 0D, 3D, wall()), 1.0E-9D);
        // Nothing the other way, and nothing along z.
        assertEquals(Double.POSITIVE_INFINITY, RideColumn.room(column(4D, 60D, 0.5D), true, -1, 0D, 3D, wall()));
        assertEquals(Double.POSITIVE_INFINITY, RideColumn.room(column(4D, 60D, 0.5D), false, 1, 0D, 3D, wall()));
        // Out of reach.
        assertEquals(Double.POSITIVE_INFINITY, RideColumn.room(column(2D, 60D, 0.5D), true, 1, 0D, 1D, wall()));
    }

    @Test
    void aBodyPressedToAWallHasItsRidersColumnInTheWall() {
        // 0.44 wide, flat against the wall at x 5: the column, 0.48 wide, is 0.02 into it.
        assertEquals(-0.02D, RideColumn.room(column(4.78D, 60D, 0.5D), true, 1, 0D, 1D, wall()), 1.0E-9D);
    }

    @Test
    void anOverhangTheBodyFitsUnderIsInTheColumnsWay() {
        // Stone from y 61 up at x 5: the body, 60 to 60.48, passes under it; its rider, up to 61.8, does not.
        RideCeiling.Blocks overhang = blocks(new int[]{5, 61, 0}, new int[]{5, 62, 0});
        assertEquals(0.76D, RideColumn.room(column(4D, 60D, 0.5D), true, 1, 0D, 3D, overhang), 1.0E-9D);
        // A ledge under the rider, at the body's own height, is the body's to stop at.
        RideCeiling.Blocks ledge = blocks(new int[]{5, 59, 0});
        assertEquals(Double.POSITIVE_INFINITY, RideColumn.room(column(4D, 60D, 0.5D), true, 1, 0D, 3D, ledge));
    }

    @Test
    void aBlockNearedCornerFirstIsSeenThroughTheTravelSideways() {
        // The block is one column over along z: straight on it is not in the way, travelling toward it it is.
        RideCeiling.Blocks corner = blocks(new int[]{5, 61, 1});
        assertEquals(Double.POSITIVE_INFINITY, RideColumn.room(column(4D, 60D, 0.5D), true, 1, 0D, 3D, corner));
        assertEquals(0.76D, RideColumn.room(column(4D, 60D, 0.5D), true, 1, 0.5D, 3D, corner), 1.0E-9D);
    }

    @Test
    void aBlockTheColumnsMiddleIsInIsNotAheadOfIt() {
        // Deep in the wall, as after a dash: no way along the axis leads out by slowing down.
        assertEquals(Double.POSITIVE_INFINITY, RideColumn.room(column(5.5D, 60D, 0.5D), true, 1, 0D, 1D, wall()));
        assertEquals(Double.POSITIVE_INFINITY, RideColumn.room(column(5.5D, 60D, 0.5D), true, -1, 0D, 1D, wall()));
    }

    @Test
    void forwardSwimsAlongTheViewAndBackwardHalfAsHardTheOtherWay() {
        // Movement speed 2: 0.065 blocks a tick. Yaw 0 looks along +z.
        Vector level = RideColumn.thrust(2D, 0F, 0F, 1, 0);
        assertEquals(0D, level.getX(), 1.0E-9D);
        assertEquals(0D, level.getY(), 1.0E-9D);
        assertEquals(0.065D, level.getZ(), 1.0E-9D);
        // Yaw -90 looks along +x; looking 30 degrees up, half the push lifts.
        Vector up = RideColumn.thrust(2D, -90F, -30F, 1, 0);
        assertEquals(0.065D * Math.cos(Math.toRadians(30D)), up.getX(), 1.0E-9D);
        assertEquals(0.0325D, up.getY(), 1.0E-9D);
        assertEquals(0D, up.getZ(), 1.0E-9D);
        Vector back = RideColumn.thrust(2D, 0F, 0F, -1, 0);
        assertEquals(-0.0325D, back.getZ(), 1.0E-9D);
    }

    @Test
    void strafingIsLevelAndSharesThePushWithForward() {
        // Left of a view along +z is +x.
        Vector left = RideColumn.thrust(2D, 0F, 0F, 0, 1);
        assertEquals(0.065D * RideColumn.KEY, left.getX(), 1.0E-9D);
        assertEquals(0D, left.getZ(), 1.0E-9D);
        Vector both = RideColumn.thrust(2D, 0F, 0F, 1, -1);
        assertEquals(0.065D, both.length(), 1.0E-9D);
        assertTrue(both.getX() < 0D && both.getZ() > 0D);
        // No key, no push, wherever the rider looks.
        assertEquals(0D, RideColumn.thrust(2D, 40F, -60F, 0, 0).length());
    }

    @Test
    void inOpenWaterNothingIsSent() {
        assertTrue(Double.isNaN(RideColumn.brake(column(2D, 60D, 0.5D), true, 0.6D, 0.065D, 0D, 1, blocks())));
    }

    @Test
    void aColumnHeadingForAWallIsSlowedBeforeItGetsThere() {
        // 0.6 blocks a tick, a block and a half from the wall: slower, and still toward it.
        double sent = RideColumn.brake(column(3.26D, 60D, 0.5D), true, 0.6D, 0.065D, 0D, 1, wall());
        assertTrue(sent < RideCeiling.WATER_DRAG * 0.6D, "slowed: " + sent);
        assertTrue(sent > -RideCeiling.MAX_PUSH, "not thrown back: " + sent);
        // Far from it at that speed, it is left alone.
        assertTrue(Double.isNaN(RideColumn.brake(column(0.5D, 60D, 0.5D), true, 0.6D, 0.065D, 0D, 1, wall())));
    }

    @Test
    void heldAgainstAWallItComesToRestAMarginShortOfIt() {
        // The rider keeps pushing at the wall. Each tick the client adds its thrust to what it was sent and moves.
        double x = 4D, step = 0.3D, thrust = 0.065D;
        for (int tick = 0; tick < 80; tick++) {
            double sent = RideColumn.brake(column(x, 60D, 0.5D), true, step, thrust, 0D, 0, wall());
            double velocity = (Double.isNaN(sent) ? RideCeiling.WATER_DRAG * step : sent) + thrust;
            // The body's own side stops at the wall, as the client stops it.
            double next = Math.min(x + velocity, 5D - 0.22D);
            step = next - x;
            x = next;
            assertTrue(x + 0.24D <= 5D - 0.01D, "tick " + tick + ": the column kept clear of the wall, its face at " + (x + 0.24D));
        }
        assertEquals(5D - RideColumn.MARGIN, x + 0.24D, 0.01D);
    }

    @Test
    void aColumnAtRestInsideTheMarginIsEasedBackOut() {
        // 0.02 into the wall, as a body pressed to it leaves its rider: sent away from the wall.
        double sent = RideColumn.brake(column(4.78D, 60D, 0.5D), true, 0D, 0D, 0D, 1, wall());
        assertTrue(sent < 0D && sent > -0.1D, "eased back: " + sent);
        // A margin clear, nothing.
        assertTrue(Double.isNaN(RideColumn.brake(column(4.70D, 60D, 0.5D), true, 0D, 0D, 0D, 1, wall())));
    }

    @Test
    void aColumnLeavingAWallIsNotHeldBack() {
        assertTrue(Double.isNaN(RideColumn.brake(column(4.6D, 60D, 0.5D), true, -0.3D, -0.065D, 0D, 1, wall())));
    }

    @Test
    void besideTheBodyOnlyWhatHangsOverTheRidersHeadIsACeiling() {
        // The wall the column is held beside runs from under the body to over the rider: no ceiling, pressed to it.
        BoundingBox body = body(4.78D, 60D, 0.5D);
        BoundingBox footprint = RideColumn.footprint(body, 0.24D, 61.8D);
        // The wall's blocks from y 62 up start over the head, 61.8: with the column 0.02 into the wall they count.
        assertEquals(62D, RideCeiling.over(footprint, 68D, wall()));
        // A margin off the wall, as the brake holds it, nothing is over the rider.
        BoundingBox clear = RideColumn.footprint(body(4.70D, 60D, 0.5D), 0.24D, 61.8D);
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.over(clear, 68D, wall()));
        // And the wall's blocks beside the head, which start under its top, never do.
        RideCeiling.Blocks low = blocks(new int[]{5, 60, 0}, new int[]{5, 61, 0});
        assertEquals(Double.POSITIVE_INFINITY, RideCeiling.over(footprint, 68D, low));
    }
}
