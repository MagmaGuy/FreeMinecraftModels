package com.magmaguy.freeminecraftmodels.pets.migration;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Which members a flock takes out of the sky because their bodies stopped ticking, and which it keeps. */
class StoppedMembersTest {
    private static final int PATIENCE = 5;
    private static final String NEAR = "near a player", FAR = "past the ticking chunks", OTHER = "near another player";
    private static final Predicate<String> NOBODY_LEFT = member -> false;

    private final StoppedMembers<String> stopped = new StoppedMembers<>(PATIENCE);

    private static List<String> flock(String... members) {
        return new ArrayList<>(List.of(members));
    }

    @Test
    void aMemberWhoseBodyTicksFliesTheWholeCrossing() {
        List<String> members = flock(NEAR);
        for (int tick = 0; tick < 1800; tick++)
            assertEquals(List.of(), stopped.sweep(members, NOBODY_LEFT, member -> true));
        assertEquals(List.of(NEAR), members);
    }

    @Test
    void aMemberGoesOnceItsBodyHasNotTickedForThePatience() {
        List<String> members = flock(FAR);
        for (int tick = 1; tick < PATIENCE; tick++)
            assertEquals(List.of(), stopped.sweep(members, NOBODY_LEFT, member -> false), "tick " + tick);
        assertEquals(List.of(FAR), stopped.sweep(members, NOBODY_LEFT, member -> false));
        assertEquals(List.of(), members);
    }

    @Test
    void aMemberLateToStartTickingKeepsFlying() {
        List<String> members = flock(NEAR);
        // Its chunk takes a moment to tick entities after it spawns.
        for (int tick = 1; tick < PATIENCE; tick++) stopped.sweep(members, NOBODY_LEFT, member -> false);
        for (int tick = 0; tick < 1800; tick++)
            assertEquals(List.of(), stopped.sweep(members, NOBODY_LEFT, member -> true));
        assertEquals(List.of(NEAR), members);
    }

    @Test
    void aBodyThatTicksAgainStartsItsCountOver() {
        List<String> members = flock(NEAR);
        // In and out of the ticking chunks at their edge, never out for the whole patience.
        for (int round = 0; round < 50; round++) {
            for (int tick = 1; tick < PATIENCE; tick++)
                assertEquals(List.of(), stopped.sweep(members, NOBODY_LEFT, member -> false));
            assertEquals(List.of(), stopped.sweep(members, NOBODY_LEFT, member -> true));
        }
        assertEquals(List.of(NEAR), members);
        // Then out for good: the whole patience again, not what was left of an earlier count.
        for (int tick = 1; tick < PATIENCE; tick++)
            assertEquals(List.of(), stopped.sweep(members, NOBODY_LEFT, member -> false));
        assertEquals(List.of(NEAR), stopped.sweep(members, NOBODY_LEFT, member -> false));
    }

    @Test
    void twoPlayersApartEachKeepTheMembersNearThem() {
        List<String> members = flock(NEAR, FAR, OTHER);
        Predicate<String> ticking = member -> !member.equals(FAR);
        List<String> gone = new ArrayList<>();
        for (int tick = 0; tick < 1800; tick++) gone.addAll(stopped.sweep(members, NOBODY_LEFT, ticking));
        assertEquals(List.of(FAR), gone);
        assertEquals(List.of(NEAR, OTHER), members);
    }

    @Test
    void membersThatStopAtDifferentTicksGoAtTheirOwn() {
        List<String> members = flock("first", "second");
        Set<String> out = new HashSet<>();
        List<Integer> wentAt = new ArrayList<>();
        for (int tick = 1; tick <= 20; tick++) {
            if (tick == 1) out.add("first");
            if (tick == 8) out.add("second");
            for (String gone : stopped.sweep(members, NOBODY_LEFT, member -> !out.contains(member))) {
                assertEquals(wentAt.isEmpty() ? "first" : "second", gone);
                wentAt.add(tick);
            }
        }
        // Each on the tick its own count reaches the patience.
        assertEquals(List.of(PATIENCE, 7 + PATIENCE), wentAt);
    }

    @Test
    void aMemberThatLeftByItselfIsLetGoOfAndNotTakenOut() {
        // Tamed: it is someone's pet now, wherever its body is.
        List<String> members = flock("tamed", NEAR);
        List<String> gone = new ArrayList<>();
        for (int tick = 0; tick < 1800; tick++)
            gone.addAll(stopped.sweep(members, member -> member.equals("tamed"), member -> !member.equals("tamed")));
        assertEquals(List.of(), gone);
        assertEquals(List.of(NEAR), members);
    }

    @Test
    void aMemberThatLeftWhileStoppedIsNotTakenOutLater() {
        List<String> members = flock("tamed");
        for (int tick = 1; tick < PATIENCE; tick++) stopped.sweep(members, NOBODY_LEFT, member -> false);
        assertEquals(List.of(), stopped.sweep(members, member -> true, member -> false));
        assertEquals(List.of(), members);
    }

    @Test
    void aFlockIsEmptyOnTheTickItsLastMemberGoes() {
        List<String> members = flock("one", "two", "three");
        for (int tick = 1; tick < PATIENCE; tick++) {
            stopped.sweep(members, NOBODY_LEFT, member -> false);
            assertEquals(3, members.size(), "tick " + tick);
        }
        assertEquals(3, stopped.sweep(members, NOBODY_LEFT, member -> false).size());
        assertTrue(members.isEmpty());
    }
}
