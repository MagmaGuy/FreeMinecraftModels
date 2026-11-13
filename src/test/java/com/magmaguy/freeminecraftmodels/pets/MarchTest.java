package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marches on made-up ground: flat at feet height 64, with walls where a test puts them. Marchers walk toward the spots
 * the march gives them each tick as march.lua and steer_onto walk them: at the module's pace times each one's speed
 * stat, hurrying when more than 3 blocks behind, easing off over the last block, and standing still within 0.3 of the
 * spot. A follower on the leader's footprints walks straight at the footprint the march gives it. Any other marcher
 * walks straight at a spot a level up or down too when it is within 2 blocks over ground that is one step at a time,
 * a climb only from within a block, as steer_onto does. Any other spot off their level goes to the body's
 * pathfinder, as steer_onto hands it over: the marcher walks at its pace until the spot is a block away, which the
 * pathfinder takes as reached. So a whole march plays out without a server.
 */
class MarchTest {
    private static final int FEET = 64;
    private static final Vector HOME = new Vector(0.5, FEET, 0.5);
    private static final double SPACING = 1.5;
    /** Blocks a tick at a speed stat of 1, and march.lua's hurry, stop and hurry distance. */
    private static final double PACE = 0.15, CATCH_UP = 1.6, CLOSE = 0.3, BEHIND = 3;
    /**
     * steer_onto walks straight at a spot within this much of the body's height, and hands one further up or down to
     * the pathfinder, whose walk ends this far short of it.
     */
    private static final double RISE = 0.6, PATH_SHORT = 1;
    /** steer_onto's reach for a straight walk, and for one that climbs. */
    private static final double REACH = 2, CLIMB = 1;
    /** steer_onto's least share of its pace over the last block, and its floor: MIN_SPEED over march.lua's 4 x speed. */
    private static final double SLOWEST_SHARE = 0.3, FLOOR = PACE * 0.25 / (4 * 0.18);

    /** Flat ground 200 blocks round, with no footing where {@code wall} says. */
    private static MarchRoute.Ground ground(BiPredicate<Integer, Integer> wall) {
        return (x, feet, z) -> Math.abs(x) > 200 || Math.abs(z) > 200 || wall.test(x, z) || Math.abs(feet - FEET) > 1
                ? MarchRoute.Ground.NO_FOOTING : FEET;
    }

    private static final MarchRoute.Ground OPEN = ground((x, z) -> false);

    @Test
    void aRunStopsBeforeAWallOrAClimbOfTwoAndTakesSingleSteps() {
        MarchRoute.Ground walled = ground((x, z) -> x == 10);
        assertEquals(9.0, MarchRoute.run(walled, HOME, 1, 0, 24).length(), 1.0E-9, "stops before the wall at x 10");
        MarchRoute.Ground steps = (x, feet, z) -> {
            int height = x >= 8 ? FEET + 3 : x >= 4 ? FEET + 1 : FEET;
            return Math.abs(height - feet) <= 1 ? height : MarchRoute.Ground.NO_FOOTING;
        };
        MarchRoute.Run run = MarchRoute.run(steps, HOME, 1, 0, 24);
        assertEquals(7.0, run.length(), 1.0E-9, "climbs the step at x 4, not the two at x 8");
        assertEquals(FEET + 1, run.end().getY(), 1.0E-9);
    }

    @Test
    void theWidestHeadingIsTheOpenOneAndATurnIsTheSmallestThatClears() {
        // Open only toward +z: a corridor 3 blocks wide.
        MarchRoute.Ground corridor = ground((x, z) -> x < -1 || x > 1 || z < -1);
        double[] widest = MarchRoute.widest(corridor, HOME, 0);
        assertNotNull(widest);
        assertEquals(0, widest[0], 1.0E-9);
        assertEquals(1, widest[1], 1.0E-9);
        assertNull(MarchRoute.widest(ground((x, z) -> Math.abs(x) > 2 || Math.abs(z) > 2), HOME, 0),
                "no heading reaches 6 blocks in a pen");
        // Heading +x into a wall across x 3: the smallest turn that runs 6 blocks before the wall is three sixteenths
        // of a circle, and the +z side comes first.
        MarchRoute.Ground wall = ground((x, z) -> x == 3);
        double[] turned = MarchRoute.turn(wall, HOME, 1, 0, MarchRoute.MIN_LEG, false);
        assertNotNull(turned);
        assertEquals(3 * Math.PI / 8, Math.atan2(turned[1], turned[0]), 1.0E-9);
        double[] straight = MarchRoute.turn(OPEN, HOME, 1, 0, MarchRoute.MIN_LEG, false);
        assertEquals(1, straight[0], 1.0E-9, "an open way ahead is no turn");
        double[] forced = MarchRoute.turn(OPEN, HOME, 1, 0, MarchRoute.MIN_LEG, true);
        assertTrue(forced[0] < 1 - 1.0E-6, "a stuck leader turns even with the way ahead open");
    }

    @Test
    void theLineFormsStraightBehindTheLeaderBeforeItSetsOff() {
        Sim sim = new Sim(OPEN, 4, 2400);
        for (int tick = 0; tick < 200 && sim.march.phase() == March.Phase.FORMING; tick++) sim.step();
        assertEquals(March.Phase.OUT, sim.march.phase(), "the line formed and set off");
        Vector leader = sim.at(0);
        assertEquals(HOME.getX(), leader.getX(), 0.2, "the leader waited where it called");
        for (int rank = 1; rank < 5; rank++) {
            Vector follower = sim.at(rank);
            assertEquals(HOME.getX() - rank * SPACING, follower.getX(), March.FORMED + 0.01, "rank " + rank);
            assertEquals(HOME.getZ(), follower.getZ(), March.FORMED + 0.01, "rank " + rank);
        }
    }

