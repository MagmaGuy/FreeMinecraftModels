package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Wild pets spawn the way vanilla's farm animals do. Most come with the world: as a chunk generates, each species
 * stocks it with groups as often as a vanilla animal of the same weight stocks a plains chunk, so a species of weight 8
 * in groups of 4 is as common as cows. Now and then a group is a cluster instead, a much bigger gathering (see
 * {@link PetSpecies.Cluster}). They stay there, waiting in the {@link WildPetStore} while the chunk is unloaded. On top
 * of that, a pass every {@code wildSpawnIntervalTicks} tops up the loaded chunks within 8 chunks of each player.
 * A top-up try picks a random column and height in a chunk, then sends out three packs from it. Each pack walks a
 * few blocks at a time and picks its species by weight at the first spot 24 to 128 blocks from the nearest player,
 * among the species that may stand there, then places that species' group size along the walk. A player's local cap
 * counts the wild pets within 8 chunks of them, and a spot can be used while a player near it has room. Constants
 * carry vanilla's names from NaturalSpawner and MobCategory.
 */
final class WildSpawner {
    /** SPAWN_DISTANCE_CHUNK: chunks around a player that spawn mobs. */
    static final int SPAWN_DISTANCE_CHUNKS = 8;
    /** MIN_SPAWN_DISTANCE: nothing tops up closer than this to a player or to the world spawn. */
    static final double MIN_SPAWN_DISTANCE = 24D;
    /** MobCategory.CREATURE's despawn distance, which NaturalSpawner also uses as the farthest spawn from a player. */
    static final double MAX_SPAWN_DISTANCE = 128D;
    /** Packs sent out from each chunk's starting spot, and the most tries a pack makes to find its first spot. */
    static final int PACKS = 3, PACK_SEARCH = 4;
    /** Each pack step moves nextInt(6) - nextInt(6) blocks on x and on z. */
    static final int PACK_STEP = 6;
    /** A chunk's stocking: each member gets 4 spots, stepping nextInt(5) - nextInt(5) and staying in the chunk. */
    static final int GENERATION_TRIES = 4, GENERATION_STEP = 5;
    /** The roll repeats after every group, so a chance of 1 would never stop; vanilla caps it just under 1 too. */
    static final double MAX_GENERATION_CHANCE = 0.9D;
    /**
     * A vanilla plains chunk's stocking: animals get a group while a roll comes under 0.1, rolled again after each
     * group, from a creature list weighing 46 in all (sheep 12, pig 10, chicken 10, cow 8, horse 5, donkey 1).
     */
    static final double VANILLA_CREATURE_CHANCE = 0.1D;
    static final int VANILLA_CREATURE_WEIGHT = 46;

    private WildSpawner() {
    }

    // ---- stocking new chunks ---------------------------------------------------------------------------------

    /**
     * Stocks a newly generated chunk, as vanilla's spawnMobsForChunkGeneration does for animals. Each species rolls on
     * its own: while a roll comes under its {@link #generationChance}, one group around a random column, where it may
     * stand. Neither the caps, the players, the time nor the mob spawning rule hold it back.
     */
    static void stockChunk(PetManager manager, World world, int cx, int cz, Collection<PetSpecies> species) {
        if (PetsConfig.mode != PetsConfig.Mode.CREATURES || species.isEmpty()) return;
        Random random = ThreadLocalRandom.current();
        List<PetSpecies> candidates = new ArrayList<>();
        for (PetSpecies pet : species)
            if (pet.wildSpawn().enabled() && pet.wildSpawn().weight() > 0 && pet.wildSpawn().allowsWorld(world)) candidates.add(pet);
        // Species that fit the same spots take turns going first.
        Collections.shuffle(candidates, random);
        int minX = cx << 4, minZ = cz << 4;
        for (PetSpecies pet : candidates) {
            double chance = generationChance(pet.wildSpawn().weight(), PetsConfig.wildAbundance);
            while (random.nextDouble() < chance) {
                int x = minX + random.nextInt(16), z = minZ + random.nextInt(16);
                // A column outside the species' biomes, or a biome whose colours are all rare, gets no group this time.
                Location first = surfaceSpot(pet, world, x, z);
                if (first == null || random.nextDouble() >= pet.wildOdds(first)) continue;
                place(manager, pet, world, x, z, gathering(pet.wildSpawn(), () -> pet.pickRareWildVariant(random, first),
                        random), false, random);
            }
        }
    }

    /** How many a stocked group has, and the colour every one of them wears, or null for each member's own pick. */
    record Gathering(int size, String colour) {
    }

