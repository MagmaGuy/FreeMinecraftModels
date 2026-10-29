package com.magmaguy.freeminecraftmodels.pets.migration;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MigrationFlockTest {
    private static final Vector EAST = new Vector(1, 0, 0);

    /** Flat ground at y 64 with one column of the given height at (hillX, hillZ). */
    private static MigrationFlock.Ground hillAt(int hillX, int hillZ, int top) {
        return (x, z) -> x == hillX && z == hillZ ? top : 64;
    }

    @Test
    void aHillWhereTheFlockStartsCounts() {
        // The player is 115 blocks east of the start; the hill is under the start, not under the player.
        assertEquals(130, MigrationFlock.routeGround(new Vector(0, 0, 0), EAST, 270, 12, hillAt(0, 0, 130)));
    }

    @Test
    void groundUnderTheFormationsEdgeCounts() {
        assertEquals(110, MigrationFlock.routeGround(new Vector(0, 0, 0), EAST, 270, 12, hillAt(100, -12, 110)));
        assertEquals(110, MigrationFlock.routeGround(new Vector(0, 0, 0), EAST, 270, 12, hillAt(-12, 0, 110)));
        assertEquals(110, MigrationFlock.routeGround(new Vector(0, 0, 0), EAST, 270, 12, hillAt(282, 0, 110)));
    }

    @Test
    void groundBesideTheRouteDoesNotCount() {
        assertEquals(64, MigrationFlock.routeGround(new Vector(0, 0, 0), EAST, 270, 12, hillAt(100, 40, 200)));
    }

    @Test
    void unloadedGroundDoesNotCount() {
        MigrationFlock.Ground halfLoaded = (x, z) -> x < 140 ? 70 : Integer.MIN_VALUE;
        assertEquals(70, MigrationFlock.routeGround(new Vector(0, 0, 0), EAST, 270, 12, halfLoaded));
        assertEquals(Integer.MIN_VALUE, MigrationFlock.routeGround(new Vector(0, 0, 0), EAST, 270, 12,
                (x, z) -> Integer.MIN_VALUE));
    }

    @Test
    void aDiagonalRouteFollowsItsHeading() {
        Vector northEast = new Vector(1, 0, -1).normalize();
        // 100 blocks along a north-east heading from the origin.
        int x = (int) Math.floor(northEast.getX() * 100), z = (int) Math.floor(northEast.getZ() * 100);
        MigrationFlock.Ground nearThere = (gx, gz) -> Math.abs(gx - x) <= 2 && Math.abs(gz - z) <= 2 ? 150 : 64;
        assertEquals(150, MigrationFlock.routeGround(new Vector(0, 0, 0), northEast, 270, 12, nearThere));
    }
}