    @Test
    void aLeaderShovedOffItsSpotWhileTheLineFormsWalksBackAndSetsOffTheWayItCalled() {
        // Open ground round the spot the march is called from, and from that spot one way out: a corridor a block
        // wide toward +x. A block to the side of the spot, no heading runs 6 blocks.
        MarchRoute.Ground corridor = ground((x, z) -> x > 2 && z != 0 || Math.abs(z) > 3 || x < -8);
        Sim sim = new Sim(corridor, 4, 2400);
        for (int tick = 0; tick < 5; tick++) sim.step();
        assertEquals(March.Phase.FORMING, sim.march.phase(), "the line is still forming");
        // The marchers gathering round the leader shove it more than a block off its spot.
        sim.at(0).setZ(HOME.getZ() - 1.3);
        while (sim.march.phase() == March.Phase.FORMING) {
            sim.step();
            assertTrue(sim.now < Sim.START + 400, "the line sets off");
        }
        assertEquals(HOME.getZ(), sim.at(0).getZ(), 0.35, "the leader is back on the spot it called from");
        for (int tick = 0; tick < 400; tick++) sim.step();
        assertFalse(sim.march.over(), "the march goes on");
        assertTrue(sim.at(0).getX() > HOME.getX() + 8, "the leader walked out along the corridor: " + sim.at(0));
    }

    @Test
    void aLeaderShovedUpAStepWhileTheLineFormsWalksBackDownToItsSpot() {
        // A terrace one block higher beside the spot the march is called from, from z -1 on.
        MarchRoute.Ground terrace = (x, feet, z) -> {
            int height = z <= -1 ? FEET + 1 : FEET;
            return Math.abs(x) > 200 || Math.abs(z) > 200 || Math.abs(height - feet) > 1 ? MarchRoute.Ground.NO_FOOTING : height;
        };
        Sim sim = new Sim(terrace, 4, 2400);
        for (int tick = 0; tick < 5; tick++) sim.step();
        assertEquals(March.Phase.FORMING, sim.march.phase(), "the line is still forming");
        // The marchers gathering round the leader shove it 1.2 blocks, up onto the terrace.
        sim.at(0).setZ(HOME.getZ() - 1.2);
        sim.at(0).setY(FEET + 1);
        Vector spot = sim.march.spot(sim.ids.get(0), sim.at(0), sim.now, terrace);
        assertEquals(HOME.getY(), spot.getY(), 1.0E-9, "its spot is where it called from, at the height it called at");
        for (int tick = 0; tick < 25 && sim.march.phase() == March.Phase.FORMING; tick++) sim.step();
        assertEquals(HOME.getZ(), sim.at(0).getZ(), 0.35, "the leader is back on the spot it called from");
        assertEquals(FEET, sim.at(0).getY(), 1.0E-9, "down off the terrace");
        while (sim.march.phase() == March.Phase.FORMING) {
            sim.step();
            assertTrue(sim.now < Sim.START + 400, "the line sets off");
        }
        Vector from = sim.at(0).clone();
        for (int tick = 0; tick < 40; tick++) sim.step();
        assertTrue(sim.at(0).getX() > from.getX() + 0.5, "and it walks out at once: " + sim.at(0));
    }

    @Test
    void aLeaderShovedOffTheEdgeItCalledFromWalksBackUnderItsSpot() {
        // The block the march is called over is a block lower than the ground round it: the caller stood on the edge
        // of the next block, its middle over the lower ground.
        MarchRoute.Ground lip = (x, feet, z) -> {
            int height = x == 0 && z == 0 ? FEET - 1 : FEET;
            return Math.abs(x) > 200 || Math.abs(z) > 200 || Math.abs(height - feet) > 1 ? MarchRoute.Ground.NO_FOOTING : height;
        };
        March march = new March(new UUID(0, 0), HOME, List.of(new UUID(0, 1)), new Vector[]{new Vector(-3, FEET, 0.5)},
                new double[]{1, 1}, 1, 0, SPACING, 1000, 2400);
        // Shoved a third of a block, it drops off the edge onto the lower ground.
        Vector spot = march.spot(new UUID(0, 0), new Vector(0.2, FEET - 1, 0.3), 1010, lip);
        assertEquals(March.Phase.FORMING, march.phase());
        assertEquals(HOME.getX(), spot.getX(), 1.0E-9);
        assertEquals(HOME.getZ(), spot.getZ(), 1.0E-9);
        assertEquals(FEET - 1, spot.getY(), 1.0E-9, "its spot is on the ground under where it called, not in the air over it");
    }

    @Test
    void theLeaderWalksDownAStepAcrossItsWayAndTheLineFollows() {
        // A step down across the way at x 10, as a terrace on a hillside.
        MarchRoute.Ground terrace = (x, feet, z) -> {
            int height = x >= 10 ? FEET - 1 : FEET;
            return Math.abs(x) > 200 || Math.abs(z) > 200 || Math.abs(height - feet) > 1 ? MarchRoute.Ground.NO_FOOTING : height;
        };
        Sim sim = new Sim(terrace, 4, 2400);
        for (int tick = 0; tick < 500; tick++) sim.step();
        assertFalse(sim.march.over(), "the march goes on");
        assertTrue(sim.at(0).getX() > 20, "the leader walked down the step and on: " + sim.at(0));
        assertEquals(FEET - 1, sim.at(0).getY(), 1.0E-9);
        assertEquals(HOME.getZ(), sim.at(0).getZ(), 0.05, "straight on, with no turn at the step");
        for (int rank = 1; rank <= 4; rank++)
            assertTrue(sim.at(rank).getX() > 10, "rank " + rank + " came down the step behind it: " + sim.at(rank));
    }