    /**
     * A group as a new chunk gets it: at the cluster's chance, a cluster, which at its rareColourChance wears the one
     * colour {@code rareColour} gives; otherwise one of the species' usual groups.
     */
    static Gathering gathering(PetSpecies.WildSpawn wild, Supplier<String> rareColour, Random random) {
        PetSpecies.Cluster cluster = wild.cluster();
        if (cluster.chance() > 0D && random.nextDouble() < cluster.chance())
            return new Gathering(between(cluster.minSize(), cluster.maxSize(), random),
                    random.nextDouble() < cluster.rareColourChance() ? rareColour.get() : null);
        return new Gathering(between(wild.minGroup(), wild.maxGroup(), random), null);
    }

    /**
     * An admin's test cluster at this spot: the species' cluster size, all in {@code colour}, or without one in a rare
     * colour as often as its clusters wear one, placed on any open surface in the spot's chunk. Returns how many stand.
     */
    static int testCluster(PetManager manager, PetSpecies pet, Location at, String colour, Random random) {
        PetSpecies.Cluster cluster = pet.wildSpawn().cluster();
        if (colour == null && random.nextDouble() < cluster.rareColourChance()) colour = pet.pickRareVariant(random);
        Gathering gathering = new Gathering(between(cluster.minSize(), cluster.maxSize(), random), colour);
        return place(manager, pet, at.getWorld(), at.getBlockX(), at.getBlockZ(), gathering, true, random);
    }

    /**
     * Places a group's members around a column, as vanilla stocks a chunk: each member gets 4 spots, stepping up to 4
     * blocks at a time and staying in the column's chunk. Stocking places them only where the species may stand wild;
     * a test cluster ({@code anywhere}) takes any open surface, and a member there without the group's colour picks
     * one by weight. Returns how many stand.
     */
    private static int place(PetManager manager, PetSpecies pet, World world, int x, int z, Gathering gathering,
                             boolean anywhere, Random random) {
        int minX = (x >> 4) << 4, minZ = (z >> 4) << 4, startX = x, startZ = z, standing = 0;
        for (int member = 0; member < gathering.size(); member++) {
            boolean placed = false;
            for (int attempt = 0; !placed && attempt < GENERATION_TRIES; attempt++) {
                Location at = anywhere ? openSpot(pet, world, x, z) : surfaceSpot(pet, world, x, z);
                if (at != null) {
                    String colour = gathering.colour() != null ? gathering.colour()
                            : anywhere ? pet.pickVariant(random) : null;
                    placed = (colour == null ? manager.spawnWild(pet, at) : manager.spawnWild(pet, at, colour)) != null;
                }
                int[] next = generationStep(startX, startZ, x, z, minX, minZ, random);
                x = next[0];
                z = next[1];
            }
            if (placed) standing++;
        }
        return standing;
    }

    /** A number from min to max, both included. */
    static int between(int min, int max, Random random) {
        return min + random.nextInt(max - min + 1);
    }

    /**
     * The groups a species of this weight gets per new chunk of its biomes, on average: as many as a vanilla animal of
     * that weight gets in a plains chunk (0.1 / 0.9 groups per chunk, shared by weight out of 46), times the
     * abundance. Weight 8 gives 0.019, a cow's share.
     */
    static double groupsPerChunk(int weight, double abundance) {
        double vanillaGroups = VANILLA_CREATURE_CHANCE / (1D - VANILLA_CREATURE_CHANCE);
        return Math.max(0D, abundance) * vanillaGroups * Math.max(0, weight) / VANILLA_CREATURE_WEIGHT;
    }

    /**
     * The chance a species' stocking roll comes up, rolled again after each group, so it gets {@link #groupsPerChunk}
     * groups on average: g / (1 + g), at most {@link #MAX_GENERATION_CHANCE}.
     */
    static double generationChance(int weight, double abundance) {
        double groups = groupsPerChunk(weight, abundance);
        return Math.min(groups / (1D + groups), MAX_GENERATION_CHANCE);
    }

    /**
     * Vanilla's step between a chunk's stocking spots: up to 4 blocks on each axis, and when that leaves the chunk, a
     * fresh step from the group's first column until one stays in.
     */
    static int[] generationStep(int startX, int startZ, int x, int z, int minX, int minZ, Random random) {
        x += random.nextInt(GENERATION_STEP) - random.nextInt(GENERATION_STEP);
        z += random.nextInt(GENERATION_STEP) - random.nextInt(GENERATION_STEP);
        while (x < minX || x >= minX + 16 || z < minZ || z >= minZ + 16) {
            x = startX + random.nextInt(GENERATION_STEP) - random.nextInt(GENERATION_STEP);
            z = startZ + random.nextInt(GENERATION_STEP) - random.nextInt(GENERATION_STEP);
        }
        return new int[]{x, z};
    }

