package com.magmaguy.freeminecraftmodels.pets;

/**
 * When the pet watchdog brings an owned pet to its owner itself.
 * <p>
 * A pet catches up through its own behaviors: follow_owner teleports it up behind its owner once they are past its
 * teleport distance. Behaviors run in the pet's Mind, and a Mind only ticks while its body's chunk ticks entities. A
 * pet whose owner respawned or teleported far away, or flew off faster than it could follow, is left in a chunk that
 * stays loaded but no longer ticks, on any server whose view distance reaches further than its simulation distance:
 * its body stays valid, its Mind never runs again, and it stood there until its owner put it away and took it out
 * again from the pets menu.
 * <p>
 * The watchdog runs on FreeMinecraftModels' own clock, whatever the pet's chunk does. A pet it finds past its
 * teleport distance gets one watchdog period to catch up by itself, so a pet whose behaviors are running is left to
 * them; one still that far the next time is brought. A body that has not aged since the last look is not ticking and
 * is brought at once.
 */
final class PetCatchUp {
    /** How far round the point behind its owner a brought pet lands, in blocks: as follow_owner's own catch-up. */
    static final double RADIUS = 2D;
    /** How long a pet past its teleport distance is left to catch up by itself, in ticks: one watchdog period. */
    static final long PATIENCE_TICKS = 20L;
    /** A pet near enough, or one that is never brought, has no tick it was first seen far at. */
    static final long NEVER = Long.MIN_VALUE;

    enum Verdict {
        /** Within its teleport distance, or not to be brought at all: nothing to do, and nothing to remember. */
        NEAR,
        /** Past its teleport distance, and still left to catch up by itself. */
        WAIT,
        /** Past its teleport distance for too long, or not ticking: the watchdog brings it. */
        BRING
    }

    private PetCatchUp() {
    }

    /**
     * What the watchdog does with an owned pet that has a body in its owner's world and was not told to stay.
     * {@code withOwner} is whether its owner is someone it can be with now (see {@link PetControl#companion()});
     * {@code distance} how far it is from them; {@code ridden} whether they ride it; {@code frozen} whether its body
     * has not aged since the last look; {@code farSince} the tick it was first seen past its teleport distance, or
     * {@link #NEVER}; {@code now} the pet clock's tick.
     */
    static Verdict verdict(boolean withOwner, double distance, double teleportDistance, boolean ridden, boolean frozen,
                           long farSince, long now) {
        if (!withOwner || ridden || !(distance > teleportDistance)) return Verdict.NEAR;
        if (frozen) return Verdict.BRING;
        return farSince != NEVER && now - farSince >= PATIENCE_TICKS ? Verdict.BRING : Verdict.WAIT;
    }
}
