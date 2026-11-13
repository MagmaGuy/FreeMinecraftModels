package com.magmaguy.freeminecraftmodels.pets;

import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One march: a leader and the marchers behind it in single file, by body id. The leader walks straight legs out from
 * where it called the march, turns with a hairpin when half the march is up, and leads the line back; each marcher's
 * spot is its place in line times the spacing back along the leader's footprints. Marchers ask for their spots every
 * tick they march, which is how the march knows who is there: one that stops asking is away, and the ones behind it
 * close up. Positions come from the caller, so the rules run on any {@link MarchRoute.Ground}. Server thread only.
 */
final class March {
    /** A marcher that has not asked for its spot this long is away. */
    static final long KEEP_TICKS = 10L;
    /** Marchers enlisted by the call keep their places in line for this long while they come. */
    static final long JOIN_GRACE_TICKS = 60L;
    /** A leader away this long hands the line to the first marcher behind it who is there. */
    static final long LEADER_TICKS = 200L;
    /** A marcher away this long has left the march. */
    static final long LEAVE_TICKS = 600L;
    /** The line forms behind the leader for at most this long before it sets off. */
    static final long FORM_TICKS = 120L;
    /** A marcher this near its spot is in place, for forming up. */
    static final double FORMED = 1.5D;
    /**
     * The leader walks at this share of the slowest marcher's own pace, so every follower, whatever its speed stat, has
     * pace to spare to close up behind it.
     */
    static final double PACE = 0.85D;
    /**
     * The nearest the leader's walking point comes. steer_onto eases a walk off over its last block in proportion, so
     * the point's distance sets the leader's pace (see {@link #lookahead}); nearer than this, march.lua would stop it.
     */
    static final double MIN_LOOKAHEAD = 0.5D;
    /** A straight leg goes on from this far before its end, beyond any walking point, so the leader keeps its pace. */
    static final double LEG_MARGIN = 2.25D;
    /** The hairpin's walk to the side, so the way back passes beside the way out. */
    static final double LANE = 4D;
    /** A leader this near where the march was called, on the way back, ends it. */
    static final double ARRIVE = 2.5D;
    /** A leg round something in the way home, or after a stuck turn on it, before the leader aims for home again. */
    static final double DETOUR_LEG = 8D;
    /** A leader that has not moved a block in this long is stuck, and turns. */
    static final long STUCK_TICKS = 100L;
    /** A leader stuck this many times on one march gives up, and the march ends. */
    static final int MAX_REPLANS = 3;
    /** A leader this near the line of its leg, across it, walks the leg: see {@link #walksLeg}. */
    static final double ON_LEG = 0.5D;
    /** A follower this near the leader's footprints across walks them: see {@link #footprint}. */
    static final double ON_TRAIL = 1D;
    /** How far ahead of itself along the leader's footprints such a follower walks at, counted across the ground. */
    static final double FOOTPRINT_AHEAD = 1D;
    /**
     * Footprints are kept this much further back than the line needs for its places, for a follower that fell
     * behind to walk: as far as a march's call reaches by default, which is as far off as a marcher starts.
     */
    static final double TRAIL_SPARE = 12D;
    /**
     * A new leg whose heading lies this far round from the last one's, by its cosine, is a turn: a sixteenth of a
     * circle, the least a leader turns on purpose. A leg toward home that only aims again as the leader walks is not.
     */
    static final double TURN_COSINE = Math.cos(2D * Math.PI / MarchRoute.HEADINGS) + 1.0E-9D;

    enum Phase {FORMING, OUT, HAIRPIN, HOME}

    private static final long NEVER = Long.MIN_VALUE;

    private static final class Marcher {
        final UUID id;
        // Its speed stat, which its walks are times.
        final double speed;
        long seen = NEVER;
        Vector at;

        Marcher(UUID id, Vector at, double speed) {
            this.id = id;
            this.at = at;
            this.speed = speed;
        }
    }

    // The line, its leader first.
    private final List<Marcher> line = new ArrayList<>();
    private final MarchTrail trail;
    private final Vector home;
    private final double spacing;
    private final long calledAt, durationTicks, hardEnd;
    private Phase phase = Phase.FORMING;
    private long outEnd;
    // The leg the leader walks: from its origin along the unit heading for its length.
    private Vector legOrigin;
    private double headingX, headingZ, legLength;
    // A new leader walking up to the newest footprint before it leads on.
    private boolean catchingUp;
    private Vector progressAt;
    private long progressTick;
    private int replans;
    private boolean over;

