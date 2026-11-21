package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.pets.interactions.PetSky;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Wild colonies and their day: a colony is the wild pets of one species whose homes lie within the march module's
 * radius of one another, two or more of them, when that module runs its routine. Each morning the colony forms a line
 * and marches to the open water nearest home, within water_reach blocks; by day its pets stay and fish there; at dusk
 * it forms up again and marches home, where it spends the night (and huddles, with the huddle module). A colony with
 * no water within reach marches out and back from home by day instead, every every_minutes.
 * <p>
 * The colony decides, not each pet: once a second it compares where it is with where the time of day wants it and
 * calls the march itself, so the routine needs no dice and turns with {@code /time set}. The water is looked for once
 * a day per colony and shared by its pets. While the colony is at its shore, its pets' anchor is the shore (see
 * c.pet.anchor) and c.pet.colony_water the water they fish; their homes, which the wild store keeps, never change.
 * Server thread only.
 */
final class PetColonies {
    /** How often the colonies are gathered and each decides, in pet ticks. */
    static final long CHECK_TICKS = 20L;
    /** The fewest pets in a colony. */
    static final int MIN_COLONY = 2;
    /** A colony whose pets' middle is this near its home, or its shore, is there. */
    static final double THERE = 12D;
    /** A march that came to nothing is tried again after this long; after this many in a row the colony gives up. */
    static final long RETRY_TICKS = 100L;
    static final int MAX_TRIES = 3;
    /** A search that found no water while columns within reach were unloaded looks again after this long. */
    static final long SEARCH_AGAIN_TICKS = 600L;
    /** A colony's home that has moved this far since its water was found looks for water again. */
    static final double HOME_MOVED = 4D;
    /** A march to a goal is given a second per block of the way and this many seconds more, then its hard cap. */
    static final double GOAL_SLACK_SECONDS = 30D;
    /**
     * The most colonies that look for their water, and that set off on a march, in one check. At dawn every colony on
     * the server wants both at once; a search costs about a millisecond and a half and twenty of them in one tick made
     * a 58 ms tick in the 100-penguin load check, so the rest wait their turn a second or two.
     */
    static final int SEARCHES_PER_CHECK = 2, MARCHES_PER_CHECK = 4;
    /** When the day ends by these bounds, as PetSky counts it. */
    private static final long DAWN = 23850L;

    enum Place {HOME, SHORE, UNKNOWN}

    /** One colony and what it remembers of its day. */
    static final class Colony {
        final World world;
        final String species;
        List<PetControl> members = new ArrayList<>();
        // The middle of its pets' homes.
        Location home;
        Place place = Place.UNKNOWN;
        // Its water, looked for from searchedFrom on the pet tick searchedAt; null when there is none within reach.
        ShoreSearch.Shore shore;
        boolean searched, searchComplete;
        Vector searchedFrom;
        long searchedAt;
        // Gave up on the shore for the rest of this day, after MAX_TRIES marches there came to nothing.
        boolean shoreGivenUp;
        // The place its march is going to, while one is under way; its leader; and whether it was called off.
        Place marchingTo;
        LivingEntity marchLeader;
        boolean cancelling;
        int failures;
        long nextTry;
        // The next march out and back of a colony with no water, by day.
        long nextRoam;
        // Whether it was shore time at the last check, to tell the morning.
        Boolean wasShoreTime;
        // The settings its march module runs with.
        Map<String, Object> settings;

        Colony(World world, String species) {
            this.world = world;
            this.species = species;
        }
    }

    // Each colony by its pets, and the colonies.
    private static final Map<PetControl, Colony> byPet = new IdentityHashMap<>();
    private static final List<Colony> colonies = new ArrayList<>();
    // What is left of this check's searches and march starts.
    private static int searchesLeft, marchesLeft;

    private PetColonies() {
    }

    /** Once a pet tick: every {@link #CHECK_TICKS}, gathers the colonies and lets each decide. */
    static void tick(PetManager manager, long now) {
        if (now % CHECK_TICKS != 0L) return;
        gather(manager, now);
        searchesLeft = SEARCHES_PER_CHECK;
        marchesLeft = MARCHES_PER_CHECK;
        for (Colony colony : colonies) decide(colony, now);
    }

    /** Forgets every colony; the pets keep no colony spot. */
    static void clear() {
        for (PetControl pet : byPet.keySet()) pet.setColonyPlace(null, null);
        byPet.clear();
        colonies.clear();
    }

