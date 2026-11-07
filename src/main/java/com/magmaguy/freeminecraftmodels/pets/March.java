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
    /** A leg round something in the way home, before the leader aims for home again. */
    static final double DETOUR_LEG = 8D;
    /** A leader that has not moved a block in this long is stuck, and turns. */
    static final long STUCK_TICKS = 100L;
    /** A leader stuck this many times on one march gives up, and the march ends. */
    static final int MAX_REPLANS = 3;

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
    private boolean detour;
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

    /** The marchers, leader first. */
    List<UUID> marchers() {
        List<UUID> ids = new ArrayList<>(line.size());
        for (Marcher marcher : line) ids.add(marcher.id);
        return ids;
    }

    UUID leader() {
        return line.isEmpty() ? null : line.get(0).id;
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
            if (now - calledAt < FORM_TICKS && !formed(now)) return at.clone();
            phase = Phase.OUT;
            outEnd = now + durationTicks / 2L;
            if (!nextLeg(at, ground)) return null;
        }
        trail.step(at, (line.size() + 1) * spacing + 2D);
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
        int feet = ground.standing((int) Math.floor(x), (int) Math.floor(at.getY()), (int) Math.floor(z));
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

    /** A leg toward where the march was called, or round what is in the way; false when the march ended. */
    private boolean homeLeg(Vector from, MarchRoute.Ground ground) {
        double distance = horizontal(from, home);
        if (distance <= ARRIVE) {
            end();
            return false;
        }
        double dx = (home.getX() - from.getX()) / distance, dz = (home.getZ() - from.getZ()) / distance;
        if (detour) detour = false;
        else {
            MarchRoute.Run run = MarchRoute.run(ground, from, dx, dz, Math.min(distance, MarchRoute.MAX_RUN));
            if (run.length() >= Math.min(MarchRoute.MIN_LEG, distance - ARRIVE)) {
                leg(from, dx, dz, run.length());
                return true;
            }
        }
        double[] around = MarchRoute.turn(ground, from, dx, dz, MarchRoute.MIN_LEG, true);
        if (around == null) {
            end();
            return false;
        }
        MarchRoute.Run run = MarchRoute.run(ground, from, around[0], around[1], DETOUR_LEG);
        leg(from, around[0], around[1], run.length());
        detour = true;
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
            if (stuck) detour = false;
            phase = Phase.HOME;
            if (!stuck) return homeLeg(from, ground);
            double[] around = MarchRoute.turn(ground, from, headingX, headingZ, MarchRoute.MIN_LEG, true);
            if (around == null) {
                end();
                return false;
            }
            leg(from, around[0], around[1], MarchRoute.run(ground, from, around[0], around[1], DETOUR_LEG).length());
            detour = true;
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
