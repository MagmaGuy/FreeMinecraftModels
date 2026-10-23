package com.magmaguy.freeminecraftmodels.pets.migration;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.PetSpecies;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetInteractions;
import com.magmaguy.freeminecraftmodels.pets.interactions.PetSky;
import com.magmaguy.magmacore.config.ConfigurationEngine;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Night migrations: now and then a flock of a migrating species crosses the sky over a player and is gone after the
 * crossing. The members are wild pets of the species on a migration route, so they look and animate like the pets,
 * and a player who reaches one can tame it. Each player gets one roll per night, the first time the schedule sees them in
 * that night; a won roll picks a moment in the rest of the night, and the flock starts then if the player is under
 * open sky (otherwise it tries again until the night runs out). Species take part through a {@code migration:}
 * section in their pet file; the schedule's own settings live in pets.yml.
 */
public final class PetMigrations {
    /** The pets.yml settings of the schedule and of every flock. */
    public record Settings(boolean enabled, double chancePerNight, int minFlockSize, int maxFlockSize, double altitude,
                           double speed, int durationTicks, int viewDistance, int maxActiveFlocks, List<String> species) {
    }

    private record Roll(UUID world, long night, long startAt) {
    }

    private static final int SCHEDULE_TICKS = 100;
    private static Settings settings = new Settings(true, 0.15D, 8, 14, 28D, 0.15D, 1800, 160, 3, List.of());

    private final Plugin plugin;
    private final Map<UUID, Roll> rolls = new HashMap<>();
    private final List<MigrationFlock> flocks = new ArrayList<>();

    public PetMigrations(Plugin plugin) {
        this.plugin = plugin;
    }

    /** Reads the {@code migrations} section of pets.yml, writing any missing key with its default and comment. */
    public static void loadSettings(FileConfiguration config) {
        boolean enabled = ConfigurationEngine.setBoolean(
                List.of("Night migrations: rare flocks of a migrating pet species crossing the night sky over players.",
                        "The members are wild pets of the species flying a migration route: same model and animations,",
                        "and in CREATURES mode a player who reaches one can tame it. They leave after durationSeconds.",
                        "A species migrates when its pet file has a migration: section."),
                config, "migrations.enabled", true);
        double chance = ConfigurationEngine.setDouble(
                List.of("Chance, 0 to 1, that a player gets a flock over them on a given night."),
                config, "migrations.chancePerNight", 0.15D);
        int minSize = ConfigurationEngine.setInt(List.of("Fewest and most creatures in one flock."),
                config, "migrations.minFlockSize", 8);
        int maxSize = ConfigurationEngine.setInt(config, "migrations.maxFlockSize", 14);
        double altitude = ConfigurationEngine.setDouble(
                List.of("Blocks above the higher of the player and the ground under them that the flock flies at."),
                config, "migrations.altitude", 28D);
        double speed = ConfigurationEngine.setDouble(
                List.of("Flock speed in blocks per tick (0.15 is 3 blocks a second)."),
                config, "migrations.speed", 0.15D);
        double duration = ConfigurationEngine.setDouble(
                List.of("Seconds from the flock appearing to its removal. It passes over the player halfway through."),
                config, "migrations.durationSeconds", 90D);
        int viewDistance = ConfigurationEngine.setInt(
                List.of("Blocks from which players see a flock; well past the usual model view distance.",
                        "Clients still need the chunks the flock flies over within their render distance."),
                config, "migrations.viewDistance", 160);
        int maxActive = ConfigurationEngine.setInt(
                List.of("Most flocks in the air at once across the server."),
                config, "migrations.maxActiveFlocks", 3);
        List<?> species = ConfigurationEngine.setList(
                List.of("Species ids that may migrate. Empty means every species with a migration: section."),
                config, "migrations.species", List.of());
        List<String> ids = new ArrayList<>();
        for (Object id : species) if (id != null) ids.add(id.toString().toLowerCase(Locale.ROOT));
        int fewest = Math.max(1, minSize);
        settings = new Settings(enabled, Math.max(0D, Math.min(1D, chance)), fewest, Math.max(fewest, maxSize),
                Math.max(4D, altitude), Math.max(0.02D, speed), (int) Math.max(100D, duration * 20D),
                Math.max(16, viewDistance), Math.max(1, maxActive), List.copyOf(ids));
    }

