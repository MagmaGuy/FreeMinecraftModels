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
 * spot. So a whole march plays out without a server.
 */
class MarchTest {
    private static final int FEET = 64;
    private static final Vector HOME = new Vector(0.5, FEET, 0.5);
    private static final double SPACING = 1.5;
    /** Blocks a tick at a speed stat of 1, and march.lua's hurry, stop and hurry distance. */
    private static final double PACE = 0.15, CATCH_UP = 1.6, CLOSE = 0.3, BEHIND = 3;
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
        static final long START = 1000;
        long now = START;

        final Map<UUID, Double> speeds = new LinkedHashMap<>();

        Sim(MarchRoute.Ground ground, int followers, long durationTicks) {
            this(ground, durationTicks, 1D, ones(followers));
        }

        /** As above, with the leader's speed stat and each follower's. */
        Sim(MarchRoute.Ground ground, long durationTicks, double leaderSpeed, double... followerSpeeds) {
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
            march = new March(leader, HOME, marchers, at, all, 1, 0, SPACING, now, durationTicks);
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
                if (spot != null) walk(position, spot, speeds.get(id));
            }
            march.sweep(now);
            now++;
        }

        /** One tick of march.lua's walk to a spot through steer_onto, for a marcher of this speed stat. */
        private static void walk(Vector position, Vector spot, double speed) {
            double dx = spot.getX() - position.getX(), dz = spot.getZ() - position.getZ();
            double distance = Math.sqrt(dx * dx + dz * dz);
            position.setY(spot.getY());
            if (distance <= CLOSE) return;
            double hurry = distance > BEHIND ? CATCH_UP : 1;
            double pace = Math.max(FLOOR, PACE * speed * hurry * Math.max(SLOWEST_SHARE, Math.min(1, distance)));
            double step = Math.min(pace, distance);
            position.setX(position.getX() + dx / distance * step);
            position.setZ(position.getZ() + dz / distance * step);
        }
    }
}
