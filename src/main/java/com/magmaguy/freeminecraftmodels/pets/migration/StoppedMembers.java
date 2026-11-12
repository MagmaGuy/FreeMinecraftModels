package com.magmaguy.freeminecraftmodels.pets.migration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Tells the members of a flock that have flown out of the chunks the server ticks from those that only pause there.
 * <p>
 * A body past those chunks stays loaded and valid, but neither it nor its Mind runs, so it hangs in the air where it
 * stopped while the flock flies on. The flock looks at every member each tick. A member whose body ticks starts its
 * count over; one whose body has gone without ticking for the whole patience, tick after tick, is handed back for the
 * flock to take out of the world. So a member late to start ticking, or in a chunk that ticks again before the
 * patience is up, keeps flying, and each member is judged where its own body is: whichever player is near it keeps it.
 * A member that left the flock by itself (tamed, killed, lost) is let go of and never handed back. Server thread only.
 */
final class StoppedMembers<M> {
    private final int patience;
    // Ticks in a row each member's body has gone without ticking; a member whose body ticks has no entry.
    private final Map<M, Integer> unticked = new IdentityHashMap<>();

    /** {@code patience} is how many ticks in a row a body may go without ticking before its member goes. */
    StoppedMembers(int patience) {
        this.patience = Math.max(1, patience);
    }

    /**
     * One tick's look at the flock. Takes out of {@code members} those that {@code left} by themselves, and those
     * whose bodies have now gone the whole patience without {@code ticking}; returns the second kind, in the members'
     * order, for the flock to remove from the world.
     */
    List<M> sweep(Collection<M> members, Predicate<M> left, Predicate<M> ticking) {
        List<M> stopped = null;
        for (Iterator<M> each = members.iterator(); each.hasNext(); ) {
            M member = each.next();
            if (left.test(member)) {
                each.remove();
                unticked.remove(member);
                continue;
            }
            if (ticking.test(member)) {
                unticked.remove(member);
                continue;
            }
            if (unticked.merge(member, 1, Integer::sum) < patience) continue;
            each.remove();
            unticked.remove(member);
            if (stopped == null) stopped = new ArrayList<>();
            stopped.add(member);
        }
        return stopped == null ? List.of() : stopped;
    }
}