    /** Where a member stands when its column is stocked: on the surface, or for swimmers in the top water block. */
    private static Location surfaceSpot(PetSpecies pet, World world, int x, int z) {
        Location at = openSpot(pet, world, x, z);
        return at != null && pet.mayStandWild(at) ? at : null;
    }

    /** That spot whatever the species' biomes, light and heights, as a test spawn takes it. */
    private static Location openSpot(PetSpecies pet, World world, int x, int z) {
        PetSpecies.Placement placement = pet.wildSpawn().placement();
        int surface = world.getHighestBlockYAt(x, z, surface(placement));
        int y = placement == PetSpecies.Placement.WATER ? surface : surface + 1;
        return standingSpot(world, x, y, z, placement);
    }

    /**
     * The surface a column's member is put on. One that stands on the ground goes under the trees, where vanilla puts
     * its animals: read off the top of the leaves, a group stocked in a forest stood on the treetops and stayed
     * there. The others take the top of whatever is there.
     */
    static HeightMap surface(PetSpecies.Placement placement) {
        return placement == PetSpecies.Placement.GROUND ? HeightMap.MOTION_BLOCKING_NO_LEAVES : HeightMap.MOTION_BLOCKING;
    }

    // ---- topping up around players -----------------------------------------------------------------------------

    /** One top-up pass over every world with players in it. */
    static void pass(PetManager manager, Collection<PetSpecies> species) {
        if (PetsConfig.mode != PetsConfig.Mode.CREATURES || species.isEmpty()) return;
        Random random = ThreadLocalRandom.current();
        Map<World, List<Player>> byWorld = new HashMap<>();
        for (Player player : Bukkit.getOnlinePlayers())
            if (player.getGameMode() != GameMode.SPECTATOR)
                byWorld.computeIfAbsent(player.getWorld(), ignored -> new ArrayList<>()).add(player);
        for (Map.Entry<World, List<Player>> entry : byWorld.entrySet()) {
            World world = entry.getKey();
            if (!mobSpawning(world)) continue;
            List<PetSpecies> candidates = new ArrayList<>();
            for (PetSpecies pet : species)
                if (pet.wildSpawn().enabled() && pet.wildSpawn().weight() > 0 && pet.wildSpawn().allowsWorld(world)
                        && pet.wildSpawn().allowsTime(world)) candidates.add(pet);
            if (!candidates.isEmpty()) passWorld(manager, world, entry.getValue(), candidates, random);
        }
    }