    /**
     * A march called at {@code home} by the leader, the marchers following in the given order, each with its speed stat
     * in {@code speeds} (the leader's first), the line forming opposite the unit heading (dx, dz) it sets off on,
     * spacing blocks apart. It walks out for half its duration.
     */
    March(UUID leader, Vector home, List<UUID> marchers, Vector[] marcherPositions, double[] speeds, double dx,
          double dz, double spacing, long now, long durationTicks) {
        this.home = home.clone();
        this.spacing = spacing;
        this.calledAt = now;
        this.durationTicks = Math.max(1L, durationTicks);
        this.hardEnd = now + FORM_TICKS + 2L * this.durationTicks;
        line.add(new Marcher(leader, home.clone(), speeds[0]));
        for (int index = 0; index < marchers.size(); index++)
            line.add(new Marcher(marchers.get(index), marcherPositions[index].clone(), speeds[index + 1]));
        trail = new MarchTrail(home, dx, dz);
        headingX = dx;
        headingZ = dz;
    }

    Phase phase() {
        return phase;
    }

    boolean over() {
        return over;
    }

    long calledAt() {
        return calledAt;
    }

    /** How many times its leader has counted as stuck, and turned. */
    int stuckTurns() {
        return replans;
    }

    /** The marchers, leader first. */
    List<UUID> marchers() {
        List<UUID> ids = new ArrayList<>(line.size());
        for (Marcher marcher : line) ids.add(marcher.id);
        return ids;
    }

    /** How many marchers the line holds, leader included; with {@link #marcherAt} a walk through it with no copy. */
    int size() {
        return line.size();
    }

    /** The marcher at this place in line, 0 being the leader. */
    UUID marcherAt(int index) {
        return line.get(index).id;
    }

    UUID leader() {
        return line.isEmpty() ? null : line.get(0).id;
    }

    /** Whether this marcher is still in the line of a march that is not over. Changes nothing, unlike spot. */
    boolean includes(UUID id) {
        return !over && indexOf(id) >= 0;
    }

    /**
     * Whether this marcher, now at {@code at}, is the leader walking a leg of its march: the line has set off, it is
     * not a new leader still walking up to the head of the line, and it is within {@link #ON_LEG} of the leg's line.
     * The march found that leg's ground walkable a body wide, every half block of it, so the leader can walk straight
     * at the point {@link #spot} gives it with no further look at the ground between.
     */
    boolean walksLeg(UUID id, Vector at) {
        if (over || phase == Phase.FORMING || catchingUp || legOrigin == null || line.isEmpty()
                || !line.get(0).id.equals(id)) return false;
        double offX = at.getX() - legOrigin.getX(), offZ = at.getZ() - legOrigin.getZ();
        return Math.abs(offX * headingZ - offZ * headingX) <= ON_LEG;
    }

    /**
     * Where this marcher, now at {@code at}, should walk to: the leader's next step, the leader's own spot while the
     * line forms, or a follower's place in line. A follower back after being away goes to the back of the line. Null
     * when it is not in this march, or the march is over; the march may end on this very call.
     */
    Vector spot(UUID id, Vector at, long now, MarchRoute.Ground ground) {
        if (over) return null;
        int index = indexOf(id);
        if (index < 0) return null;
        Marcher marcher = line.get(index);
        boolean wasAway = away(marcher, now);
        marcher.seen = now;
        marcher.at = at.clone();
        if (index > 0 && wasAway && index < line.size() - 1) {
            line.remove(index);
            line.add(marcher);
            index = line.size() - 1;
        }
        if (index == 0) return lead(at, now, ground);
        return trail.behind(rank(index, now) * spacing);
    }

    /**
     * Where a follower, now at {@code at}, walks at on the way to its place in line: the leader's footprint
     * {@link #FOOTPRINT_AHEAD} ahead of where it is on them, or its place when that is nearer. Ask it after
     * {@link #spot} on the same tick. Null for the leader, while the line forms, for a follower whose place lies
     * behind the oldest footprint, and for one that is not on the footprints, more than {@link #ON_TRAIL} from them
     * across or more than a step above or below them: those go for their place as steer_onto takes them there.
     * <p>
     * A follower used to be sent to its place whatever lay between. Within 2 blocks, over ground steer_onto could
     * read, it walked straight. Any other time it was handed to its body's pathfinder: when its place was further
     * off than that, and at every edge the leader had crossed, where a footprint lies at the height of the upper
     * block over the lower ground, since the leader's box still rested on the edge. On a steep hillside that was 10
     * to 18 ticks in a hundred for every follower, 25 paths a march each. A path stops a block short of its end and
     * is asked for again a second later, so a follower 2 to 3 blocks behind fell further behind. And a path is the
     * pathfinder's own way, down drops of 2 and 3 blocks the leader's legs never take, from where the way back up
     * to the line is a long one. The footprints are ground the leader itself walked, one step at a time.
     */
    Vector footprint(UUID id, Vector at, long now) {
        if (over || phase == Phase.FORMING) return null;
        int index = indexOf(id);
        if (index <= 0) return null;
        double place = rank(index, now) * spacing;
        double self = trail.nearest(at, place, ON_TRAIL);
        return self < 0D ? null : trail.ahead(self, FOOTPRINT_AHEAD, place);
    }

