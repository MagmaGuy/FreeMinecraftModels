package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.ToIntFunction;

/**
 * Wild pets spawn the way vanilla's farm animals do. Most come with the world: as a chunk generates, it is stocked
 * with groups of pets, and they stay there, waiting in the {@link WildPetStore} while the chunk is unloaded. On top
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

    private WildSpawner() {
    }

    // ---- stocking new chunks ---------------------------------------------------------------------------------

    /**
     * Stocks a newly generated chunk, as vanilla's spawnMobsForChunkGeneration does for animals: while a roll comes
     * under {@code wildGenerationChance}, one group of a species picked by weight, placed on the surface around a
     * random column. Neither the caps, the players, the time nor the mob spawning rule hold it back.
     */
    static void stockChunk(PetManager manager, World world, int cx, int cz, Collection<PetSpecies> species) {
        if (PetsConfig.mode != PetsConfig.Mode.CREATURES || species.isEmpty()) return;
        Random random = ThreadLocalRandom.current();
        List<PetSpecies> candidates = new ArrayList<>();
        for (PetSpecies pet : species)
            if (pet.wildSpawn().enabled() && pet.wildSpawn().weight() > 0 && pet.wildSpawn().allowsWorld(world)) candidates.add(pet);
        if (candidates.isEmpty()) return;
        int minX = cx << 4, minZ = cz << 4;
        double chance = Math.min(PetsConfig.wildGenerationChance, MAX_GENERATION_CHANCE);
        while (random.nextDouble() < chance) {
            int x = minX + random.nextInt(16), z = minZ + random.nextInt(16);
            List<PetSpecies> fitting = new ArrayList<>();
            for (PetSpecies pet : candidates) if (surfaceSpot(pet, world, x, z) != null) fitting.add(pet);
            PetSpecies pet = pickWeighted(fitting, candidate -> candidate.wildSpawn().weight(), random);
            if (pet == null) continue;
            // A biome whose colours are all rare gets their groups as rarely.
            Location first = surfaceSpot(pet, world, x, z);
            if (first == null || random.nextDouble() >= pet.wildOdds(first)) continue;
            int group = pet.wildSpawn().minGroup() + random.nextInt(pet.wildSpawn().maxGroup() - pet.wildSpawn().minGroup() + 1);
            int startX = x, startZ = z;
            for (int member = 0; member < group; member++) {
                boolean placed = false;
                for (int attempt = 0; !placed && attempt < GENERATION_TRIES; attempt++) {
                    Location at = surfaceSpot(pet, world, x, z);
                    placed = at != null && manager.spawnWild(pet, at) != null;
                    int[] next = generationStep(startX, startZ, x, z, minX, minZ, random);
                    x = next[0];
                    z = next[1];
                }
            }
        }
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
        int surface = world.getHighestBlockYAt(x, z);
        int y = pet.wildSpawn().placement() == PetSpecies.Placement.WATER ? surface : surface + 1;
        Location at = standingSpot(world, x, y, z, pet.wildSpawn().placement());
        return at != null && pet.mayStandWild(at) ? at : null;
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

    private static void passWorld(PetManager manager, World world, List<Player> players, List<PetSpecies> candidates,
                                  Random random) {
        Map<Long, List<Player>> chunks = new HashMap<>();
        for (Player player : players) {
            int cx = player.getLocation().getBlockX() >> 4, cz = player.getLocation().getBlockZ() >> 4;
            for (int dx = -SPAWN_DISTANCE_CHUNKS; dx <= SPAWN_DISTANCE_CHUNKS; dx++)
                for (int dz = -SPAWN_DISTANCE_CHUNKS; dz <= SPAWN_DISTANCE_CHUNKS; dz++)
                    chunks.computeIfAbsent(chunkKey(cx + dx, cz + dz), ignored -> new ArrayList<>()).add(player);
        }
        List<Long> order = new ArrayList<>(chunks.keySet());
        Collections.shuffle(order, random);
        for (long key : order) {
            if (random.nextDouble() >= PetsConfig.wildSpawnChunkChance) continue;
            int cx = (int) (key >> 32), cz = (int) key;
            if (!world.isChunkLoaded(cx, cz)) continue;
            if (chunks.get(key).stream().noneMatch(player -> manager.wildInSpawnArea(player, null) < PetsConfig.wildPetCapPerPlayer))
                continue;
            int x = (cx << 4) + random.nextInt(16), z = (cz << 4) + random.nextInt(16);
            int surface = world.getHighestBlockYAt(x, z);
            int y = world.getMinHeight() + random.nextInt(Math.max(1, surface + 2 - world.getMinHeight()));
            spawnPacks(manager, world, players, candidates, x, y, z, random);
        }
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
                    steps = pet.wildSpawn().minGroup() + random.nextInt(pet.wildSpawn().maxGroup() - pet.wildSpawn().minGroup() + 1);
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
     * top-up's caves and deep water count. Air and water-surface members float just over the column's surface, where
     * the pet's hover takes over.
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
                if (!feet.getRelative(0, -1, 0).getType().isSolid()) return null;
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