    /**
     * Every chunk within {@link #SPAWN_DISTANCE_CHUNKS} of a player gets one wildSpawnChunkChance roll, and the chunks
     * that roll in are worked through in random order, each topped up when one of the players whose area holds it has
     * room. The odds and the order come out as shuffling every chunk and rolling each in turn, with no map of all of
     * them: a chunk is rolled by the first player whose area holds it, and only the few that roll in are kept and
     * shuffled.
     */
    private static void passWorld(PetManager manager, World world, List<Player> players, List<PetSpecies> candidates,
                                  Random random) {
        int count = players.size();
        int[] playerX = new int[count], playerZ = new int[count];
        for (int index = 0; index < count; index++) {
            Location at = players.get(index).getLocation();
            playerX[index] = at.getBlockX() >> 4;
            playerZ[index] = at.getBlockZ() >> 4;
        }
        long[] rolled = new long[16];
        int picked = 0;
        for (int index = 0; index < count; index++)
            for (int dx = -SPAWN_DISTANCE_CHUNKS; dx <= SPAWN_DISTANCE_CHUNKS; dx++)
                for (int dz = -SPAWN_DISTANCE_CHUNKS; dz <= SPAWN_DISTANCE_CHUNKS; dz++) {
                    if (random.nextDouble() >= PetsConfig.wildSpawnChunkChance) continue;
                    int cx = playerX[index] + dx, cz = playerZ[index] + dz;
                    // An earlier player's area holds it, so its roll came then.
                    if (nearAny(playerX, playerZ, index, cx, cz)) continue;
                    if (picked == rolled.length) rolled = Arrays.copyOf(rolled, picked * 2);
                    rolled[picked++] = chunkKey(cx, cz);
                }
        // Collections.shuffle's walk, on the keys as they are.
        for (int index = picked - 1; index > 0; index--) {
            int other = random.nextInt(index + 1);
            long swap = rolled[index];
            rolled[index] = rolled[other];
            rolled[other] = swap;
        }
        for (int next = 0; next < picked; next++) {
            long key = rolled[next];
            int cx = (int) (key >> 32), cz = (int) key;
            if (!world.isChunkLoaded(cx, cz) || !roomAround(manager, players, playerX, playerZ, cx, cz)) continue;
            int x = (cx << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
            int surface = world.getHighestBlockYAt(x, z);
            int y = world.getMinHeight() + random.nextInt(Math.max(1, surface + 2 - world.getMinHeight()));
            spawnPacks(manager, world, players, candidates, x, y, z, random);
        }
    }

    /** Whether the chunk lies within {@link #SPAWN_DISTANCE_CHUNKS} of one of the first {@code before} players. */
    private static boolean nearAny(int[] playerX, int[] playerZ, int before, int cx, int cz) {
        for (int index = 0; index < before; index++)
            if (Math.abs(playerX[index] - cx) <= SPAWN_DISTANCE_CHUNKS && Math.abs(playerZ[index] - cz) <= SPAWN_DISTANCE_CHUNKS)
                return true;
        return false;
    }

    /** Whether a player whose area holds the chunk is under the overall cap. */
    private static boolean roomAround(PetManager manager, List<Player> players, int[] playerX, int[] playerZ, int cx,
                                      int cz) {
        for (int index = 0; index < players.size(); index++)
            if (Math.abs(playerX[index] - cx) <= SPAWN_DISTANCE_CHUNKS && Math.abs(playerZ[index] - cz) <= SPAWN_DISTANCE_CHUNKS
                    && manager.wildInSpawnArea(players.get(index), null) < PetsConfig.wildPetCapPerPlayer) return true;
        return false;
    }

    /**
     * Vanilla's packs from one chunk's starting spot. A pack makes 1 to 4 steps looking for a spot where some species
     * may stand; once it has one, its step count becomes that species' group size, counted from the steps already
     * made, as in vanilla. All packs together place at most the species' maxGroup.
     */
    private static void spawnPacks(PetManager manager, World world, List<Player> players, List<PetSpecies> candidates,
                                   int startX, int startY, int startZ, Random random) {
        int placed = 0;
        for (int pack = 0; pack < PACKS; pack++) {
            int x = startX, z = startZ;
            PetSpecies pet = null;
            int steps = 1 + random.nextInt(PACK_SEARCH);
            for (int step = 0; step < steps; step++) {
                x += packStep(random);
                z += packStep(random);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;
                if (pet == null) {
                    pet = pick(candidates, world, players, x, startY, z, random);
                    if (pet == null) continue;
                    // A biome whose colours are all rare gets their packs as rarely; this pack is skipped.
                    Location first = spot(pet, world, players, x, startY, z);
                    if (first == null || random.nextDouble() >= pet.wildOdds(first)) break;
                    steps = between(pet.wildSpawn().minGroup(), pet.wildSpawn().maxGroup(), random);
                }
                Location at = spot(pet, world, players, x, startY, z);
                if (at == null) continue;
                if (!roomNear(manager, players, at, pet)) return;
                if (manager.spawnWild(pet, at) != null && ++placed >= pet.wildSpawn().maxGroup()) return;
            }
        }
    }

    /** Where a member of the species may top up in this column at the pack's height, or null. */
    private static Location spot(PetSpecies pet, World world, List<Player> players, int x, int y, int z) {
        Location at = standingSpot(world, x, y, z, pet.wildSpawn().placement());
        if (at == null || !pet.mayStandWild(at) || !inRange(world, players, at)) return null;
        return at;
    }

    /** One pack step on one axis: nextInt(6) - nextInt(6), so -5 to 5 and most often 0. */
    static int packStep(Random random) {
        return random.nextInt(PACK_STEP) - random.nextInt(PACK_STEP);
    }

    /** Vanilla's distance rule: farther than 24 blocks from the nearest player and the world spawn, within 128 of that player. */
    private static boolean inRange(World world, List<Player> players, Location at) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Player player : players) nearest = Math.min(nearest, player.getLocation().distanceSquared(at));
        if (nearest <= MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE || nearest > MAX_SPAWN_DISTANCE * MAX_SPAWN_DISTANCE) return false;
        return world.getSpawnLocation().distanceSquared(at) > MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE;
    }

    /** Whether a player near the spot has room under both the overall cap and the species' cap. */
    private static boolean roomNear(PetManager manager, List<Player> players, Location at, PetSpecies pet) {
        for (Player player : players)
            if (inSpawnArea(player, at) && manager.wildInSpawnArea(player, null) < PetsConfig.wildPetCapPerPlayer
                    && manager.wildInSpawnArea(player, pet) < pet.wildSpawn().cap()) return true;
        return false;
    }

    /**
     * A species by weight among those that may top up in this column, as vanilla picks a mob from the biome's spawn
     * list at the pack's first spot.
     */
    private static PetSpecies pick(List<PetSpecies> candidates, World world, List<Player> players, int x, int y, int z,
                                   Random random) {
        List<PetSpecies> fitting = new ArrayList<>();
        for (PetSpecies pet : candidates) if (spot(pet, world, players, x, y, z) != null) fitting.add(pet);
        return pickWeighted(fitting, pet -> pet.wildSpawn().weight(), random);
    }

    // ---- shared ------------------------------------------------------------------------------------------------

    /** One entry, each as likely as its share of the total weight; null when nothing weighs anything. */
    static <T> T pickWeighted(List<T> entries, ToIntFunction<T> weight, Random random) {
        int total = 0;
        for (T entry : entries) total += Math.max(0, weight.applyAsInt(entry));
        if (total <= 0) return null;
        int roll = random.nextInt(total);
        for (T entry : entries) {
            roll -= Math.max(0, weight.applyAsInt(entry));
            if (roll < 0) return entry;
        }
        return null;
    }

    /**
     * Where a member may stand in this column, or null. Ground and water members stand at the given height, so a
     * top-up's caves and deep water count; a ground member never stands on a tree's leaves. Air and water-surface
     * members float just over the column's surface, where the pet's hover takes over.
     */
    static Location standingSpot(World world, int x, int y, int z, PetSpecies.Placement placement) {
        switch (placement) {
            case AIR, WATER_SURFACE -> {
                Block top = world.getHighestBlockAt(x, z);
                boolean water = top.getType() == Material.WATER;
                if (placement == PetSpecies.Placement.WATER_SURFACE ? !water : (top.isLiquid() || !top.getType().isSolid()))
                    return null;
                Block above = top.getRelative(0, 1, 0);
                if (!above.isPassable() || !above.getRelative(0, 1, 0).isPassable()) return null;
                return new Location(world, x + 0.5D, top.getY() + 1D, z + 0.5D);
            }
            case GROUND -> {
                Block feet = world.getBlockAt(x, y, z);
                if (!feet.isPassable() || feet.isLiquid() || !feet.getRelative(0, 1, 0).isPassable()) return null;
                // Solid ground, and not a tree's leaves, which a top-up's random height can land on as well.
                Material under = feet.getRelative(0, -1, 0).getType();
                if (PetMotion.isLeaves(under) || !under.isSolid()) return null;
                return new Location(world, x + 0.5D, y, z + 0.5D);
            }
            case WATER -> {
                if (world.getBlockAt(x, y, z).getType() != Material.WATER) return null;
                return new Location(world, x + 0.5D, y, z + 0.5D);
            }
        }
        return null;
    }

    /** Whether the spot is in the player's spawn area: the chunks within 8 of theirs, in the same world. */
    static boolean inSpawnArea(Player player, Location at) {
        return player.getWorld() == at.getWorld() && inSpawnArea(player.getLocation().getBlockX(), player.getLocation().getBlockZ(),
                at.getBlockX(), at.getBlockZ());
    }

    static boolean inSpawnArea(int playerX, int playerZ, int x, int z) {
        return Math.abs((playerX >> 4) - (x >> 4)) <= SPAWN_DISTANCE_CHUNKS && Math.abs((playerZ >> 4) - (z >> 4)) <= SPAWN_DISTANCE_CHUNKS;
    }

    static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /**
     * Whether the world lets mobs spawn. The rule is SPAWN_MOBS from 1.21.11 and DO_MOB_SPAWNING before, so it is
     * looked up by name; a server where neither links always spawns.
     */
    @SuppressWarnings("unchecked")
    private static boolean mobSpawning(World world) {
        for (String name : new String[]{"SPAWN_MOBS", "DO_MOB_SPAWNING"}) {
            try {
                Object rule = GameRule.class.getField(name).get(null);
                if (rule instanceof GameRule<?> gameRule) return !Boolean.FALSE.equals(world.getGameRuleValue((GameRule<Boolean>) gameRule));
            } catch (Throwable missing) {
                // Not this version's name, or a server whose GameRule type differs from the API FMM is built against.
            }
        }
        return true;
    }
}