    @Test
    void theLeaderWalksThroughADitchAcrossItsWayAndTheLineFollows() {
        // A ditch one block wide and one deep across the way at x 10.
        MarchRoute.Ground ditch = (x, feet, z) -> {
            int height = x == 10 ? FEET - 1 : FEET;
            return Math.abs(x) > 200 || Math.abs(z) > 200 || Math.abs(height - feet) > 1 ? MarchRoute.Ground.NO_FOOTING : height;
        };
        Sim sim = new Sim(ditch, 4, 2400);
        boolean inTheDitch = false;
        for (int tick = 0; tick < 500; tick++) {
            sim.step();
            if (sim.at(0).getY() == FEET - 1) inTheDitch = true;
        }
        assertFalse(sim.march.over(), "the march goes on");
        assertTrue(inTheDitch, "the leader went down into the ditch");
        assertTrue(sim.at(0).getX() > 20, "and out of it on the far side: " + sim.at(0));
        assertEquals(FEET, sim.at(0).getY(), 1.0E-9);
        assertEquals(HOME.getZ(), sim.at(0).getZ(), 0.05, "straight on, with no turn at the ditch");
        for (int rank = 1; rank <= 4; rank++)
            assertTrue(sim.at(rank).getX() > 11, "rank " + rank + " crossed the ditch behind it: " + sim.at(rank));
    }

    @Test
    void theLeaderWalksUpAStepAcrossItsWayAndTheLineFollows() {
        MarchRoute.Ground terrace = (x, feet, z) -> {
            int height = x >= 10 ? FEET + 1 : FEET;
            return Math.abs(x) > 200 || Math.abs(z) > 200 || Math.abs(height - feet) > 1 ? MarchRoute.Ground.NO_FOOTING : height;
        };
        Sim sim = new Sim(terrace, 4, 2400);
        for (int tick = 0; tick < 500; tick++) sim.step();
        assertFalse(sim.march.over(), "the march goes on");
        assertTrue(sim.at(0).getX() > 20, "the leader walked up the step and on: " + sim.at(0));
        assertEquals(FEET + 1, sim.at(0).getY(), 1.0E-9);
        for (int rank = 1; rank <= 4; rank++)
            assertTrue(sim.at(rank).getX() > 10, "rank " + rank + " came up the step behind it: " + sim.at(rank));
    }

    @Test
    void onlyTheLeaderOfALineThatHasSetOffWalksItsLeg() {
        Sim sim = new Sim(OPEN, 3, 2400);
        UUID leader = sim.ids.get(0), follower = sim.ids.get(1);
        sim.step();
        assertFalse(sim.march.walksLeg(leader, sim.at(0)), "while the line forms the leader has no leg");
        while (sim.march.phase() == March.Phase.FORMING) sim.step();
        for (int tick = 0; tick < 40; tick++) sim.step();
        assertTrue(sim.march.walksLeg(leader, sim.at(0)), "once it has set off the leader walks its leg");
        assertFalse(sim.march.walksLeg(follower, sim.at(1)), "a follower walks in footprints, not on a leg");
        assertTrue(sim.march.walksLeg(leader, sim.at(0).clone().setZ(HOME.getZ() + 0.4)), "shoved a little aside, it still does");
        assertFalse(sim.march.walksLeg(leader, sim.at(0).clone().setZ(HOME.getZ() + 0.8)),
                "shoved most of a block off its leg's line, it looks at the ground again");
    }

    @Test
    void aLeaderOnTheEdgeOfAStepGetsItsPointAtTheFootOfTheNextStep() {
        // Steps down across the way: a block lower at x 10, another from x 11 on.
        MarchRoute.Ground stairs = (x, feet, z) -> {
            int height = x >= 11 ? FEET - 2 : x == 10 ? FEET - 1 : FEET;
            return Math.abs(x) > 200 || Math.abs(z) > 200 || Math.abs(height - feet) > 1 ? MarchRoute.Ground.NO_FOOTING : height;
        };
        Sim sim = new Sim(stairs, 3, 2400);
        while (sim.march.phase() == March.Phase.FORMING) sim.step();
        // The leader rests on the edge of the upper block, its feet at that block's height and its middle over the
        // first step down. Its point, 0.85 blocks on, is over the second.
        Vector onTheEdge = new Vector(10.3, FEET, HOME.getZ());
        Vector point = sim.march.spot(sim.ids.get(0), onTheEdge, sim.now, stairs);
        assertTrue(point.getX() >= 11, "its point is over the second step: " + point);
        assertEquals(FEET - 2, point.getY(), 1.0E-9, "at the height of the ground there, two steps under its feet");
        assertTrue(sim.march.walksLeg(sim.ids.get(0), onTheEdge), "and it walks straight at it");
    }

    @Test
    void theLeadersWalkingPointIsNeverOverAHoleItsLegSteppedOver() {
        // A leg slanting across the blocks, 0.8 along x for 0.6 along z. Standing on it every half block from its
        // start misses the corners of some blocks it crosses: the first such block past 3 blocks out is a hole.
        double dx = 0.8, dz = 0.6;
        Set<Long> stoodOn = new HashSet<>();
        for (int half = 0; half <= 48; half++)
            stoodOn.add(column(HOME.getX() + dx * half * 0.5, HOME.getZ() + dz * half * 0.5));
        long hole = Long.MIN_VALUE;
        for (double along = 3; along < 20 && hole == Long.MIN_VALUE; along += 0.01) {
            long crossed = column(HOME.getX() + dx * along, HOME.getZ() + dz * along);
            if (!stoodOn.contains(crossed)) hole = crossed;
        }
        assertTrue(hole != Long.MIN_VALUE, "the leg crosses a block its run never stood on");
        long pit = hole;
        MarchRoute.Ground holed = (x, feet, z) -> Math.abs(x) > 200 || Math.abs(z) > 200 || column(x, z) == pit
                || Math.abs(feet - FEET) > 1 ? MarchRoute.Ground.NO_FOOTING : FEET;
        assertEquals(24.0, MarchRoute.run(holed, HOME, dx, dz, 24).length(), 1.0E-9, "the run steps over the hole");
        Sim sim = new Sim(holed, 2400, new double[]{dx, dz}, 1D, 1D, 1D, 1D, 1D);
        while (sim.march.phase() == March.Phase.FORMING) sim.step();
        Vector before = sim.at(0).clone();
        for (int tick = 0; tick < 400; tick++) {
            sim.step();
            Vector point = sim.leaderPoint;
            assertTrue(holed.standing((int) Math.floor(point.getX()), FEET, (int) Math.floor(point.getZ())) == FEET,
                    "the leader's point is on ground at tick " + tick + ": " + point);
        }
        assertFalse(sim.march.over(), "the march goes on");
        assertTrue(sim.at(0).distance(before) > 12, "the leader walked on past the hole: " + sim.at(0));
    }

