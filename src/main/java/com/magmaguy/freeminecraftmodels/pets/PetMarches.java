package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.magmacore.ai.MindHandle;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The marches under way, for the march module: a call gathers the free kin round the caller into a {@link March}, and
 * each marcher asks for its spot every tick it marches. A sweep each pet tick plays the call's second toot and drops
 * marchers that died, were tamed or carry a rider, and ends marches. Admins can ask a pet to call one. A pet on a
 * migration neither calls a march nor is gathered into one. Server thread only.
 */
final class PetMarches {
    /** The most pets in one march, its leader included. */
    static final int MAX_MARCHERS = 64;
    /** The call's second toot comes this many ticks after the first. */
    static final long SECOND_TOOT_TICKS = 6L;
    /** An admin's request waits this long for the pet to be free to call. */
    static final long REQUEST_TICKS = 400L;

    /** One march in a world, with its call's sound and the species, wildness and owner its marchers share. */
    private static final class Walk {
        final March march;
        final World world;
        final String species;
        final boolean wild;
        final UUID owner;
        final String sound;
        final float volume, pitch;
        // The marchers' bodies, by id: a body that is gone has left.
        final Map<UUID, LivingEntity> bodies = new HashMap<>();
        boolean secondToot;

        Walk(March march, World world, PetControl leader, String sound, float volume, float pitch) {
            this.march = march;
            this.world = world;
            this.species = leader.species().id();
            this.wild = leader.isWild();
            this.owner = leader.ownerId();
            this.sound = sound;
            this.volume = volume;
            this.pitch = pitch;
        }
    }

    // Each marcher's march, by its body's id.
    private static final Map<UUID, Walk> byBody = new HashMap<>();
    // The marches under way, each once: those byBody holds, kept so the tick's sweep need not gather them.
    private static final List<Walk> walks = new ArrayList<>();
    // The pets an admin asked to call a march, with the tick each request ends.
    private static final Map<UUID, Long> requests = new HashMap<>();

    private PetMarches() {
    }

    /**
     * Calls a march with this pet at its head, from where it stands: see c.pet:march_call. Its free kin within radius,
     * itself included, must number at least minGroup (at least 2), and chance is shared out among them, so a colony
     * calls as often whatever its size; an admin's request skips the dice. The leader sets off along the heading whose
     * straight run goes farthest, which must reach {@link MarchRoute#MIN_LEG}. True when this pet now leads a march.
     * <p>
     * Free kin are settled, not told to stay, in no march, and not kept from marching: a kin whose behavior with the
     * calling module's id could not start now, because something that comes first holds its body (a fight, a stolen
     * fish, its owner), is left out, as the caller would be. Enrolled anyway, it filled the group and a place in line
     * it could not take, and was held out of any other march until this one ended.
     */
    static boolean call(PetControl caller, LivingEntity body, String module, double radius, int minGroup,
                        double chance, double minSeconds, double maxSeconds, double spacing, String sound, float volume,
                        float pitch, long now) {
        PetManager manager = PetManager.get();
        // A migrant flies its flock's route and leaves with it, so it calls no march in the ticks before it sets off.
        if (manager == null || caller.route() != null || byBody.containsKey(body.getUniqueId())) return false;
        Long requestEnd = requests.get(body.getUniqueId());
        boolean asked = requestEnd != null && now < requestEnd;
        // chance, then one in the group's size: the group's chance shared out, with no search on most checks.
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (!asked && random.nextDouble() >= chance) return false;
        Location here = body.getLocation();
        List<LivingEntity> kin = new ArrayList<>();
        for (Entity entity : body.getWorld().getNearbyEntities(here, radius, radius, radius,
                candidate -> candidate instanceof LivingEntity && candidate != body)) {
            if (entity.getLocation().distanceSquared(here) > radius * radius || byBody.containsKey(entity.getUniqueId()))
                continue;
            PetControl other = manager.pet(entity);
            if (!caller.isKin(other) || other.isStaying() || !PetMotion.settled(other, (LivingEntity) entity)) continue;
            MindHandle mind = other.mind();
            if (mind != null && mind.isBlocked(module)) continue;
            kin.add((LivingEntity) entity);
        }
        if (kin.size() + 1 < Math.max(2, minGroup)) return false;
        if (!asked && random.nextDouble() >= 1D / (kin.size() + 1)) return false;
        MarchRoute.Ground ground = ground(body.getWorld());
        Vector start = here.toVector();
        double[] heading = MarchRoute.widest(ground, start, random.nextDouble(2D * Math.PI / MarchRoute.HEADINGS));
        if (heading == null) return false;
        kin.sort(Comparator.comparingDouble(entity -> entity.getLocation().distanceSquared(here)));
        if (kin.size() > MAX_MARCHERS - 1) kin = kin.subList(0, MAX_MARCHERS - 1);
        List<UUID> ids = new ArrayList<>(kin.size());
        Vector[] positions = new Vector[kin.size()];
        double[] speeds = new double[kin.size() + 1];
        speeds[0] = caller.stats().speed;
        for (int index = 0; index < kin.size(); index++) {
            ids.add(kin.get(index).getUniqueId());
            positions[index] = kin.get(index).getLocation().toVector();
            PetControl marcher = manager.pet(kin.get(index));
            speeds[index + 1] = marcher == null ? 1D : marcher.stats().speed;
        }
        double seconds = minSeconds + random.nextDouble() * Math.max(0D, maxSeconds - minSeconds);
        March march = new March(body.getUniqueId(), start, ids, positions, speeds, heading[0], heading[1], spacing, now,
                Math.round(seconds * 20D));
        Walk walk = new Walk(march, body.getWorld(), caller, sound, volume, pitch);
        walk.bodies.put(body.getUniqueId(), body);
        for (LivingEntity marcher : kin) walk.bodies.put(marcher.getUniqueId(), marcher);
        for (UUID id : walk.bodies.keySet()) byBody.put(id, walk);
        walks.add(walk);
        requests.remove(body.getUniqueId());
        toot(walk, here);
        return true;
    }