    /** The colony this pet is in, or null. */
    static Colony of(PetControl pet) {
        return byPet.get(pet);
    }

    /**
     * Sets a colony going at once, for filming or after it gave up: it forgets its failures, looks for its water
     * again, and marches where the time of day wants it, or out and back when it has no water, on its next check.
     */
    static void hurry(Colony colony) {
        colony.failures = 0;
        colony.nextTry = 0L;
        colony.nextRoam = 0L;
        colony.shoreGivenUp = false;
        colony.searched = false;
        if (colony.marchingTo == null) colony.place = Place.UNKNOWN;
    }

    // ---- gathering ---------------------------------------------------------------------------------------------

    /**
     * The colonies now: the live wild pets whose march module runs its routine, linked by homes within its radius of
     * one another, two or more to a colony. A colony keeps what it remembers when most of its pets are still in it.
     */
    private static void gather(PetManager manager, long now) {
        Map<String, List<PetControl>> byKind = new HashMap<>();
        Map<String, Map<String, Object>> settingsByKind = new HashMap<>();
        for (PetControl pet : manager.wildPets()) {
            LivingEntity body = pet.body();
            Location home = pet.home();
            if (pet.route() != null || body == null || !body.isValid() || home == null
                    || home.getWorld() != body.getWorld()) continue;
            String kind = body.getWorld().getUID() + "/" + pet.species().id() + "/" + pet.model();
            Map<String, Object> settings = settingsByKind.computeIfAbsent(kind, key -> manager.marchSettings(pet));
            if (settings == null || !bool(settings, "routine", true)) continue;
            byKind.computeIfAbsent(kind, key -> new ArrayList<>()).add(pet);
        }
        // Variants of one species live together: a colony is of a species, whatever each one's colour.
        Map<String, List<PetControl>> bySpecies = new HashMap<>();
        Map<String, Map<String, Object>> settingsBySpecies = new HashMap<>();
        for (Map.Entry<String, List<PetControl>> entry : byKind.entrySet()) {
            String kind = entry.getKey();
            String species = kind.substring(0, kind.lastIndexOf('/'));
            bySpecies.computeIfAbsent(species, key -> new ArrayList<>()).addAll(entry.getValue());
            settingsBySpecies.putIfAbsent(species, settingsByKind.get(kind));
        }
        Map<PetControl, Colony> previous = new IdentityHashMap<>(byPet);
        byPet.clear();
        List<Colony> found = new ArrayList<>();
        Map<Colony, Boolean> claimed = new IdentityHashMap<>();
        for (Map.Entry<String, List<PetControl>> entry : bySpecies.entrySet()) {
            Map<String, Object> settings = settingsBySpecies.get(entry.getKey());
            double radius = Math.max(1D, Math.min(PetLuaTable.MAX_KIN_RADIUS, number(settings, "radius", 12D)));
            for (List<PetControl> group : groups(entry.getValue(), radius)) {
                if (group.size() < MIN_COLONY) continue;
                Colony colony = inherit(group, previous, claimed);
                if (colony == null) {
                    PetControl first = group.get(0);
                    colony = new Colony(first.body().getWorld(), first.species().id());
                    // A colony new to the server sets off on its first day's walk out and back within a minute, not
                    // all at once.
                    colony.nextRoam = now + ThreadLocalRandom.current().nextLong(1200L);
                }
                claimed.put(colony, Boolean.TRUE);
                colony.members = group;
                colony.settings = settings;
                colony.home = middleOfHomes(group);
                for (PetControl pet : group) byPet.put(pet, colony);
                found.add(colony);
            }
        }
        // Pets that left their colony keep no colony spot.
        for (PetControl pet : previous.keySet()) if (!byPet.containsKey(pet)) pet.setColonyPlace(null, null);
        // A colony that is gone has its march called off quietly.
        for (Colony colony : colonies)
            if (!claimed.containsKey(colony) && colony.marchLeader != null) {
                colony.cancelling = true;
                PetMarches.cancel(colony.marchLeader);
            }
        colonies.clear();
        colonies.addAll(found);
    }