    private static long column(double x, double z) {
        return ((long) Math.floor(x) << 32) ^ ((long) Math.floor(z) & 0xFFFFFFFFL);
    }

    @Test
    void aMarchingLineStaysStraightAndEvenlySpaced() {
        Sim sim = new Sim(OPEN, 6, 2400);
        while (sim.at(0).getX() < 25) {
            sim.step();
            assertFalse(sim.march.over());
        }
        for (int rank = 0; rank <= 6; rank++)
            assertEquals(HOME.getZ(), sim.at(rank).getZ(), 0.05, "rank " + rank + " is on the line");
        assertEvenlySpaced(sim, 6);
    }

    @Test
    void slowerMarchersKeepTheirPlaces() {
        // Wild pets roll speed stats from 0.8 to 1; a follower slower than the leader must not drop back.
        Sim sim = new Sim(OPEN, 2400, 1.0, 0.8, 1.0, 0.85, 0.95, 0.8);
        while (sim.at(0).getX() < 40) {
            sim.step();
            assertFalse(sim.march.over());
        }
        for (int rank = 0; rank <= 5; rank++)
            assertEquals(HOME.getZ(), sim.at(rank).getZ(), 0.05, "rank " + rank + " is on the line");
        assertEvenlySpaced(sim, 5);
    }

    /** Followers about a spacing apart, and the leader at most a block more ahead of the first. */
    private static void assertEvenlySpaced(Sim sim, int followers) {
        double lead = sim.at(0).getX() - sim.at(1).getX();
        assertTrue(lead > SPACING - 0.3 && lead < SPACING + 1.0, "the leader ahead of rank 1: " + lead);
        for (int rank = 2; rank <= followers; rank++) {
            double gap = sim.at(rank - 1).getX() - sim.at(rank).getX();
            assertTrue(gap > SPACING - 0.3 && gap < SPACING + 0.3, "gap ahead of rank " + rank + ": " + gap);
        }
    }

    @Test
    void itTurnsWithAHairpinAndLeadsTheLineBackWhereItWasCalled() {
        Sim sim = new Sim(OPEN, 5, 600);
        double farthest = 0, widest = 0;
        boolean passedBeside = false;
        while (!sim.march.over()) {
            sim.step();
            assertTrue(sim.now < Sim.START + 3000, "the march ends");
            Vector leader = sim.at(0);
            farthest = Math.max(farthest, leader.getX() - HOME.getX());
            widest = Math.max(widest, leader.getZ() - HOME.getZ());
            // On the way back, the leader passes the last marcher still on its way out, a lane away.
            if (sim.march.phase() == March.Phase.HOME && Math.abs(leader.getX() - sim.at(5).getX()) < 1
                    && leader.getZ() - sim.at(5).getZ() > 2.5) passedBeside = true;
        }
        assertTrue(farthest > 20, "walked out " + farthest);
        assertEquals(March.LANE, widest, 0.6, "the hairpin's walk to the side");
        assertTrue(passedBeside, "the way back passes beside the way out");
        assertTrue(sim.at(0).distance(HOME) <= March.ARRIVE + 0.5, "ended back home: " + sim.at(0));
    }

    @Test
    void aWallAheadTurnsTheLineWithoutCrossingIt() {
        Sim sim = new Sim(ground((x, z) -> x == 14), 4, 2400);
        for (int tick = 0; tick < 600; tick++) {
            sim.step();
            for (int rank = 0; rank <= 4; rank++) assertTrue(sim.at(rank).getX() < 14, "nobody crosses the wall");
        }
        assertFalse(sim.march.over());
        assertTrue(Math.abs(sim.at(0).getZ() - HOME.getZ()) > 20, "it went on along the wall: " + sim.at(0));
    }

    @Test
    void aLeaderThatTurnsRightRoundAndWalksOnTakesNoStuckTurn() {
        // A gap 3 blocks wide with a wall across its far end. The leader's leg ends before the wall, and from there
        // the only way that runs 6 blocks is the way it came, so it turns right round, with no stuck turn. It then
        // walks back past the place the march last saw it make progress at, and is within a block of that place, out
        // and back, for up to 3 blocks of walking. A stuck count asks for a block in 5 seconds, 0.2 blocks a second.
        // A marching penguin walks 0.5 to 0.7, and slower over steps, so those 3 blocks can take the 5 seconds: it
        // depends where in a block the turn falls. So the gap is walked at every pace from 0.3 blocks a second, half
        // as fast again as the count asks for, to 0.6, and at none may a leader that walks count as stuck.
        MarchRoute.Ground gap = ground((x, z) -> x >= 14 || x >= 2 && Math.abs(z) > 1);
        for (int step = 0; step <= 20; step++) {
            // The leader walks at 85% of the sim's pace, toward a point 0.85 blocks ahead.
            double pace = (0.3 + 0.015 * step) / 20 / 0.85;
            Sim sim = new Sim(gap, 2, 6000);
            sim.pace = pace;
            double farthest = 0;
            boolean back = false;
            for (int tick = 0; tick < 2500 && !back; tick++) {
                sim.step();
                assertFalse(sim.march.over(), "the march goes on, at pace " + pace);
                farthest = Math.max(farthest, sim.at(0).getX());
                back = sim.at(0).getX() < farthest - 6;
            }
            assertTrue(back, "the leader turned round in the gap and walked 6 blocks back, at pace " + pace);
            assertTrue(farthest > 10 && farthest < 14, "it turned at the end of its leg, before the wall: " + farthest);
            assertEquals(0, sim.march.stuckTurns(), "a leader that walks takes no stuck turn, at pace " + pace);
        }
    }