    /** Where this marcher should walk to now (see {@link March#spot}); null when it is in no march, or it ended. */
    static Location spot(LivingEntity body, long now) {
        Walk walk = byBody.get(body.getUniqueId());
        if (walk == null) return null;
        if (body.getWorld() != walk.world) {
            walk.march.remove(body.getUniqueId());
            walk.bodies.remove(body.getUniqueId());
            byBody.remove(body.getUniqueId());
            if (walk.march.over()) end(walk);
            return null;
        }
        Vector spot = walk.march.spot(body.getUniqueId(), body.getLocation().toVector(), now, ground(walk.world));
        if (walk.march.over()) {
            end(walk);
            return null;
        }
        return spot == null ? null : spot.toLocation(walk.world);
    }

    /**
     * Whether this pet is in a march that is not over, in its world. Unlike {@link #spot} it keeps nothing: a marcher
     * that only asks this is not seen, so one another behavior took away counts as away after a while, as it should.
     */
    static boolean marching(LivingEntity body) {
        Walk walk = byBody.get(body.getUniqueId());
        return walk != null && body.getWorld() == walk.world && walk.march.includes(body.getUniqueId());
    }

    /** Whether an admin asked this pet to call a march, and the request still waits. */
    static boolean requested(LivingEntity body, long now) {
        Long end = requests.get(body.getUniqueId());
        return end != null && now < end;
    }

    /** Asks this pet to call a march as soon as it is free, skipping the dice and the module's time of day. */
    static void request(LivingEntity body, long now) {
        requests.put(body.getUniqueId(), now + REQUEST_TICKS);
    }

    /**
     * Once a pet tick: the second toot of each new call, marchers that died, left the world, were tamed or carry a
     * rider, each march's own rules, and the marches that ended.
     */
    static void tick(long now) {
        if (!requests.isEmpty()) requests.values().removeIf(end -> now >= end);
        if (walks.isEmpty()) return;
        PetManager manager = PetManager.get();
        // From the back, so a march that ends and leaves the list skips none, and the same inside each line: a
        // removal moves only the marchers behind it. The marchers left, and their order, come out as they would
        // walking a copy from the front.
        for (int next = walks.size() - 1; next >= 0; next--) {
            Walk walk = walks.get(next);
            for (int index = walk.march.size() - 1; index >= 0; index--) {
                UUID id = walk.march.marcherAt(index);
                LivingEntity body = walk.bodies.get(id);
                PetControl control = manager == null || body == null ? null : manager.pet(body);
                if (control == null || !body.isValid() || body.getWorld() != walk.world || !body.isEmpty()
                        || !belongs(walk, control)) {
                    walk.march.remove(id);
                    walk.bodies.remove(id);
                    byBody.remove(id);
                }
            }
            if (!walk.secondToot && now - walk.march.calledAt() >= SECOND_TOOT_TICKS) {
                walk.secondToot = true;
                UUID leader = walk.march.leader();
                LivingEntity head = leader == null ? null : walk.bodies.get(leader);
                if (head != null && head.isValid()) toot(walk, head.getLocation());
            }
            walk.march.sweep(now);
            if (walk.march.over()) {
                end(walk);
                continue;
            }
            // A marcher the sweep dropped for being away too long is free again: it can call or join another march.
            if (walk.bodies.size() != walk.march.size()) walk.bodies.keySet().removeIf(id -> {
                if (walk.march.includes(id)) return false;
                byBody.remove(id, walk);
                return true;
            });
        }
    }

    /** Forgets every march and request. */
    static void clear() {
        byBody.clear();
        walks.clear();
        requests.clear();
    }

    private static boolean belongs(Walk walk, PetControl control) {
        if (!control.species().id().equals(walk.species) || control.isWild() != walk.wild) return false;
        return walk.wild || walk.owner.equals(control.ownerId());
    }

    private static void end(Walk walk) {
        byBody.values().removeIf(other -> other == walk);
        // Walk keeps identity equality, so this drops this march alone.
        walks.remove(walk);
    }

    private static void toot(Walk walk, Location at) {
        if (walk.sound != null && walk.volume > 0F) walk.world.playSound(at, walk.sound, walk.volume, walk.pitch);
    }

    /** The world's columns as a march sees them: see {@link MarchRoute.Ground#standing}. */
    static MarchRoute.Ground ground(World world) {
        return (x, feet, z) -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return MarchRoute.Ground.NO_FOOTING;
            for (int y = feet + 1; y >= feet - 1; y--) {
                if (y - 1 < world.getMinHeight() || y + 1 >= world.getMaxHeight()) continue;
                if (PetLuaTable.room(world.getBlockAt(x, y, z)) && PetLuaTable.room(world.getBlockAt(x, y + 1, z))
                        && footing(world.getBlockAt(x, y - 1, z))) return y;
            }
            return MarchRoute.Ground.NO_FOOTING;
        };
    }

    /**
     * Ground a march walks on: firm, and not a tree's leaves. On a hillside the top of a tree lower down is level
     * with the ground, and a leg that ran over it took the line onto the treetop, from where it came down in drops
     * it could not climb back.
     */
    static boolean footing(Block block) {
        return PetLuaTable.firm(block) && !PetMotion.isLeaves(block.getType());
    }
}