    /** The colony most of this group's pets were in, when no other group took it already. */
    private static Colony inherit(List<PetControl> group, Map<PetControl, Colony> previous, Map<Colony, Boolean> claimed) {
        Map<Colony, Integer> votes = new IdentityHashMap<>();
        Colony best = null;
        int bestVotes = 0;
        for (PetControl pet : group) {
            Colony was = previous.get(pet);
            if (was == null || claimed.containsKey(was)) continue;
            int count = votes.merge(was, 1, Integer::sum);
            if (count > bestVotes) {
                bestVotes = count;
                best = was;
            }
        }
        return best;
    }

    /**
     * These pets in groups linked by homes within {@code radius} of one another, through any chain of them: the
     * homes are bucketed in cells a radius across, so each home is held against those in the cells round it only.
     */
    static List<List<PetControl>> groups(List<PetControl> pets, double radius) {
        int count = pets.size();
        int[] parent = new int[count];
        double[] x = new double[count], z = new double[count];
        Map<Long, List<Integer>> cells = new HashMap<>();
        for (int index = 0; index < count; index++) {
            parent[index] = index;
            Location home = pets.get(index).home();
            x[index] = home.getX();
            z[index] = home.getZ();
            cells.computeIfAbsent(cell((int) Math.floor(x[index] / radius), (int) Math.floor(z[index] / radius)),
                    key -> new ArrayList<>()).add(index);
        }
        for (int index = 0; index < count; index++) {
            int cellX = (int) Math.floor(x[index] / radius), cellZ = (int) Math.floor(z[index] / radius);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    List<Integer> near = cells.get(cell(cellX + dx, cellZ + dz));
                    if (near == null) continue;
                    for (int other : near) {
                        if (other <= index) continue;
                        double ax = x[index] - x[other], az = z[index] - z[other];
                        if (ax * ax + az * az <= radius * radius) union(parent, index, other);
                    }
                }
        }
        Map<Integer, List<PetControl>> byRoot = new HashMap<>();
        for (int index = 0; index < count; index++)
            byRoot.computeIfAbsent(root(parent, index), key -> new ArrayList<>()).add(pets.get(index));
        return new ArrayList<>(byRoot.values());
    }

    private static long cell(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int root(int[] parent, int index) {
        while (parent[index] != index) {
            parent[index] = parent[parent[index]];
            index = parent[index];
        }
        return index;
    }

    private static void union(int[] parent, int a, int b) {
        int rootA = root(parent, a), rootB = root(parent, b);
        if (rootA != rootB) parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
    }

    private static Location middleOfHomes(List<PetControl> group) {
        double x = 0D, y = 0D, z = 0D;
        World world = null;
        for (PetControl pet : group) {
            Location home = pet.home();
            world = home.getWorld();
            x += home.getX();
            y += home.getY();
            z += home.getZ();
        }
        return new Location(world, x / group.size(), y / group.size(), z / group.size());
    }

    // ---- the day -----------------------------------------------------------------------------------------------

    /**
     * Whether the time of day wants the colony at its shore: by day, before dusk_time (a time of day in ticks, 0 to
     * 24000, as /time set takes it).
     */
    static boolean shoreTime(boolean day, long timeOfDay, long dusk) {
        return day && (timeOfDay < dusk || timeOfDay > DAWN);
    }

    /** Where the colony belongs now: its shore in shore time when it has one, else home. */
    static Place wanted(boolean shoreTime, boolean hasShore) {
        return shoreTime && hasShore ? Place.SHORE : Place.HOME;
    }

    private static void decide(Colony colony, long now) {
        World world = colony.world;
        boolean shoreTime = shoreTime(PetSky.isDay(world), world.getTime(),
                (long) number(colony.settings, "dusk_time", 10500D));
        if (shoreTime && Boolean.FALSE.equals(colony.wasShoreTime)) {
            // Morning: a new day, with its own look for water and its own tries.
            colony.searched = false;
            colony.shoreGivenUp = false;
            colony.failures = 0;
            colony.nextTry = 0L;
            colony.nextRoam = now;
        }
        colony.wasShoreTime = shoreTime;
        // A colony whose look for water waits its turn decides nothing more until it has looked.
        if (shoreTime && !search(colony, now)) return;
        boolean hasShore = colony.shore != null && !colony.shoreGivenUp;
        Place wanted = wanted(shoreTime, hasShore);
        if (colony.marchingTo != null) {
            // Its march goes on, unless the day turned and it is wanted elsewhere now.
            if (colony.marchingTo != wanted && colony.marchLeader != null) {
                colony.cancelling = true;
                PetMarches.cancel(colony.marchLeader);
            }
            if (colony.marchingTo != null) return;
        }
        // An admin's march, or one its pets called on their own: the colony waits for it to end.
        for (PetControl pet : colony.members) if (PetMarches.marching(pet.body())) return;
        if (colony.place == Place.UNKNOWN) colony.place = where(colony);
        if (colony.place == wanted) {
            settle(colony);
            if (wanted == Place.HOME && shoreTime && colony.shore == null && colony.searchComplete
                    && now >= colony.nextRoam && !snowing(colony) && marchesLeft > 0) {
                marchesLeft--;
                roam(colony, now);
            }
            return;
        }
        if (now >= colony.nextTry && marchesLeft > 0) {
            marchesLeft--;
            march(colony, wanted, now);
        }
    }

    /** Where the colony is, by its pets' middle: at home, at its shore, or neither. */
    private static Place where(Colony colony) {
        double x = 0D, z = 0D;
        for (PetControl pet : colony.members) {
            Location at = pet.body().getLocation();
            x += at.getX();
            z += at.getZ();
        }
        x /= colony.members.size();
        z /= colony.members.size();
        if (horizontal(x, z, colony.home.getX(), colony.home.getZ()) <= THERE) return Place.HOME;
        if (colony.shore != null
                && horizontal(x, z, colony.shore.stand().getX(), colony.shore.stand().getZ()) <= THERE)
            return Place.SHORE;
        return Place.UNKNOWN;
    }

    /** Its pets' spot for where the colony is: the shore and its water, or home. */
    private static void settle(Colony colony) {
        Location stand = null, water = null;
        if (colony.place == Place.SHORE && colony.shore != null) {
            stand = colony.shore.stand().toLocation(colony.world);
            water = colony.shore.water().toLocation(colony.world);
        }
        for (PetControl pet : colony.members) pet.setColonyPlace(stand, water);
    }

    /**
     * Looks for the colony's water once a day, again when its home moved or unloaded columns may hold some. False when
     * a look is due but this check has none left, so the colony waits for the next.
     */
    private static boolean search(Colony colony, long now) {
        Vector home = colony.home.toVector();
        boolean stale = !colony.searched
                || colony.searchedFrom.distanceSquared(home) > HOME_MOVED * HOME_MOVED
                || colony.shore == null && !colony.searchComplete && now - colony.searchedAt >= SEARCH_AGAIN_TICKS;
        if (!stale) return true;
        if (searchesLeft <= 0) return false;
        searchesLeft--;
        double reach = number(colony.settings, "water_reach", 48D);
        ShoreSearch.Result result = ShoreSearch.nearest(terrain(colony.world), home, reach);
        colony.shore = result.shore();
        colony.searchComplete = result.complete();
        colony.searched = true;
        colony.searchedFrom = home;
        colony.searchedAt = now;
        return true;
    }

    /**
     * Calls the colony's march to where it is wanted, led by the free adult standing on dry ground nearest there; one
     * still in the water falls in behind and swims out to its place.
     */
    private static void march(Colony colony, Place to, long now) {
        Vector goal = to == Place.SHORE ? colony.shore.stand() : colony.home.toVector();
        PetControl leader = null;
        LivingEntity leaderBody = null;
        double best = Double.MAX_VALUE;
        List<LivingEntity> free = new ArrayList<>();
        List<PetControl> freePets = new ArrayList<>();
        for (PetControl pet : colony.members) {
            LivingEntity body = pet.body();
            if (!PetMarches.free(pet, body)) continue;
            free.add(body);
            freePets.add(pet);
            if (pet.isBaby() || !PetMarches.standing(body)) continue;
            Location at = body.getLocation();
            double distance = horizontal(at.getX(), at.getZ(), goal.getX(), goal.getZ());
            if (distance < best) {
                best = distance;
                leader = pet;
                leaderBody = body;
            }
        }
        if (leader == null) {
            colony.nextTry = now + RETRY_TICKS;
            return;
        }
        Location from = leaderBody.getLocation();
        List<LivingEntity> marchers = new ArrayList<>();
        for (LivingEntity body : free)
            if (body != leaderBody && body.getLocation().distanceSquared(from)
                    <= PetLuaTable.MAX_KIN_RADIUS * PetLuaTable.MAX_KIN_RADIUS) marchers.add(body);
        long duration = Math.round((best + GOAL_SLACK_SECONDS) * 20D);
        Place target = to;
        boolean started = !marchers.isEmpty() && PetMarches.lead(leader, leaderBody, marchers, goal,
                spacing(colony), sound(colony), volume(colony), pitch(colony), now, duration,
                arrived -> ended(colony, target, arrived));
        if (!started) {
            colony.nextTry = now + RETRY_TICKS;
            return;
        }
        colony.marchingTo = to;
        colony.marchLeader = leaderBody;
        // Any of its pets that did not fall in makes its own way there.
        Location spot = to == Place.SHORE ? goal.toLocation(colony.world) : null;
        for (PetControl pet : colony.members) pet.setColonyPlace(spot, null);
    }

    /** A colony with no water marches out and back from home. */
    private static void roam(Colony colony, long now) {
        PetControl leader = null;
        List<LivingEntity> free = new ArrayList<>();
        List<PetControl> pets = new ArrayList<>();
        for (PetControl pet : colony.members) {
            if (!PetMarches.free(pet, pet.body())) continue;
            free.add(pet.body());
            pets.add(pet);
            if (leader == null && !pet.isBaby() && PetMarches.standing(pet.body())) leader = pet;
        }
        double minSeconds = number(colony.settings, "min_seconds", 60D),
                maxSeconds = Math.max(minSeconds, number(colony.settings, "max_seconds", 120D));
        double seconds = minSeconds + ThreadLocalRandom.current().nextDouble() * (maxSeconds - minSeconds);
        long every = Math.round(Math.max(0.05D, number(colony.settings, "every_minutes", 8D)) * 1200D);
        if (leader == null || free.size() < MIN_COLONY) {
            colony.nextRoam = now + RETRY_TICKS;
            return;
        }
        LivingEntity leaderBody = leader.body();
        List<LivingEntity> marchers = new ArrayList<>(free);
        marchers.remove(leaderBody);
        boolean started = PetMarches.lead(leader, leaderBody, marchers, null, spacing(colony), sound(colony),
                volume(colony), pitch(colony), now, Math.round(seconds * 20D), arrived -> {
                    colony.marchingTo = null;
                    colony.marchLeader = null;
                    colony.cancelling = false;
                });
        if (!started) {
            colony.nextRoam = now + RETRY_TICKS;
            return;
        }
        colony.nextRoam = now + Math.round(seconds * 20D) + every;
        colony.marchingTo = Place.HOME;
        colony.marchLeader = leaderBody;
    }

    /** A colony's march ended: there, or not, and then it tries again a few times before it gives up for the day. */
    private static void ended(Colony colony, Place target, boolean arrived) {
        boolean cancelled = colony.cancelling;
        colony.cancelling = false;
        colony.marchingTo = null;
        colony.marchLeader = null;
        long now = PetManager.ticks();
        if (arrived) {
            colony.place = target;
            colony.failures = 0;
            settle(colony);
            return;
        }
        colony.place = Place.UNKNOWN;
        if (cancelled) return;
        colony.nextTry = now + RETRY_TICKS;
        if (++colony.failures < MAX_TRIES) return;
        colony.failures = 0;
        // The shore cannot be reached today: the colony stays home, and walks out and back from there. Home cannot
        // be reached: the colony counts as home, and its pets make their own way there with their walks.
        if (target == Place.SHORE) colony.shoreGivenUp = true;
        else {
            colony.place = Place.HOME;
            settle(colony);
        }
    }

    private static boolean snowing(Colony colony) {
        return colony.world.hasStorm() && PetSky.isSnowingOn(colony.members.get(0).body().getLocation());
    }

    // ---- what an admin is told ---------------------------------------------------------------------------------

    /** The colony nearest this spot that has a pet within {@code blocks}, or null. */
    static Colony nearest(Location at, double blocks) {
        Colony best = null;
        double bestDistance = blocks * blocks;
        for (Colony colony : colonies) {
            if (colony.world != at.getWorld()) continue;
            for (PetControl pet : colony.members) {
                LivingEntity body = pet.body();
                if (body == null) continue;
                double distance = body.getLocation().distanceSquared(at);
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    best = colony;
                }
            }
        }
        return best;
    }

    /** How a colony stands, for an admin: its pets, where it is and is going, and its water. */
    static String describe(Colony colony) {
        int babies = 0;
        for (PetControl pet : colony.members) if (pet.isBaby()) babies++;
        World world = colony.world;
        boolean shoreTime = shoreTime(PetSky.isDay(world), world.getTime(),
                (long) number(colony.settings, "dusk_time", 10500D));
        StringBuilder text = new StringBuilder("&aA wild " + colony.species + " colony of " + colony.members.size()
                + (babies > 0 ? " (" + babies + (babies == 1 ? " baby" : " babies") + ")" : "") + ", home at "
                + block(colony.home.toVector()) + ". ");
        text.append(shoreTime ? "&7It is shore time (day, before dusk). " : "&7It is home time (dusk or night). ");
        if (!colony.searched) text.append("It has not looked for water yet today. ");
        else if (colony.shore == null)
            text.append(colony.searchComplete ? "No open water within " + (int) number(colony.settings, "water_reach", 48D)
                    + " blocks of home, so it marches out and back by day. "
                    : "No open water found yet; part of its reach is unloaded. ");
        else text.append("Its shore is " + Math.round(colony.shore.stand().distance(colony.home.toVector()))
                    + " blocks from home, at " + block(colony.shore.stand()) + (colony.shoreGivenUp
                    ? ", but it gave up reaching it today. " : ". "));
        if (colony.marchingTo != null)
            text.append("It is marching " + (colony.marchingTo == Place.SHORE ? "to the water." : "home."));
        else text.append("It is " + switch (colony.place) {
            case HOME -> "at home.";
            case SHORE -> "at its shore.";
            case UNKNOWN -> "on its way.";
        });
        if (colony.failures > 0) text.append(" Failed marches in a row: " + colony.failures + ".");
        return text.toString();
    }

    private static String block(Vector at) {
        return String.format(Locale.ROOT, "%d %d %d", (int) Math.floor(at.getX()), (int) Math.floor(at.getY()),
                (int) Math.floor(at.getZ()));
    }

    // ---- settings ----------------------------------------------------------------------------------------------

    private static double spacing(Colony colony) {
        return Math.max(0.5D, Math.min(8D, number(colony.settings, "spacing", 1.5D)));
    }

    private static String sound(Colony colony) {
        Object sound = colony.settings.get("sound");
        if (!(sound instanceof String text) || text.isBlank() || text.trim().equalsIgnoreCase("NONE")) return null;
        return text.trim();
    }

    private static float volume(Colony colony) {
        return (float) Math.max(0D, Math.min(4D, number(colony.settings, "volume", 1D)));
    }

    private static float pitch(Colony colony) {
        return (float) Math.max(0.5D, Math.min(2D, number(colony.settings, "pitch", 1D)));
    }

    static double number(Map<String, Object> settings, String key, double fallback) {
        Object value = settings == null ? null : settings.get(key);
        return value instanceof Number number && Double.isFinite(number.doubleValue()) ? number.doubleValue() : fallback;
    }

    static boolean bool(Map<String, Object> settings, String key, boolean fallback) {
        Object value = settings == null ? null : settings.get(key);
        return value instanceof Boolean flag ? flag : fallback;
    }

    private static double horizontal(double ax, double az, double bx, double bz) {
        double dx = ax - bx, dz = az - bz;
        return Math.sqrt(dx * dx + dz * dz);
    }

    // ---- the world as the search sees it -----------------------------------------------------------------------

    /** The world's columns for {@link ShoreSearch}: standing as a march sees it, open water by the heightmap. */
    static ShoreSearch.Terrain terrain(World world) {
        MarchRoute.Ground ground = PetMarches.ground(world);
        return new ShoreSearch.Terrain() {
            @Override
            public int standing(int x, int feet, int z) {
                return ground.standing(x, feet, z);
            }

            @Override
            public boolean loaded(int x, int z) {
                return world.isChunkLoaded(x >> 4, z >> 4);
            }

            @Override
            public int water(int x, int z, int depth) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) return NO_WATER;
                Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING);
                if (!wet(top)) return NO_WATER;
                int surface = top.getY();
                for (int below = 1; below < depth; below++) {
                    if (surface - below <= world.getMinHeight() || !wet(world.getBlockAt(x, surface - below, z)))
                        return NO_WATER;
                }
                return surface;
            }
        };
    }

    /** Water, or a water plant standing in it. */
    private static boolean wet(Block block) {
        Material type = block.getType();
        return type == Material.WATER || type == Material.KELP || type == Material.KELP_PLANT
                || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS;
    }
}