    @Test
    void aLeaderHeldInPlaceStillCountsAsStuckOnAStraightLegAfterATurnAndNearHome() {
        // On a straight leg: held where it is, it turns after the 5 seconds a stuck count runs, and not before.
        Sim straight = new Sim(OPEN, 2, 6000);
        while (straight.at(0).getX() < 8) straight.step();
        straight.held.add(straight.ids.get(0));
        for (int tick = 0; tick < March.STUCK_TICKS - 45; tick++) straight.step();
        assertEquals(0, straight.march.stuckTurns(), "not stuck yet");
        for (int tick = 0; tick < 90; tick++) straight.step();
        assertEquals(1, straight.march.stuckTurns(), "held for 5 seconds on a straight leg, it is stuck");

        // Just after a turn: the count that the turn started again still runs out.
        Sim turned = new Sim(ground((x, z) -> x >= 14 || x >= 2 && Math.abs(z) > 1), 2, 6000);
        double farthest = 0;
        while (turned.at(0).getX() > farthest - 0.5) {
            turned.step();
            farthest = Math.max(farthest, turned.at(0).getX());
        }
        turned.held.add(turned.ids.get(0));
        for (int tick = 0; tick < March.STUCK_TICKS + 5; tick++) turned.step();
        assertEquals(1, turned.march.stuckTurns(), "held for 5 seconds after it turned round, it is stuck");

        // Near home, where each tick's leg is a new one aimed at home again: none of those is a turn.
        Sim home = new Sim(OPEN, 2, 600);
        while (home.march.phase() != March.Phase.HOME || home.at(0).distance(HOME) > 4.5) {
            home.step();
            assertFalse(home.march.over(), "the leader comes home");
        }
        home.held.add(home.ids.get(0));
        for (int tick = 0; tick < March.STUCK_TICKS + 45; tick++) home.step();
        assertEquals(1, home.march.stuckTurns(), "held for 5 seconds within reach of home, it is stuck");
    }

    @Test
    void aLeaderOnItsWayHomeAimsForHomeAgainAfterALegRoundSomething() {
        // A gap 3 blocks wide and 58 long, open at the end the march is called from. Half the march's time is up
        // while the leader is inside it. There is no room for a hairpin, so it turns for home where it is.
        MarchRoute.Ground gap = ground((x, z) -> x >= 60 || x >= 2 && Math.abs(z) > 1);
        Sim sim = new Sim(gap, 2, 400);
        while (sim.march.phase() != March.Phase.HOME) {
            sim.step();
            assertFalse(sim.march.over(), "the march goes on");
        }
        double turned = sim.at(0).getX();
        assertTrue(turned > 15 && turned < 40, "it turned for home inside the gap: " + turned);
        // Something holds it on its way home until it counts as stuck. Its stuck turn finds one way that runs 6
        // blocks, the way back into the gap: that leg is the last it walks away from home.
        while (sim.at(0).getX() > turned - 4) sim.step();
        double heldAt = sim.at(0).getX();
        sim.held.add(sim.ids.get(0));
        for (int tick = 0; tick < March.STUCK_TICKS + 5; tick++) sim.step();
        assertEquals(1, sim.march.stuckTurns(), "held for 5 seconds, it is stuck and turns");
        sim.held.clear();
        double deepest = heldAt;
        for (int tick = 0; tick < 1200 && !sim.march.over(); tick++) {
            sim.step();
            deepest = Math.max(deepest, sim.at(0).getX());
        }
        assertTrue(deepest > heldAt + 3, "its stuck turn led it back into the gap: " + deepest);
        assertTrue(deepest <= heldAt + March.DETOUR_LEG + 0.5,
                "after that one leg it headed home, the way being open, and did not walk on into the gap: held at "
                        + heldAt + ", went to " + deepest);
        assertTrue(sim.march.over(), "the march ended");
        assertTrue(sim.at(0).distance(HOME) <= March.ARRIVE + 0.5, "back where it was called: " + sim.at(0));
        assertEquals(1, sim.march.stuckTurns(), "with no further stuck turn");
    }

    @Test
    void theTrailFindsTheFootprintNearestAPlaceAmongThoseBehindAGivenOne() {
        // Footprints every quarter block from x 0.5 to x 10.5, the newest last.
        MarchTrail trail = new MarchTrail(HOME, 1, 0);
        for (double x = 0.75; x <= 10.5; x += 0.25) trail.step(new Vector(x, FEET, 0.5), 30);
        assertEquals(4.0, trail.nearest(new Vector(6.5, FEET, 0.9), 0, 1), 1.0E-9, "the footprint beside it, 4 blocks back");
        assertEquals(5.0, trail.nearest(new Vector(6.5, FEET, 0.9), 5, 1.5), 1.0E-9,
                "of those 5 blocks back or more, the nearest is the one 5 back");
        assertEquals(-1, trail.nearest(new Vector(6.5, FEET, 0.9), 5, 1), 1.0E-9, "which is more than a block from it");
        assertEquals(-1, trail.nearest(new Vector(6.5, FEET, 1.7), 0, 1), 1.0E-9, "more than a block to the side is off the trail");
        assertEquals(4.0, trail.nearest(new Vector(6.5, FEET - 1, 0.5), 0, 1), 1.0E-9, "a step below the footprints is on it");
        assertEquals(-1, trail.nearest(new Vector(6.5, FEET - 2, 0.5), 0, 1), 1.0E-9, "two below is not");
        assertEquals(-1, trail.nearest(new Vector(-3, FEET, 0.5), 0, 1), 1.0E-9,
                "behind the oldest footprint the trail runs on straight, and that run is no footprint");
        assertEquals(-1, trail.nearest(new Vector(0.5, FEET, 0.5), 12, 1), 1.0E-9, "nor is there one 12 blocks back");
    }

