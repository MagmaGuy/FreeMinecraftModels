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
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * Night migrations: now and then a flock of a migrating species crosses the sky over a player and is gone after the
 * crossing. The members are wild pets of the species on a migration route, so they look and animate like the pets,
 * and a player who reaches one can tame it. Each player gets one roll per night, the first time the schedule sees them
 * in that night: each migrating species is rolled at its own chancePerNight, and of those that won one is picked by
 * weight. A won roll picks a moment in the rest of the night, and the flock starts then if the player is under open sky
 * (otherwise it tries again until the night runs out). A flying species takes part through a {@code migration:}
 * section in its pet file, which holds its flock settings, as long as migrate is among its behaviors; pets.yml only
 * switches migrations on and limits them server-wide.
 */
public final class PetMigrations {
    /** The pets.yml settings: whether flocks fly, how many at once, and which species may. */
    public record Settings(boolean enabled, int maxActiveFlocks, List<String> species) {
    }

    private record Roll(UUID world, long night, long startAt, String species) {
    }

    /** The flock settings pets.yml held before they moved to each species' migration section. */
    static final List<String> MOVED_KEYS = List.of("chancePerNight", "minFlockSize", "maxFlockSize", "altitude", "speed",
            "durationSeconds", "viewDistance", "rareFlockChance");
    private static final int SCHEDULE_TICKS = 100;
    private static Settings settings = new Settings(true, 3, List.of());

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
                        "and in CREATURES mode a player who reaches one can tame it. A species migrates when it flies,",
                        "its pet file has a migration: section and migrate is among its behaviors; the console says when",
                        "one cannot. How often its flocks come, their size, height, speed and length are set in that",
                        "migration: section."),
                config, "migrations.enabled", true);
        // The flock settings moved to each species' pet file, so they are taken out of pets.yml instead of
        // advertising settings that do nothing.
        for (String key : MOVED_KEYS) config.set("migrations." + key, null);
        int maxActive = ConfigurationEngine.setInt(
                List.of("Most flocks in the air at once across the server."),
                config, "migrations.maxActiveFlocks", 3);
        List<?> species = ConfigurationEngine.setList(
                List.of("Species ids that may migrate. Empty means every species that can (see enabled above)."),
                config, "migrations.species", List.of());
        List<String> ids = new ArrayList<>();
        for (Object id : species) if (id != null) ids.add(id.toString().toLowerCase(Locale.ROOT));
        settings = new Settings(enabled, Math.max(1, maxActive), List.copyOf(ids));
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
        PetManager manager = PetManager.get();
        if (!current.enabled() || manager == null) return;
        List<PetSpecies> candidates = candidates(manager, current);
        if (candidates.isEmpty()) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL || PetSky.isDay(world)) continue;
            long now = world.getFullTime();
            long night = Math.floorDiv(now, 24000L);
            for (Player player : world.getPlayers()) {
                UUID id = player.getUniqueId();
                Roll roll = rolls.get(id);
                if (roll == null || !roll.world().equals(world.getUID()) || roll.night() != night) {
                    roll = new Roll(world.getUID(), night, -1L, null);
                    PetSpecies won = rollNight(candidates, PetMigrations::migration, random::nextDouble);
                    if (won != null) {
                        long latest = latest(now, won);
                        if (latest > now)
                            roll = new Roll(world.getUID(), night, now + random.nextLong(latest - now + 1L), won.id());
                    }
                    rolls.put(id, roll);
                }
                if (roll.startAt() < 0L || now < roll.startAt()) continue;
                PetSpecies species = manager.species(roll.species());
                // A species gone since the roll, or too late for a whole crossing, drops the roll; a flock that could
                // not start keeps it for the next look.
                if (species == null || !candidates.contains(species) || now > latest(now, species))
                    rolls.put(id, new Roll(world.getUID(), night, -1L, null));
                else if (start(player, species, false) != null) rolls.put(id, new Roll(world.getUID(), night, -1L, null));
            }
        }
    }

    /** The last moment tonight a flock of the species can start and still finish its crossing before dawn. */
    private static long latest(long now, PetSpecies species) {
        return PetSky.nightEnd(now) - migration(species).durationTicks();
    }

    private static PetInteractions.Migration migration(PetSpecies species) {
        return species.interactions().migration();
    }

    /**
     * A player's roll for the night: each species is rolled at its own chancePerNight, and of those that won one is
     * picked by weight. Null when none won.
     */
    static <T> T rollNight(List<T> candidates, Function<T, PetInteractions.Migration> migration, DoubleSupplier random) {
        List<T> won = new ArrayList<>();
        for (T species : candidates)
            if (random.getAsDouble() < migration.apply(species).chancePerNight()) won.add(species);
        return pick(won, migration, random);
    }

    /**
     * Sends a flock over the player now. With a species, that species flies even without a migration section, with
     * the default flock settings; forced, the open sky and nearby-flock checks are skipped (the admin command).
     * Returns null when no flock could start.
     */
    public MigrationFlock start(Player player, PetSpecies species, boolean forced) {
        PetManager manager = PetManager.get();
        if (manager == null) return null;
        Settings current = settings;
        flocks.removeIf(MigrationFlock::isDone);
        if (!forced && flocks.size() >= current.maxActiveFlocks()) return null;
        PetSpecies chosen = species != null ? species
                : pick(candidates(manager, current), PetMigrations::migration, ThreadLocalRandom.current()::nextDouble);
        if (chosen == null) return null;
        PetInteractions.Migration look = chosen.interactions().migration();
        if (look == null) look = PetInteractions.Migration.DEFAULT;
        Location at = player.getLocation();
        if (!forced) {
            // Not where a flock is already in view, by the farther of the two view distances.
            for (MigrationFlock flock : flocks)
                if (flock.isNear(at, Math.max(flock.viewDistance(), look.viewDistance()))) return null;
            if (!PetSky.underOpenSky(at)) return null;
        }
        MigrationFlock flock = MigrationFlock.launch(plugin, player, chosen, look);
        if (flock != null) flocks.add(flock);
        return flock;
    }

    /**
     * The migrating species pets.yml allows. A species that does not fly, or whose migrants would not run the migrate
     * behavior, is left out, since its flock would hover or fall where it spawned; its pet file was warned about at
     * load. So is one with a weight or chancePerNight of 0.
     */
    private static List<PetSpecies> candidates(PetManager manager, Settings current) {
        List<PetSpecies> candidates = new ArrayList<>();
        for (PetSpecies species : manager.species()) {
            PetInteractions.Migration look = species.interactions().migration();
            if (look == null || look.weight() <= 0D || look.chancePerNight() <= 0D || !manager.canMigrate(species))
                continue;
            if (!current.species().isEmpty() && !current.species().contains(species.id())) continue;
            candidates.add(species);
        }
        return candidates;
    }

    /** One of the species, by the weight of its migration section; null when there is none. */
    static <T> T pick(List<T> candidates, Function<T, PetInteractions.Migration> migration, DoubleSupplier random) {
        if (candidates.isEmpty()) return null;
        double total = 0D;
        for (T species : candidates) total += migration.apply(species).weight();
        double roll = random.getAsDouble() * total;
        for (T species : candidates) {
            roll -= migration.apply(species).weight();
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