    /**
     * The rules that need no marcher to ask: the hard cap, marchers that left, a leader away too long handing over to
     * the first marcher there, and a march with nobody left to lead or follow.
     */
    void sweep(long now) {
        if (over) return;
        if (now >= hardEnd) {
            over = true;
            return;
        }
        for (int index = line.size() - 1; index > 0; index--)
            if (gone(line.get(index), now, LEAVE_TICKS)) line.remove(index);
        if (!line.isEmpty() && gone(line.get(0), now, LEADER_TICKS)) {
            int next = -1;
            for (int index = 1; index < line.size(); index++)
                if (!away(line.get(index), now)) {
                    next = index;
                    break;
                }
            if (next < 0) {
                over = true;
                return;
            }
            Marcher old = line.remove(0);
            Marcher leader = line.remove(next - 1);
            line.add(0, leader);
            line.add(old);
            catchingUp = true;
            progressAt = null;
        }
        if (line.size() < 2) over = true;
    }

    /** Drops a marcher at once: it died, was tamed or is ridden. A leader hands over on the next sweep or ask. */
    void remove(UUID id) {
        int index = indexOf(id);
        if (index < 0) return;
        if (index == 0 && line.size() > 1) {
            line.remove(0);
            catchingUp = true;
            progressAt = null;
        } else line.remove(index);
        if (line.size() < 2) over = true;
    }

    private Vector lead(Vector at, long now, MarchRoute.Ground ground) {
        if (catchingUp) {
            Vector head = trail.head();
            if (horizontal(at, head) > 1D) return head.clone();
            catchingUp = false;
            progressAt = null;
        }
        if (phase == Phase.FORMING) {
            // The leader waits on the spot it called from: the marchers gathering round it shove it, and it walks
            // back. The spot is at the height of the ground there. Shoved up or down a step, the leader was given
            // the spot at the height it then stood at, a point in the air or in the ground, could not walk there,
            // and stood on the other level until the line set off without it. The ground there is not always at the
            // height the march was called at: a caller on the edge of a block has its middle over lower ground.
            if (now - calledAt < FORM_TICKS && !formed(now)) {
                int feet = ground.standing((int) Math.floor(home.getX()), (int) Math.floor(home.getY()),
                        (int) Math.floor(home.getZ()));
                return new Vector(home.getX(), feet == MarchRoute.Ground.NO_FOOTING ? home.getY() : feet, home.getZ());
            }
            phase = Phase.OUT;
            outEnd = now + durationTicks / 2L;
            // The first leg runs from that spot, along the heading that was found clear from there. From wherever
            // the leader stood a block to the side, that heading could be shut, and the march turned away or ended.
            if (!nextLeg(home, ground)) return null;
        }
        trail.step(at, (line.size() + 1) * spacing + 2D + TRAIL_SPARE);
        if (progressAt == null || horizontal(at, progressAt) >= 1D) {
            progressAt = at.clone();
            progressTick = now;
        } else if (now - progressTick >= STUCK_TICKS) {
            progressTick = now;
            if (++replans > MAX_REPLANS || !replan(at, ground, true)) return end();
        }
        if (phase == Phase.HOME && horizontal(at, home) <= ARRIVE) return end();
        if (phase == Phase.OUT && now >= outEnd) {
            if (!hairpin(at, ground)) return null;
        } else {
            // A straight leg goes on from a little before its end, so the leader keeps its pace; the hairpin's walk to
            // the side runs its full length, so the way back passes LANE blocks beside the way out.
            double margin = phase == Phase.HAIRPIN ? 0.25D : LEG_MARGIN;
            double along = along(at);
            if (along >= legLength - margin && !nextLeg(onLeg(along, at), ground)) return null;
        }
        // The hairpin's walking point runs on past the end of its walk to the side, so the leader walks the whole of it
        // at its pace instead of slowing to a stop just short.
        double along = along(at) + lookahead();
        if (phase != Phase.HAIRPIN) along = Math.min(along, legLength);
        double x = legOrigin.getX() + headingX * along, z = legOrigin.getZ() + headingZ * along;
        // The point is at most a block ahead, at the height of the ground there: a step up or down from the leader's
        // own when a step lies between them. The leader walks straight at it either way (see walksLeg).
        int level = (int) Math.floor(at.getY());
        int feet = ground.standing((int) Math.floor(x), level, (int) Math.floor(z));
        if (feet == MarchRoute.Ground.NO_FOOTING) {
            // A leader on the edge of a block rests on it with its middle over ground a step down. Where the ground
            // steps down again within its reach, the point is two steps under its feet and one under the ground it
            // is over: the height is counted from that ground, as the leg's run counted it.
            int under = ground.standing((int) Math.floor(at.getX()), level, (int) Math.floor(at.getZ()));
            if (under != MarchRoute.Ground.NO_FOOTING && under != level) {
                level = under;
                feet = ground.standing((int) Math.floor(x), level, (int) Math.floor(z));
            }
        }
        if (feet == MarchRoute.Ground.NO_FOOTING) {
            // The leg was found by standing on it every half block from its start, and a slanting leg crosses the
            // corners of blocks between those: the point is over one that is no ground, a hole beside the line. With
            // its point in the air there the leader had nowhere to walk and stood until it counted as stuck. Its
            // point is the next place the leg's run stood on.
            double stood = Math.ceil(along / MarchRoute.STEP - 1.0E-9D) * MarchRoute.STEP;
            if (phase != Phase.HAIRPIN) stood = Math.min(stood, legLength);
            double stoodX = legOrigin.getX() + headingX * stood, stoodZ = legOrigin.getZ() + headingZ * stood;
            int stoodFeet = ground.standing((int) Math.floor(stoodX), level, (int) Math.floor(stoodZ));
            if (stoodFeet != MarchRoute.Ground.NO_FOOTING) return new Vector(stoodX, stoodFeet, stoodZ);
        }
        return new Vector(x, feet == MarchRoute.Ground.NO_FOOTING ? at.getY() : feet, z);
    }