    @Test
    void theWayOnAlongTheTrailCountsGroundAndNotTheHeightOfAStep() {
        // Footprints along +x, and at x 5 the leader rose a block: two footprints there, one over the other.
        MarchTrail trail = new MarchTrail(HOME, 1, 0);
        for (double x = 0.75; x <= 5; x += 0.25) trail.step(new Vector(x, FEET, 0.5), 60);
        trail.step(new Vector(5, FEET + 1, 0.5), 60);
        for (double x = 5.25; x <= 10; x += 0.25) trail.step(new Vector(x, FEET + 1, 0.5), 60);
        // A body at the foot of the step, 0.4 short of it: 5 blocks of trail on top, 1 up the step, 0.4 below.
        double self = trail.nearest(new Vector(4.6, FEET, 0.5), 0, 1);
        assertEquals(6.4, self, 1.0E-9, "on the trail at the foot of the step");
        Vector on = trail.ahead(self, 1, 0);
        assertEquals(5.6, on.getX(), 1.0E-9, "a block on across the ground is past the step, not the top of it");
        assertEquals(FEET + 1, on.getY(), 1.0E-9, "and up it");
        assertEquals(new Vector(5, FEET, 0.5), trail.ahead(self, 1, 6), "but no further on than its place, here the step's foot");
        assertEquals(new Vector(-1, FEET, 0.5), trail.ahead(20, 1, 12),
                "from behind the oldest footprint, 10.5 blocks back, the way on is its place: 12 back, 1.5 behind it");
    }

    @Test
    void whereTheTrailDoublesBackABodyIsOnTheStretchThatLeadsToItsPlaceSoonest() {
        // Out along +x to x 10 and back along the same line to x 6, where the leader is now.
        MarchTrail trail = new MarchTrail(HOME, 1, 0);
        for (double x = 0.75; x <= 10; x += 0.25) trail.step(new Vector(x, FEET, 0.5), 60);
        for (double x = 9.75; x >= 6; x -= 0.25) trail.step(new Vector(x, FEET, 0.5), 60);
        Vector body = new Vector(8, FEET, 0.6);
        assertEquals(2.0, trail.nearest(body, 0, 1), 1.0E-9, "beside both stretches, it is on the way back, 2 blocks behind the leader");
        assertEquals(2.5, trail.nearest(body, 2.5, 1), 1.0E-9,
                "with its place half a block further back on the way back, within a block of it: at its place");
        assertEquals(6.0, trail.nearest(body, 3.5, 1), 1.0E-9,
                "with its place more than a block off, near the turn: still on the way out, 2 blocks short of the turn");
    }

    @Test
    void aFollowerOnTheFootprintsWalksAtTheOneABlockAheadOfItOnItsWayToItsPlace() {
        Sim forming = new Sim(OPEN, 4, 2400);
        for (int tick = 0; tick < 5; tick++) forming.step();
        assertNull(forming.march.footprint(forming.ids.get(2), forming.at(2), forming.now - 1), "not while the line forms");

        Sim sim = new Sim(OPEN, 4, 2400);
        while (sim.at(0).getX() < 25) sim.step();
        long asked = sim.now - 1;
        UUID second = sim.ids.get(2);
        Vector place = sim.march.spot(second, sim.at(2), asked, OPEN);
        assertEquals(place, sim.march.footprint(second, sim.at(2), asked), "within a block of its place, its place itself");
        Vector behind = new Vector(place.getX() - 6, FEET, HOME.getZ());
        Vector ahead = sim.march.footprint(second, behind, asked);
        assertNotNull(ahead, "6 blocks behind its place on the footprints");
        assertEquals(behind.getX() + March.FOOTPRINT_AHEAD, ahead.getX(), 1.0E-6, "the footprint a block ahead of it");
        assertEquals(HOME.getZ(), ahead.getZ(), 0.05);
        assertNull(sim.march.footprint(second, behind.clone().setZ(HOME.getZ() + 1.6), asked),
                "more than a block beside the footprints it goes for its place as before");
        assertNull(sim.march.footprint(second, behind.clone().setY(FEET - 2), asked), "and so two blocks under them");
        assertNull(sim.march.footprint(sim.ids.get(0), sim.at(0), asked), "the leader walks its leg, not footprints");
    }

    @Test
    void aFollowerThatFellBehindWalksTheLeadersFootprintsAndNotItsOwnWayAcross() {
        // A wall across the way at x 14 turns the line along it. The last follower is held where it stands until
        // the leader is well round the turn, 9 blocks and more ahead of it by the way the leader walked.
        Sim sim = new Sim(ground((x, z) -> x == 14), 4, 6000);
        List<Vector> walked = new ArrayList<>();
        UUID last = sim.ids.get(4);
        while (sim.at(0).getX() < 8) step(sim, walked);
        sim.held.add(last);
        while (Math.abs(sim.at(0).getZ() - HOME.getZ()) < 5) step(sim, walked);
        sim.held.clear();
        // From there it catches up. It used to be handed to its body's path, which is its own way to its place:
        // across the inside of the turn, over ground the leader never walked. Now it never leaves the footprints.
        double farthest = 0;
        for (int tick = 0; tick < 300; tick++) {
            step(sim, walked);
            double off = Double.MAX_VALUE;
            for (Vector footprint : walked)
                off = Math.min(off, Math.hypot(footprint.getX() - sim.at(4).getX(), footprint.getZ() - sim.at(4).getZ()));
            farthest = Math.max(farthest, off);
        }
        assertTrue(farthest <= 0.5, "it kept to the way the leader walked: " + farthest + " blocks off it at most");
        assertTrue(sim.at(3).distance(sim.at(4)) < SPACING + 1, "and caught up with the marcher ahead of it: "
                + sim.at(3).distance(sim.at(4)));
    }