    public static Settings settings() {
        return settings;
    }

    /** Called by the interaction pass with its clock; the schedule looks around every five seconds. */
    public void tick(long ticks) {
        if (ticks % SCHEDULE_TICKS != 0L) return;
        flocks.removeIf(MigrationFlock::isDone);
        schedule();
    }

    private void schedule() {
        Settings current = settings;
        rolls.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
        if (!current.enabled() || current.chancePerNight() <= 0D || PetManager.get() == null) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL || PetSky.isDay(world)) continue;
            long now = world.getFullTime();
            long night = Math.floorDiv(now, 24000L);
            long latest = PetSky.nightEnd(now) - current.durationTicks();
            for (Player player : world.getPlayers()) {
                UUID id = player.getUniqueId();
                Roll roll = rolls.get(id);
                if (roll == null || !roll.world().equals(world.getUID()) || roll.night() != night) {
                    long startAt = -1L;
                    if (latest > now && random.nextDouble() < current.chancePerNight())
                        startAt = now + random.nextLong(latest - now + 1L);
                    roll = new Roll(world.getUID(), night, startAt);
                    rolls.put(id, roll);
                }
                if (roll.startAt() < 0L || now < roll.startAt()) continue;
                // Too late for a whole crossing, or a flock is up: drop the roll, or keep it for the next look.
                if (now > latest) rolls.put(id, new Roll(world.getUID(), night, -1L));
                else if (start(player, null, false) != null) rolls.put(id, new Roll(world.getUID(), night, -1L));
            }
        }
    }

    /**
     * Sends a flock over the player now. With a species, that species flies even without a migration section;
     * forced, the open sky and nearby-flock checks are skipped (the admin command). Returns null when no flock
     * could start.
     */
    public MigrationFlock start(Player player, PetSpecies species, boolean forced) {
        PetManager manager = PetManager.get();
        if (manager == null) return null;
        Settings current = settings;
        flocks.removeIf(MigrationFlock::isDone);
        if (!forced && flocks.size() >= current.maxActiveFlocks()) return null;
        Location at = player.getLocation();
        if (!forced) {
            for (MigrationFlock flock : flocks) if (flock.isNear(at, current.viewDistance())) return null;
            if (!PetSky.underOpenSky(at)) return null;
        }
        PetSpecies chosen = species != null ? species : pick(manager, current);
        if (chosen == null) return null;
        PetInteractions.Migration look = chosen.interactions().migration();
        if (look == null) look = new PetInteractions.Migration(null, 1D, null, 1D);
        MigrationFlock flock = MigrationFlock.launch(plugin, player, chosen, look, current);
        if (flock != null) flocks.add(flock);
        return flock;
    }


    /** A migrating species by weight, among those pets.yml allows. */
    private static PetSpecies pick(PetManager manager, Settings current) {
        List<PetSpecies> candidates = new ArrayList<>();
        double total = 0D;
        for (PetSpecies species : manager.species()) {
            PetInteractions.Migration look = species.interactions().migration();
            if (look == null || look.weight() <= 0D) continue;
            if (!current.species().isEmpty() && !current.species().contains(species.id())) continue;
            candidates.add(species);
            total += look.weight();
        }
        if (candidates.isEmpty()) return null;
        double roll = ThreadLocalRandom.current().nextDouble() * total;
        for (PetSpecies species : candidates) {
            roll -= species.interactions().migration().weight();
            if (roll < 0D) return species;
        }
        return candidates.get(candidates.size() - 1);
    }

    /** Flocks in the air now. */
    public List<MigrationFlock> flocks() {
        flocks.removeIf(MigrationFlock::isDone);
        return List.copyOf(flocks);
    }

    /** Takes every flock out of the sky and forgets the rolls. */
    public void stopAll() {
        for (MigrationFlock flock : flocks) flock.remove();
        flocks.clear();
        rolls.clear();
    }
}