    /** The next leg from {@code from}, at the end of the current one; false when the march ended. */
    private boolean nextLeg(Vector from, MarchRoute.Ground ground) {
        switch (phase) {
            case OUT -> {
                MarchRoute.Run run = MarchRoute.run(ground, from, headingX, headingZ, MarchRoute.MAX_RUN);
                if (run.length() >= MarchRoute.MIN_LEG) {
                    leg(from, headingX, headingZ, run.length());
                    return true;
                }
                return replan(from, ground, false);
            }
            case HAIRPIN -> {
                phase = Phase.HOME;
                return homeLeg(from, ground);
            }
            case HOME -> {
                return homeLeg(from, ground);
            }
            default -> {
                return true;
            }
        }
    }

    /**
     * A leg toward where the march was called, or round what is in the way; false when the march ended.
     * <p>
     * The straight way home comes first every time. After one leg round something the leader used to be kept off
     * it for good: each further leg had to turn away from home, by the smallest turn that ran 6 blocks. On open
     * ground that curved it home a sixteenth of a circle off. In a gap between trees or walls, where the only ways
     * that run 6 blocks are straight home and straight back, it turned right round, away from home and into its own
     * line, with the way home open.
     */
    private boolean homeLeg(Vector from, MarchRoute.Ground ground) {
        double distance = horizontal(from, home);
        if (distance <= ARRIVE) {
            end();
            return false;
        }
        double dx = (home.getX() - from.getX()) / distance, dz = (home.getZ() - from.getZ()) / distance;
        MarchRoute.Run straight = MarchRoute.run(ground, from, dx, dz, Math.min(distance, MarchRoute.MAX_RUN));
        if (straight.length() >= Math.min(MarchRoute.MIN_LEG, distance - ARRIVE)) {
            leg(from, dx, dz, straight.length());
            return true;
        }
        double[] around = MarchRoute.turn(ground, from, dx, dz, MarchRoute.MIN_LEG, true);
        if (around == null) {
            end();
            return false;
        }
        MarchRoute.Run run = MarchRoute.run(ground, from, around[0], around[1], DETOUR_LEG);
        leg(from, around[0], around[1], run.length());
        return true;
    }