    /** One tick of a sim, keeping where its leader walked. */
    private static void step(Sim sim, List<Vector> walked) {
        sim.step();
        assertFalse(sim.march.over(), "the march goes on");
        walked.add(sim.at(0).clone());
    }

    @Test
    void aGapClosesAndAMarcherBackFromAwayJoinsAtTheBack() {
        Sim sim = new Sim(OPEN, 4, 2400);
        while (sim.at(0).getX() < 15) sim.step();
        UUID second = sim.ids.get(2), last = sim.ids.get(4);
        Vector before = sim.march.spot(last, sim.positions.get(last), sim.now, OPEN);
        assertEquals(4 * SPACING, sim.at(0).getX() - before.getX(), 0.3, "fourth in line");
        sim.resting.add(second);
        for (int tick = 0; tick < March.KEEP_TICKS + 2; tick++) sim.step();
        Vector closed = sim.march.spot(last, sim.positions.get(last), sim.now, OPEN);
        assertEquals(3 * SPACING, sim.at(0).getX() - closed.getX(), 0.3, "the last marcher moved up into the gap");
        sim.resting.remove(second);
        sim.step();
        assertEquals(second, sim.march.marchers().get(4), "back from away, it joins at the back");
    }

    @Test
    void aLeaderAwayTooLongHandsTheLineToTheNextMarcher() {
        Sim sim = new Sim(OPEN, 3, 2400);
        while (sim.at(0).getX() < 10) sim.step();
        UUID leader = sim.ids.get(0), next = sim.ids.get(1);
        sim.resting.add(leader);
        for (int tick = 0; tick <= March.LEADER_TICKS + 1; tick++) sim.step();
        assertEquals(next, sim.march.leader(), "the first marcher there leads");
        assertEquals(leader, sim.march.marchers().get(3), "the old leader is at the back");
        double before = sim.positions.get(next).getX();
        for (int tick = 0; tick < 200; tick++) sim.step();
        assertFalse(sim.march.over());
        assertTrue(sim.positions.get(next).getX() > before + 10, "the new leader leads on");
    }

    @Test
    void aMarchEndsWhenTooFewAreLeftWhenNobodyIsThereAndAtTheHardCap() {
        Sim few = new Sim(OPEN, 2, 2400);
        few.step();
        few.march.remove(few.ids.get(1));
        assertFalse(few.march.over());
        few.march.remove(few.ids.get(2));
        assertTrue(few.march.over(), "one marcher is no march");
        assertNull(few.march.spot(few.ids.get(0), HOME, few.now, OPEN));

        Sim gone = new Sim(OPEN, 3, 2400);
        gone.step();
        gone.resting.addAll(gone.ids);
        for (int tick = 0; tick <= March.LEADER_TICKS + 1 && !gone.march.over(); tick++) gone.step();
        assertTrue(gone.march.over(), "nobody there for the leader's time is the end");

        Sim capped = new Sim(ground((x, z) -> false), 2, 100);
        capped.march.sweep(capped.now + March.FORM_TICKS + 200);
        assertTrue(capped.march.over(), "the hard cap: twice the duration after forming");
    }

    /** A march of a leader at HOME and {@code followers} marchers scattered round it, heading +x. */
    private static final class Sim {
        final March march;
        final MarchRoute.Ground ground;
        final List<UUID> ids = new ArrayList<>();
        final Map<UUID, Vector> positions = new LinkedHashMap<>();
        final Set<UUID> resting = new HashSet<>();
        /** Marchers that ask for their spot and cannot walk to it: something holds them where they are. */
        final Set<UUID> held = new HashSet<>();
        static final long START = 1000;
        long now = START;
        /** Blocks a tick at a speed stat of 1. A test of how long a walk takes sets the pace a penguin has in game. */
        double pace = PACE;

        final Map<UUID, Double> speeds = new LinkedHashMap<>();
        /** The spot the march last gave its leader. */
        Vector leaderPoint;

        Sim(MarchRoute.Ground ground, int followers, long durationTicks) {
            this(ground, durationTicks, 1D, ones(followers));
        }

        /** As above, with the leader's speed stat and each follower's. */
        Sim(MarchRoute.Ground ground, long durationTicks, double leaderSpeed, double... followerSpeeds) {
            this(ground, durationTicks, new double[]{1D, 0D}, leaderSpeed, followerSpeeds);
        }

        /** As above, setting off along the unit heading {dx, dz}. */
        Sim(MarchRoute.Ground ground, long durationTicks, double[] heading, double leaderSpeed,
            double... followerSpeeds) {
            this.ground = ground;
            int followers = followerSpeeds.length;
            UUID leader = new UUID(0, 0);
            ids.add(leader);
            positions.put(leader, HOME.clone());
            speeds.put(leader, leaderSpeed);
            double[] all = new double[followers + 1];
            all[0] = leaderSpeed;
            List<UUID> marchers = new ArrayList<>();
            Vector[] at = new Vector[followers];
            for (int index = 0; index < followers; index++) {
                UUID id = new UUID(0, index + 1);
                ids.add(id);
                marchers.add(id);
                // Scattered round the leader, a few blocks off, on either side.
                at[index] = new Vector(HOME.getX() + (index % 3) - 1, FEET, HOME.getZ() + (index % 2 == 0 ? 3 : -3));
                positions.put(id, at[index].clone());
                speeds.put(id, followerSpeeds[index]);
                all[index + 1] = followerSpeeds[index];
            }
            march = new March(leader, HOME, marchers, at, all, heading[0], heading[1], SPACING, now, durationTicks);
        }

