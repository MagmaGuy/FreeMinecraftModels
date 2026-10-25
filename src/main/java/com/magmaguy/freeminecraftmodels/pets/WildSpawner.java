package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.config.PetsConfig;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
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
 * Wild pets spawn the way vanilla's ambient mobs (bats, fish) do, because like them they are never saved with the
 * world. Each pass tries the loaded chunks within 8 chunks of each player. A try picks a random column and height in
 * the chunk, then sends out three packs from it. Each pack walks a few blocks at a time and picks its species by
 * weight at the first spot 24 to 128 blocks from the nearest player, among the species that may stand there. Then it
 * places that species' group size along the walk. A player's local cap counts the wild pets within 8 chunks of them,
 * and a spot can be used while a player near it has room. Wild pets despawn like vanilla mobs too: at once past
 * 128 blocks from every player, and past 32 blocks at 1 in 800 a tick once no player has come within 32 blocks for
 * 600 ticks. Constants carry vanilla's names from NaturalSpawner, MobCategory and Mob.
 */
final class WildSpawner {
    /** SPAWN_DISTANCE_CHUNK: chunks around a player that spawn mobs. */
    static final int SPAWN_DISTANCE_CHUNKS = 8;
    /** MIN_SPAWN_DISTANCE: nothing spawns closer than this to a player or to the world spawn. */
    static final double MIN_SPAWN_DISTANCE = 24D;
    /** MobCategory.noDespawnDistance: a mob this close to a player never despawns. */
    static final double NO_DESPAWN_DISTANCE = 32D;
    /** Mob.checkDespawn: ticks alone before the random despawn starts, and its one-in odds per tick. */
    static final int IDLE_TICKS_BEFORE_DESPAWN = 600, DESPAWN_ODDS_PER_TICK = 800;
    /** Packs sent out from each chunk's starting spot, and the most tries a pack makes to find its first spot. */
    static final int PACKS = 3, PACK_SEARCH = 4;
    /** Each pack step moves nextInt(6) - nextInt(6) blocks on x and on z. */
    static final int PACK_STEP = 6;

    private WildSpawner() {
    }

    /** One spawn pass over every world with players in it. */
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
                if (pet.wildSpawn().enabled() && pet.wildSpawn().weight() > 0 && pet.wildSpawn().allowsWorld(world))
                    candidates.add(pet);
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
                    steps = pet.wildSpawn().minGroup() + random.nextInt(pet.wildSpawn().maxGroup() - pet.wildSpawn().minGroup() + 1);
                }
                Location at = spot(pet, world, players, x, startY, z);
                if (at == null) continue;
                if (!roomNear(manager, players, at, pet)) return;
                if (manager.spawnWild(pet, at) != null && ++placed >= pet.wildSpawn().maxGroup()) return;
            }
        }
    }

    /** Where a member of the species may spawn in this column at the pack's height, or null. */
    private static Location spot(PetSpecies pet, World world, List<Player> players, int x, int y, int z) {
        Location at = standingSpot(world, x, y, z, pet.wildSpawn().placement());
        if (at == null || !pet.wildSpawn().allows(at) || !inRange(world, players, at)) return null;
        return at;
    }

    /** One pack step on one axis: nextInt(6) - nextInt(6), so -5 to 5 and most often 0. */
    static int packStep(Random random) {
        return random.nextInt(PACK_STEP) - random.nextInt(PACK_STEP);
    }

    /**
     * Vanilla's distance rule: farther than 24 blocks from the nearest player and from the world spawn, and within
     * the hard despawn distance of that player, so nothing spawns only to vanish.
     */
    private static boolean inRange(World world, List<Player> players, Location at) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Player player : players) nearest = Math.min(nearest, player.getLocation().distanceSquared(at));
        if (nearest <= MIN_SPAWN_DISTANCE * MIN_SPAWN_DISTANCE) return false;
        if (nearest > PetsConfig.wildDespawnDistance * PetsConfig.wildDespawnDistance) return false;
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
     * A species by weight among those that may spawn in this column, as vanilla picks a mob from the biome's spawn
     * list at the pack's first spot.
     */
    private static PetSpecies pick(List<PetSpecies> candidates, World world, List<Player> players, int x, int y, int z,
                                   Random random) {
        List<PetSpecies> fitting = new ArrayList<>();
        for (PetSpecies pet : candidates) if (spot(pet, world, players, x, y, z) != null) fitting.add(pet);
        return pickWeighted(fitting, pet -> pet.wildSpawn().weight(), random);
    }

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
     * Where a member may stand in this column, or null. Ground and water members stand at the pack's height, as
     * vanilla's do, so caves and deep water count. Air and water-surface members float just over the column's
     * surface, where the pet's hover takes over.
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

    /**
     * Vanilla's despawn rule for a mob nobody keeps: gone at once past the hard distance, safe within 32 blocks, and in
     * between, once it has gone 600 ticks without a player within 32 blocks, a 1 in 800 chance per tick.
     *
     * @param idleTicks ticks without a player within 32 blocks, this check included
     * @param ticks     ticks since the last check, folded into one roll
     */
    static boolean shouldDespawn(double nearestPlayer, double hardDistance, int idleTicks, int ticks, Random random) {
        if (nearestPlayer > hardDistance) return true;
        if (nearestPlayer <= NO_DESPAWN_DISTANCE || idleTicks <= IDLE_TICKS_BEFORE_DESPAWN) return false;
        return random.nextDouble() < despawnChance(ticks);
    }

    /** The chance that at least one of {@code ticks} one-in-800 rolls hits. */
    static double despawnChance(int ticks) {
        return 1D - Math.pow(1D - 1D / DESPAWN_ODDS_PER_TICK, Math.max(1, ticks));
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

    /** Distance from the body to the nearest player who is not spectating; infinity when there is none. */
    static double nearestPlayerDistance(LivingEntity body) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Player player : body.getWorld().getPlayers())
            if (player.getGameMode() != GameMode.SPECTATOR) nearest = Math.min(nearest, player.getLocation().distance(body.getLocation()));
        return nearest;
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