    /** Turns to the clearer side for the walk back; false when the march ended. */
    private boolean hairpin(Vector from, MarchRoute.Ground ground) {
        double leftX = -headingZ, leftZ = headingX;
        MarchRoute.Run left = MarchRoute.run(ground, from, leftX, leftZ, LANE),
                right = MarchRoute.run(ground, from, -leftX, -leftZ, LANE);
        MarchRoute.Run side = left.length() >= right.length() ? left : right;
        if (side.length() >= LANE - 1.0E-9D) {
            double sign = side == left ? 1D : -1D;
            leg(from, sign * leftX, sign * leftZ, side.length());
            phase = Phase.HAIRPIN;
            return true;
        }
        phase = Phase.HOME;
        return homeLeg(from, ground);
    }

    /** A new heading from where the leader is: any clear way when stuck, else the smallest turn that clears. */
    private boolean replan(Vector from, MarchRoute.Ground ground, boolean stuck) {
        if (phase == Phase.HOME || phase == Phase.HAIRPIN) {
            phase = Phase.HOME;
            if (!stuck) return homeLeg(from, ground);
            double[] around = MarchRoute.turn(ground, from, headingX, headingZ, MarchRoute.MIN_LEG, true);
            if (around == null) {
                end();
                return false;
            }
            leg(from, around[0], around[1], MarchRoute.run(ground, from, around[0], around[1], DETOUR_LEG).length());
            return true;
        }
        double[] turned = MarchRoute.turn(ground, from, headingX, headingZ, MarchRoute.MIN_LEG, stuck);
        if (turned == null) {
            end();
            return false;
        }
        leg(from, turned[0], turned[1], MarchRoute.run(ground, from, turned[0], turned[1], MarchRoute.MAX_RUN).length());
        return true;
    }

    private void leg(Vector from, double dx, double dz, double length) {
        // A leader that turns starts its stuck count again where it turns. Counted from the place it last made
        // progress at, a leader that turned right round walked back past that place, was within a block of it out
        // and back for the whole count, and took a stuck turn while it walked: one of its three, for nothing.
        if (legOrigin != null && headingX * dx + headingZ * dz <= TURN_COSINE) progressAt = null;
        legOrigin = from.clone();
        headingX = dx;
        headingZ = dz;
        legLength = length;
    }

    /**
     * How far ahead on its leg the leader walks at: {@link #PACE} of the slowest marcher's speed stat over the leader's,
     * since steer_onto walks at that share of a pace over its last block; at most a block, at least
     * {@link #MIN_LOOKAHEAD}.
     */
    double lookahead() {
        double leader = line.get(0).speed, slowest = leader;
        for (Marcher marcher : line) slowest = Math.min(slowest, marcher.speed);
        if (!(leader > 0D) || !(slowest > 0D)) return 1D;
        return Math.max(MIN_LOOKAHEAD, Math.min(1D, PACE * slowest / leader));
    }

    private Vector end() {
        over = true;
        return null;
    }

    /** How far along its leg the leader is, by its projection on the leg's line. */
    private double along(Vector at) {
        return (at.getX() - legOrigin.getX()) * headingX + (at.getZ() - legOrigin.getZ()) * headingZ;
    }

    /** The point on the leg's line beside the leader, at its height. */
    private Vector onLeg(double along, Vector at) {
        return new Vector(legOrigin.getX() + headingX * along, at.getY(), legOrigin.getZ() + headingZ * along);
    }

    /** Whether every follower who is there stands within FORMED of its spot. */
    private boolean formed(long now) {
        for (int index = 1; index < line.size(); index++) {
            Marcher marcher = line.get(index);
            if (away(marcher, now)) continue;
            Vector spot = trail.behind(rank(index, now) * spacing);
            if (horizontal(marcher.at, spot) > FORMED) return false;
        }
        return true;
    }

    /** A follower's place in line: 1 behind the leader, counting only the followers ahead of it who are there. */
    private int rank(int index, long now) {
        int rank = 1;
        for (int ahead = 1; ahead < index; ahead++) if (!away(line.get(ahead), now)) rank++;
        return rank;
    }

    private boolean away(Marcher marcher, long now) {
        if (marcher.seen == NEVER) return now - calledAt > JOIN_GRACE_TICKS;
        return now - marcher.seen > KEEP_TICKS;
    }

    private boolean gone(Marcher marcher, long now, long ticks) {
        long since = marcher.seen == NEVER ? calledAt : marcher.seen;
        return now - since > ticks;
    }

    private int indexOf(UUID id) {
        for (int index = 0; index < line.size(); index++) if (line.get(index).id.equals(id)) return index;
        return -1;
    }

    private static double horizontal(Vector a, Vector b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }
}