        private static double[] ones(int count) {
            double[] ones = new double[count];
            java.util.Arrays.fill(ones, 1D);
            return ones;
        }

        Vector at(int index) {
            return positions.get(ids.get(index));
        }

        void step() {
            for (UUID id : march.marchers()) {
                if (resting.contains(id)) continue;
                Vector position = positions.get(id);
                Vector spot = march.spot(id, position, now, ground);
                if (march.over()) break;
                if (spot != null && id.equals(march.leader())) leaderPoint = spot.clone();
                if (spot != null && !held.contains(id)) {
                    Vector footprint = march.footprint(id, position, now);
                    if (footprint != null) follow(position, footprint, spot, speeds.get(id));
                    else walk(position, spot, speeds.get(id), march.walksLeg(id, position));
                }
                // A body stands on the ground under it.
                int feet = ground.standing((int) Math.floor(position.getX()), (int) Math.floor(position.getY()),
                        (int) Math.floor(position.getZ()));
                if (feet != MarchRoute.Ground.NO_FOOTING) position.setY(feet);
            }
            march.sweep(now);
            now++;
        }

        /**
         * One tick of a follower on the leader's footprints: march.lua asks for its place, and steer_onto walks it
         * straight at the footprint the march gives it on the way there, with no look at the ground. It hurries when
         * its place is more than 3 blocks off, eases off over the last block to that footprint, and stands still
         * within 0.3 of its place.
         */
        private void follow(Vector position, Vector footprint, Vector spot, double speed) {
            double away = Math.hypot(spot.getX() - position.getX(), spot.getZ() - position.getZ());
            if (Math.abs(spot.getY() - position.getY()) <= RISE && away <= CLOSE) return;
            double dx = footprint.getX() - position.getX(), dz = footprint.getZ() - position.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < 1.0E-9) return;
            double hurry = away > BEHIND ? CATCH_UP : 1;
            double step = Math.min(distance,
                    Math.max(floor(), pace * speed * hurry * Math.max(SLOWEST_SHARE, Math.min(1, distance))));
            position.setX(position.getX() + dx / distance * step);
            position.setZ(position.getZ() + dz / distance * step);
        }

        /**
         * One tick of march.lua's walk to a spot through steer_onto, for a marcher of this speed stat. A leader on
         * its leg walks straight at its point with no look at the ground, as steer_onto walks it.
         */
        private void walk(Vector position, Vector spot, double speed, boolean onItsLeg) {
            double dx = spot.getX() - position.getX(), dz = spot.getZ() - position.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            double hurry = distance > BEHIND ? CATCH_UP : 1;
            boolean offItsLevel = Math.abs(spot.getY() - position.getY()) > RISE;
            if (onItsLeg && distance <= REACH) {
                if (distance < 1.0E-9) return;
                double pace = Math.max(floor(), this.pace * speed * hurry * Math.max(SLOWEST_SHARE, Math.min(1, distance)));
                double step = Math.min(pace, distance);
                position.setX(position.getX() + dx / distance * step);
                position.setZ(position.getZ() + dz / distance * step);
                return;
            }
            if (offItsLevel ? !stepped(position, spot, distance) : distance > CLOSE && !level(position, spot, distance)) {
                // Off its level and no single step away, or on its level with ground between that is not: the
                // pathfinder's walk, at its pace with no easing, which ends a block short.
                if (distance <= PATH_SHORT) return;
                double step = Math.min(pace * speed * hurry, distance - PATH_SHORT);
                position.setX(position.getX() + dx / distance * step);
                position.setZ(position.getZ() + dz / distance * step);
                return;
            }
            // On its level a marcher stands still once it is close. A step away it is never close: march.lua measures
            // to the spot's own height.
            if (!offItsLevel && distance <= CLOSE || distance < 1.0E-9) return;
            double pace = Math.max(floor(), this.pace * speed * hurry * Math.max(SLOWEST_SHARE, Math.min(1, distance)));
            double step = Math.min(pace, distance);
            position.setX(position.getX() + dx / distance * step);
            position.setZ(position.getZ() + dz / distance * step);
        }

        /** steer_onto's slowest walk at this sim's pace: see FLOOR. */
        private double floor() {
            return FLOOR * pace / PACE;
        }

        /** steer_onto's rule for a straight walk on the body's level, on this ground: see PetLuaTable.WalkPath.level. */
        private boolean level(Vector from, Vector to, double distance) {
            if (distance > REACH) return false;
            int feet = (int) Math.floor(from.getY());
            int steps = Math.max(1, (int) Math.ceil(distance / 0.25));
            for (int step = 1; step <= steps; step++) {
                double share = (double) step / steps;
                if (ground.standing((int) Math.floor(from.getX() + (to.getX() - from.getX()) * share), feet,
                        (int) Math.floor(from.getZ() + (to.getZ() - from.getZ()) * share)) != feet) return false;
            }
            return true;
        }

        /** steer_onto's rule for a straight walk across a step, on this ground: see PetLuaTable.WalkPath.stepped. */
        private boolean stepped(Vector from, Vector to, double distance) {
            if (distance > REACH) return false;
            int feet = (int) Math.floor(from.getY());
            int steps = Math.max(1, (int) Math.ceil(distance / 0.25));
            boolean climbs = false;
            for (int step = 0; step <= steps; step++) {
                double share = (double) step / steps;
                int next = ground.standing((int) Math.floor(from.getX() + (to.getX() - from.getX()) * share), feet,
                        (int) Math.floor(from.getZ() + (to.getZ() - from.getZ()) * share));
                if (next == MarchRoute.Ground.NO_FOOTING) return false;
                if (next > feet && step > 0) climbs = true;
                feet = next;
            }
            return feet == (int) Math.floor(to.getY()) && (!climbs || distance <= CLIMB);
        }
    }
}
