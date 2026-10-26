package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.freeminecraftmodels.pets.PetLuaTable;
import com.magmaguy.magmacore.ai.MindActionRequester;
import com.magmaguy.magmacore.ai.MindActuator;
import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.ai.MindControl;
import com.magmaguy.magmacore.ai.MindMemoryView;
import com.magmaguy.magmacore.ai.MindPerception;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindRunawayException;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.ai.MindStopReason;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.shaded.luaj.vm2.LuaError;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import com.magmaguy.shaded.luaj.vm2.Varargs;
import com.magmaguy.shaded.luaj.vm2.lib.ThreeArgFunction;
import com.magmaguy.shaded.luaj.vm2.lib.TwoArgFunction;
import com.magmaguy.shaded.luaj.vm2.lib.VarArgFunction;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the shipped pet behavior modules against a strict stand-in for c.pet, and for the parts of c.perception and
 * c.actuator they use. Every locomotion's default list must build, and every module runs through each pet state the
 * stand-in plays and each way its methods answer, including the "nothing" each one documents. Reading a name c.pet or
 * c.settings lacks, passing a method arguments of the wrong shape, calling a leased method without its controls, or
 * failing on a nothing answer or an extreme setting fails here.
 */
class ShippedPetModulesTest {
    /**
     * The game ticks a pet lives through: every tick to 119, past every stuck threshold (follow_owner's 60 is the
     * longest), every bob, re-check and ring step, and the 100-tick retries; then jumps of 40 to 480, past the 120-tick
     * timeouts of rain_bloom, wander and swim, drift's 160, and every rest.
     */
    private static final long[] TICKS = LongStream.concat(LongStream.range(0, 120),
            LongStream.rangeClosed(4, 12).map(step -> step * 40)).toArray();
    /** When something else takes the body once, as a higher priority behavior would. */
    private static final long PREEMPT_AT = 80;
    // The pet file defaults (PetSpecies), but for speed and hover height: values other than 1 and trail_back's 1.5, so
    // the recorded calls show each cruise multiplied by the species speed and trail_point's arguments in order.
    private static final double FOLLOW = 5, LEASH = 12, TELEPORT = 24, SPEED = 1.5, HOVER_HEIGHT = 2;
    /**
     * Every distinct call the shipped modules make at their default settings (L is a location), with the arguments
     * today's Java behaviors used: each cruise is the Java constant times SPEED (drift, campfire and a far perch 0.06;
     * a near perch, stay and hover 0.03; follow 0.25, x1.6 past the leash; rain bloom 0.06 x1.4), except the flock's
     * own route cruise. The glow is light 15 on the lantern bone's glow animation at night and none by day; a sting is
     * slowness for 60 ticks at amplifier 0, no damage, a 60-tick cooldown and mobs within 0.5 blocks, players only for
     * a wild pet; a drizzle looks 4 blocks down at chance 0.1. owner_effect is new: night vision for 300 ticks within 16
     * blocks. So are the walking and swimming modules: a walk and a swim at 0.15 to a spot within the roam
     * radius (an owned pet's within 0.8 of the follow distance), the swim 0.5 to 3 blocks under the surface, and a look
     * at the nearest player within 8 blocks. stay bobs as before, now in the body's own medium.
     */
    private static final Set<String> DEFAULT_CALLS = Set.of(
            "fmm:pet/migrate route_target()", "fmm:pet/migrate steer_to(L, 0.3)", "fmm:pet/migrate stop()",
            "fmm:pet/stay rest_point(L, 0.5)", "fmm:pet/stay steer_to(L, 0.045)", "fmm:pet/stay stop()",
            "fmm:pet/follow_owner trail_point(1.5, 2)", "fmm:pet/follow_owner distance_to(L)",
            "fmm:pet/follow_owner teleport_near_owner(2)",
            "fmm:pet/follow_owner steer_to(L, 0.375)", "fmm:pet/follow_owner steer_to(L, 0.6)",
            "fmm:pet/drift hover_point(L, 5)", "fmm:pet/drift distance_to(L)", "fmm:pet/drift steer_to(L, 0.09)",
            "fmm:pet/hover ground_below(L, 12)", "fmm:pet/hover steer_to(L, 0.045)",
            "fmm:pet/campfire nearest_campfire(12, 100, {CAMPFIRE, SOUL_CAMPFIRE}, {}, 2.5)",
            "fmm:pet/campfire campfire_usable(L, {CAMPFIRE, SOUL_CAMPFIRE}, {}, 2.5)",
            "fmm:pet/campfire distance_to(L)", "fmm:pet/campfire steer_to(L, 0.09)",
            "fmm:pet/day_perch find_perch(0.3, 2)", "fmm:pet/day_perch distance_to(L)",
            "fmm:pet/day_perch steer_to(L, 0.045)", "fmm:pet/day_perch steer_to(L, 0.09)",
            "fmm:pet/rain_bloom scale_toward(1, 1.25, 60, 4)", "fmm:pet/rain_bloom scale_toward(1.25, 1.25, 60, 4)",
            "fmm:pet/rain_bloom hover_point(L, 5)", "fmm:pet/rain_bloom distance_to(L)",
            "fmm:pet/rain_bloom steer_to(L, 0.126)",
            "fmm:pet/night_glow set_glow(15, lantern, glow)", "fmm:pet/night_glow clear_glow()",
            "fmm:pet/sting sting_contacts(true, SLOWNESS, 60, 0, 0, 60)",
            "fmm:pet/sting sting_enemies(0.5, SLOWNESS, 60, 0, 0, 60)",
            "fmm:pet/crop_drizzle drizzle(4, 0.1)",
            "fmm:pet/owner_effect owner_effect(NIGHT_VISION, 300, 0, 16)",
            "fmm:pet/wander ground_point(L, 5)", "fmm:pet/wander ground_point(L, 4)", "fmm:pet/wander distance_to(L)",
            "fmm:pet/wander steer_to(L, 0.225)", "fmm:pet/wander stop()",
            "fmm:pet/idle_look c.perception:nearest_player(8)", "fmm:pet/idle_look c.actuator:look_at(L)",
            "fmm:pet/swim water_point(L, 5, 0.5, 3)", "fmm:pet/swim water_point(L, 4, 0.5, 3)",
            "fmm:pet/swim distance_to(L)", "fmm:pet/swim steer_to(L, 0.225)", "fmm:pet/swim stop()");
    /**
     * wander's and swim's calls by where the owner is: a wild pet's trips end within the roam radius of home, an owned
     * one's within 0.8 of the follow distance of its owner (4 of 5 here), so follow_owner does not take over as soon as
     * it arrives. With the owner away there is no anchor, and no trip.
     */
    private static final Map<Owner, Set<String>> WANDER_CALLS = trips("ground_point(L, 5)", "ground_point(L, 4)"),
            SWIM_CALLS = trips("water_point(L, 5, 0.5, 3)", "water_point(L, 4, 0.5, 3)");
    /**
     * follow_owner's calls by where the owner is: the follow speed past the follow distance, times leash_urgency past
     * the leash, only the catch-up teleport past the teleport distance, and nothing while the owner is near, away or
     * wild. Steering that is refused, or accepted but gets the pet nowhere, also teleports, after stuck_ticks. Each
     * follow also checks how far the pet is from its trail point, to end on arrival.
     */
    private static final Map<Owner, Set<String>> FOLLOW_CALLS = Map.of(
            Owner.WILD, Set.of(), Owner.WITHIN_FOLLOW, Set.of(), Owner.AWAY, Set.of(),
            Owner.PAST_FOLLOW, Set.of("trail_point(1.5, 2)", "distance_to(L)", "steer_to(L, 0.375)",
                    "teleport_near_owner(2)"),
            Owner.PAST_LEASH, Set.of("trail_point(1.5, 2)", "distance_to(L)", "steer_to(L, 0.6)",
                    "teleport_near_owner(2)"),
            Owner.PAST_TELEPORT, Set.of("trail_point(1.5, 2)", "distance_to(L)", "teleport_near_owner(2)"));
    /**
     * sting's calls by where the owner is: a wild pet takes the touches on its model and stings those players; an
     * owned one never takes touches, and stings hostile mobs only.
     */
    private static final Map<Owner, Set<String>> STING_CALLS = Map.of(
            Owner.WILD, Set.of("sting_contacts(true, SLOWNESS, 60, 0, 0, 60)"),
            Owner.WITHIN_FOLLOW, Set.of("sting_enemies(0.5, SLOWNESS, 60, 0, 0, 60)"),
            Owner.PAST_FOLLOW, Set.of("sting_enemies(0.5, SLOWNESS, 60, 0, 0, 60)"),
            Owner.PAST_LEASH, Set.of("sting_enemies(0.5, SLOWNESS, 60, 0, 0, 60)"),
            Owner.PAST_TELEPORT, Set.of("sting_enemies(0.5, SLOWNESS, 60, 0, 0, 60)"),
            Owner.AWAY, Set.of("sting_enemies(0.5, SLOWNESS, 60, 0, 0, 60)"));
    /**
     * The controls each leased c.pet method needs, as PetLuaTable documents them. A behavior holds its declared
     * controls from just before its start until its stop returns, so start, can_continue, tick and stop hold them, and
     * can_start and sensors hold none.
     */
    private static final Map<String, Set<MindControl>> LEASED = Map.of(
            "steer_to", EnumSet.of(MindControl.MOVE, MindControl.LOOK), "stop", EnumSet.of(MindControl.MOVE));
    /**
     * The world of the pet's body and the stand-in player, named world. MagmaCore looks up the world a location table
     * names through Bukkit, so the class installs a stand-in server that answers it.
     */
    private static final World WORLD = stub(World.class, (proxy, method, args) ->
            method.getName().equals("getName") ? "world" : unknown("the world", proxy, method, args));
    /**
     * The player c.perception:nearest_player finds, 3 blocks from the pet. It answers what MagmaCore reads to build its
     * entity table, and where it stands and looks from; anything else a module asks of it fails.
     */
    private static final Player PLAYER = stub(Player.class, (proxy, method, args) -> switch (method.getName()) {
        case "getUniqueId" -> new UUID(0L, 1L);
        case "getType" -> EntityType.PLAYER;
        case "getGameMode" -> GameMode.SURVIVAL;
        case "getLocation" -> new Location(WORLD, 3.5, 70, 0.5);
        case "getEyeLocation" -> new Location(WORLD, 3.5, 71.62, 0.5);
        default -> unknown("the player", proxy, method, args);
    });
    /** The Bukkit server another class left installed, if any, put back when this class is done. */
    private static Server previousServer;
    private static final Pattern PET_NAME = Pattern.compile("c\\.pet[.:]([a-z_]+)");
    private static final Pattern SPECIES_NAME = Pattern.compile("species\\.([a-z_]+)");

    @TempDir
    Path folder;
    private final List<String> warnings = new ArrayList<>();

    /**
     * Installs this class's own stand-in Bukkit server, for the world a location table names, whatever an earlier
     * class left installed, then warms up. A cold JVM links MagmaCore's Lua tables on their first use, which can take
     * longer than the 20 ms CPU limit of the callback that first uses them. So this builds the player table
     * nearest_player returns, outside any limit, and lives one pet's life through every shipped module before any
     * checked run.
     */
    @BeforeAll
    static void setUp(@TempDir Path folder) throws ReflectiveOperationException {
        Field server = serverField();
        previousServer = (Server) server.get(null);
        server.set(null, stub(Server.class, (proxy, method, args) -> switch (method.getName()) {
            case "getWorld" -> "world".equals(args[0]) ? WORLD : null;
            case "getLogger" -> Logger.getLogger("FMM-shipped-pet-modules-test");
            case "getBukkitVersion" -> "26.3-R0.1-SNAPSHOT";
            default -> plain(proxy, method, args);
        }));
        LuaLivingEntityTable.build(PLAYER);
        warmUp(folder);
    }

    /** Puts back the server that was installed before this class, or none. */
    @AfterAll
    static void tearDown() throws ReflectiveOperationException {
        serverField().set(null, previousServer);
        previousServer = null;
    }

    /** Bukkit's server singleton, which Bukkit.setServer will not replace once set. */
    private static Field serverField() throws NoSuchFieldException {
        Field server = Bukkit.class.getDeclaredField("server");
        server.setAccessible(true);
        return server;
    }

    private PetBehaviorCatalog catalog() {
        return load(folder, warnings::add);
    }

    private static PetBehaviorCatalog load(Path folder, Consumer<String> warn) {
        return PetBehaviorCatalog.load(folder, PetBehaviorCatalog.DEFAULTS, name -> {
            try (InputStream in = ShippedPetModulesTest.class.getResourceAsStream("/pet_behaviors/" + name + ".lua")) {
                if (in == null) throw new IllegalStateException("No resource for the default " + name);
                Files.copy(in, folder.resolve(name + ".lua"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }, warn);
    }

    @Test
    void everyShippedModuleLoads() {
        try (PetBehaviorCatalog catalog = catalog()) {
            assertEquals(Set.copyOf(PetBehaviorCatalog.DEFAULTS), catalog.names());
        }
        assertEquals(List.of(), warnings);
    }

    /**
     * How often the runtime asks each shipped behavior, while stopped, whether it can start: every 4 ticks for an
     * owner's commands and walk-offs, 5 for hover's bob between drifts, 10 for the rest. Pinned, so a typo cannot
     * make stay or follow_owner wait seconds, or put a behavior back to asking every tick.
     */
    private static final Map<String, Integer> PROBE_INTERVALS = Map.ofEntries(
            Map.entry("fmm:pet/stay", 4), Map.entry("fmm:pet/follow_owner", 4), Map.entry("fmm:pet/hover", 5),
            Map.entry("fmm:pet/migrate", 10), Map.entry("fmm:pet/campfire", 10), Map.entry("fmm:pet/day_perch", 10),
            Map.entry("fmm:pet/rain_bloom", 10), Map.entry("fmm:pet/drift", 10), Map.entry("fmm:pet/wander", 10),
            Map.entry("fmm:pet/swim", 10), Map.entry("fmm:pet/idle_look", 10));

    @Test
    void everyShippedBehaviorIsAskedToStartAtItsPinnedInterval() {
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            List<PetBehaviorEntry> all = PetBehaviorCatalog.DEFAULTS.stream()
                    .map(name -> new PetBehaviorEntry(name, Map.of())).toList();
            MindProgram everything = programs.program("test", "everything", all, "everything").definition().instantiate();
            Map<String, Integer> intervals = everything.behaviors().stream()
                    .collect(Collectors.toMap(MindBehavior::identifier, MindBehavior::probeInterval));
            assertEquals(new TreeMap<>(PROBE_INTERVALS), new TreeMap<>(intervals));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void everyDefaultListRunsEveryPath() throws IOException {
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            for (MindBodyLocomotion locomotion : MindBodyLocomotion.values()) {
                Set<String> missing = PetPrograms.defaults(locomotion).stream().map(PetBehaviorEntry::module)
                        .filter(name -> !PetBehaviorCatalog.DEFAULTS.contains(name)).collect(Collectors.toSet());
                assertEquals(Set.of(), missing, locomotion + "'s default list names modules that do not ship");
                // Each module's every path runs in the full list below; this shows each list builds and runs, a
                // stationary body's always failing steering included.
                exercise(programs.program("test", locomotion.name(), PetPrograms.defaults(locomotion), locomotion.name()),
                        EnumSet.allOf(Answers.class));
            }

            PetPrograms.Program everything = programs.program("test", "everything", PetBehaviorCatalog.DEFAULTS.stream()
                    .map(name -> new PetBehaviorEntry(name, Map.of())).toList(), "everything");
            Coverage coverage = exercise(everything, EnumSet.allOf(Answers.class));
            MindProgram instance = everything.definition().instantiate();
            Set<String> behaviors = instance.behaviors().stream().map(MindBehavior::identifier).collect(Collectors.toCollection(TreeSet::new));
            assertEquals(behaviors, coverage.started, "every behavior starts");
            assertEquals(behaviors, coverage.ticked, "every behavior ticks");
            assertEquals(behaviors, coverage.stopped, "every behavior stops");
            assertEquals(instance.sensors().stream().map(MindSensor::identifier).collect(Collectors.toCollection(TreeSet::new)),
                    coverage.sensed, "every sensor senses");
            Set<String> unread = new TreeSet<>(sourceNames());
            unread.removeAll(coverage.names);
            assertEquals(Set.of(), unread, "every c.pet name a module's source uses is read on some path");
            assertEquals(new TreeSet<>(DEFAULT_CALLS), coverage.calls, "the shipped defaults pass the Java behaviors' arguments");
            assertCallsByOwner(FOLLOW_CALLS, coverage, "fmm:pet/follow_owner",
                    "follow_owner's speed and teleport by where the owner is");
            // Short of the teleport distance, a pet that gets somewhere never jumps; one whose steering is refused, or
            // accepted but leaves it where it was, catches up after stuck_ticks.
            for (Owner owner : List.of(Owner.PAST_FOLLOW, Owner.PAST_LEASH)) {
                assertFalse(coverage.calls(owner, Answers.SOMETHING, "fmm:pet/follow_owner")
                        .contains("teleport_near_owner(2)"), owner + ": a moving pet does not catch up");
                for (Answers stuck : List.of(Answers.STEER_FAILS, Answers.STUCK))
                    assertTrue(coverage.calls(owner, stuck, "fmm:pet/follow_owner").contains("teleport_near_owner(2)"),
                            owner + ", " + stuck + ": a pet that gets nowhere catches up");
            }
            assertCallsByOwner(STING_CALLS, coverage, "fmm:pet/sting", "sting's victims by whether the pet is wild");
            assertCallsByOwner(WANDER_CALLS, coverage, "fmm:pet/wander", "wander's spots by whether the pet is wild");
            assertCallsByOwner(SWIM_CALLS, coverage, "fmm:pet/swim", "swim's spots by whether the pet is wild");
            // idle_look finds the player again every 5 ticks while it looks, and with nobody near searches every 20.
            assertEquals(20L, coverage.closestSearches.get(Answers.NOTHING), "idle_look's searches with nobody near");
            assertEquals(5L, coverage.closestSearches.values().stream().min(Long::compare).orElseThrow(),
                    "idle_look's closest searches");
            // A trip that gets nowhere gives up after stuck_ticks (20), long before its 120-tick timeout; a moving one
            // runs on.
            for (String module : List.of("fmm:pet/wander", "fmm:pet/swim")) {
                Map<Answers, Integer> trips = coverage.longestTrips.getOrDefault(module, Map.of());
                for (Answers stuck : List.of(Answers.STEER_FAILS, Answers.STUCK))
                    assertEquals(20, trips.get(stuck), module + ", " + stuck + ": steer_to calls in the longest trip");
                assertTrue(trips.getOrDefault(Answers.SOMETHING, 0) > 20, module + ": a moving trip runs past 20");
            }
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void extremeSettingsNeverFailACallback() {
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            for (double value : new double[]{0, -5, 0.5, 1e12})
                sweep(catalog, programs, "numbers " + value, fallback -> fallback instanceof Double ? value : null);
            // Every switch the other way round from its default.
            sweep(catalog, programs, "switches flipped", fallback -> fallback instanceof Boolean flag ? !flag : null);
            // Text that names nothing, as an effect of NONE or an empty one.
            for (String text : new String[]{"NONE", ""})
                sweep(catalog, programs, "text '" + text + "'", fallback -> fallback instanceof String ? text : null);
        }
        assertEquals(List.of(), warnings);
    }

    /**
     * Runs every shipped module with each setting given the value {@code change} makes of its default (null keeps the
     * default), once it has checked that each value reached its module's settings and that some setting changed.
     */
    private static void sweep(PetBehaviorCatalog catalog, PetPrograms programs, String label,
                              Function<Object, Object> change) {
        List<PetBehaviorEntry> entries = new ArrayList<>();
        Map<String, Map<String, Object>> changed = new LinkedHashMap<>();
        for (String name : PetBehaviorCatalog.DEFAULTS) {
            Map<String, Object> settings = new LinkedHashMap<>();
            catalog.module(name).orElseThrow().settings().forEach((setting, fallback) -> {
                Object value = change.apply(fallback);
                if (value != null) settings.put(setting, value);
            });
            changed.put(PetBehaviorCatalog.ID_PREFIX + name, settings);
            entries.add(new PetBehaviorEntry(name, settings));
        }
        assertNotEquals(Set.of(), changed.values().stream().flatMap(settings -> settings.keySet().stream())
                .collect(Collectors.toSet()), label + " changes some setting");
        PetPrograms.Program program = programs.program("test", label, entries, label);
        changed.forEach((module, settings) -> settings.forEach((setting, value) ->
                assertEquals(value, program.settings().get(module).get(setting), label + ": " + module + "." + setting)));
        // Flickering answers reach the nothing paths and failed steering too, at half the cost of every mode.
        exercise(program, EnumSet.of(Answers.SOMETHING, Answers.FLICKER));
    }

    /** A trip module's calls by where the owner is, from how it searches for a wild pet's spot and an owned one's. */
    private static Map<Owner, Set<String>> trips(String wild, String owned) {
        Map<Owner, Set<String>> calls = new EnumMap<>(Owner.class);
        for (Owner owner : Owner.values())
            calls.put(owner, owner == Owner.AWAY ? Set.of() : Set.of(owner == Owner.WILD ? wild : owned,
                    "distance_to(L)", "steer_to(L, 0.225)", "stop()"));
        return calls;
    }

    /** A module's calls by where the owner is, against the expected ones. */
    private static void assertCallsByOwner(Map<Owner, Set<String>> calls, Coverage coverage, String module,
                                           String message) {
        Map<Owner, Set<String>> expected = new EnumMap<>(Owner.class), actual = new EnumMap<>(Owner.class);
        for (Owner owner : Owner.values()) {
            expected.put(owner, new TreeSet<>(calls.get(owner)));
            actual.put(owner, coverage.calls(owner, module));
        }
        assertEquals(expected, actual, message);
    }

    /** Every c.pet name, and every c.pet.species field, the shipped modules' code (not their comments) uses. */
    private Set<String> sourceNames() throws IOException {
        Set<String> names = new TreeSet<>();
        for (String name : PetBehaviorCatalog.DEFAULTS) {
            String code;
            try (InputStream in = getClass().getResourceAsStream("/pet_behaviors/" + name + ".lua")) {
                code = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("--[^\\n]*", "");
            }
            for (Matcher matcher = PET_NAME.matcher(code); matcher.find(); ) names.add(matcher.group(1));
            for (Matcher matcher = SPECIES_NAME.matcher(code); matcher.find(); ) names.add("species." + matcher.group(1));
        }
        return names;
    }

    /** Where the owner is, as c.pet reports it, against the pet file's default follow, leash and teleport distances. */
    private enum Owner {
        WILD(Double.POSITIVE_INFINITY),
        WITHIN_FOLLOW(3),
        PAST_FOLLOW(8),
        PAST_LEASH(18),
        PAST_TELEPORT(30),
        /** Owned, but the owner is offline or in another world. */
        AWAY(Double.POSITIVE_INFINITY);

        final double distance;

        Owner(double distance) {
            this.distance = distance;
        }

        boolean present() {
            return this != WILD && this != AWAY;
        }
    }

    /** How the stand-in's methods answer. */
    private enum Answers {
        /** Every method finds something, and the pet moves. */
        SOMETHING,
        /** Every method answers the nothing it documents, and the pet stays put. */
        NOTHING,
        /** Only steering fails, as for a walking body with no path, and the pet stays put. */
        STEER_FAILS,
        /**
         * Every method finds something and steering is accepted, but the pet stays put, as a walker whose path ends at
         * a fence or a flyer held back by a wall.
         */
        STUCK,
        /**
         * Each method answers nothing in some 10-tick windows and something in others, distances swing between near
         * and far, and the pet stays put.
         */
        FLICKER
    }

    /**
     * What the runs reached: the c.pet names read, each distinct call with its module and arguments, overall and by
     * where the owner was and how the methods answered, and the behaviors and sensors whose callbacks ran. Also, by
     * how the methods answered, the fewest ticks between two nearest_player calls in one pet's life, and by module the
     * most steer_to calls in one trip (from its first steer_to to its stop()).
     */
    private static final class Coverage {
        final Set<String> names = new TreeSet<>(), calls = new TreeSet<>();
        final Map<Owner, Map<Answers, Set<String>>> callsByState = new EnumMap<>(Owner.class);
        final Set<String> started = new TreeSet<>(), ticked = new TreeSet<>(), stopped = new TreeSet<>(),
                sensed = new TreeSet<>();
        final Map<Answers, Long> closestSearches = new EnumMap<>(Answers.class);
        final Map<String, Map<Answers, Integer>> longestTrips = new TreeMap<>();

        void search(Answers answers, long gap) {
            closestSearches.merge(answers, gap, Math::min);
        }

        void trip(String module, Answers answers, int steers) {
            longestTrips.computeIfAbsent(module, key -> new EnumMap<>(Answers.class)).merge(answers, steers, Math::max);
        }

        void call(Owner owner, Answers answers, String call) {
            calls.add(call);
            callsByState.computeIfAbsent(owner, key -> new EnumMap<>(Answers.class))
                    .computeIfAbsent(answers, key -> new TreeSet<>()).add(call);
        }

        /** The calls a module made while the owner was there, however the methods answered, without its name. */
        Set<String> calls(Owner owner, String module) {
            Set<String> calls = new TreeSet<>();
            for (Answers answers : Answers.values()) calls.addAll(calls(owner, answers, module));
            return calls;
        }

        /** The calls a module made while the owner was there and the methods answered so, without its name. */
        Set<String> calls(Owner owner, Answers answers, String module) {
            return callsByState.getOrDefault(owner, Map.of()).getOrDefault(answers, Set.of()).stream()
                    .filter(call -> call.startsWith(module + " ")).map(call -> call.substring(module.length() + 1))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    /** Runs the program through every pet state crossed with each of the given ways the methods answer. */
    private static Coverage exercise(PetPrograms.Program program, Set<Answers> modes) {
        Coverage coverage = new Coverage();
        for (Owner owner : Owner.values())
            for (boolean staying : new boolean[]{false, true})
                for (boolean night : new boolean[]{false, true})
                    for (Answers answers : modes) {
                        StubPet pet = new StubPet(owner, staying, night, answers, coverage);
                        try {
                            run(program, pet, coverage);
                        } catch (RuntimeException | AssertionError failure) {
                            throw new AssertionError(pet + ", tick " + pet.tick + ": " + failure.getMessage(), failure);
                        }
                    }
        return coverage;
    }

    /**
     * Lives one pet's life through every shipped module, with every method finding something. MagmaCore's context
     * tables still link inside the first callbacks' limits, so a time limit stopping it is tried again; the instruction
     * limit counts the same however warm the JVM is, and fails at once, as does anything else.
     */
    private static void warmUp(Path folder) {
        try (PetBehaviorCatalog catalog = load(folder, warning -> {
        })) {
            PetPrograms.Program everything = new PetPrograms(catalog, warning -> {
            }).program("warm-up", "everything", PetBehaviorCatalog.DEFAULTS.stream()
                    .map(name -> new PetBehaviorEntry(name, Map.of())).toList(), "warm-up");
            for (int attempt = 1; ; attempt++) {
                Coverage ignored = new Coverage();
                StubPet pet = new StubPet(Owner.PAST_FOLLOW, false, true, Answers.SOMETHING, ignored);
                try {
                    run(everything, pet, ignored);
                    return;
                } catch (MindRunawayException runaway) {
                    String why = String.valueOf(runaway.getMessage());
                    // Raised inside LuaJ's instruction hook, the message gains a source:line prefix and a traceback.
                    boolean timed = why.contains("Lua CPU-time budget exceeded") || why.contains("Lua elapsed-time");
                    if (timed && attempt < 8) continue;
                    throw new AssertionError("Warming up, " + pet + ", tick " + pet.tick + ": " + why, runaway);
                } catch (RuntimeException | AssertionError failure) {
                    throw new AssertionError("Warming up, " + pet + ", tick " + pet.tick + ": " + failure.getMessage(),
                            failure);
                }
            }
        }
    }

    /**
     * One pet's life as the Mind runtime leads it: a behavior that can start starts and, the same tick, ticks while it
     * can continue; a sensor senses on its interval. Something else takes the body once, and the pet goes at the end.
     * Each callback holds the controls the runtime would lease it: its behavior's from start to stop, none in can_start
     * or a sensor. It does not arbitrate between behaviors: every behavior that can start runs, beside any other
     * holding the same controls, so priorities and the pre-emption they cause are not exercised here.
     */
    private static void run(PetPrograms.Program program, StubPet pet, Coverage coverage) {
        Map<String, LuaTable> settings = new HashMap<>();
        program.settings().forEach((module, values) -> settings.put(module, strictSettings(values)));
        LuaTable none = strictSettings(Map.of());
        MindProgram instance = program.definition().instantiate((table, context, contribution) -> {
            pet.module = contribution;
            table.set("pet", pet.table);
            table.set("settings", settings.getOrDefault(contribution, none));
        });
        Map<Object, Object> memory = new HashMap<>();
        List<MindBehavior> behaviors = instance.behaviors();
        boolean[] running = new boolean[behaviors.size()];
        for (long tick : TICKS) {
            pet.tick = tick;
            MindContext context = context(memory, tick, pet);
            pet.leases = null;
            for (MindSensor sensor : instance.sensors()) {
                if (tick % sensor.intervalTicks() != 0) continue;
                sensor.sense(context);
                coverage.sensed.add(sensor.identifier());
            }
            for (int index = 0; index < behaviors.size(); index++) {
                MindBehavior behavior = behaviors.get(index);
                pet.leases = null;
                if (!running[index] && behavior.canStart(context)) {
                    pet.leases = behavior.controls();
                    behavior.start(context);
                    running[index] = true;
                    coverage.started.add(behavior.identifier());
                }
                if (!running[index]) continue;
                pet.leases = behavior.controls();
                if (behavior.canContinue(context)) {
                    behavior.tick(context);
                    coverage.ticked.add(behavior.identifier());
                } else {
                    stop(behavior, pet, context, MindStopReason.COMPLETED, coverage);
                    running[index] = false;
                }
            }
            if (tick == PREEMPT_AT) stopAll(behaviors, running, pet, context, MindStopReason.PREEMPTED, coverage);
        }
        pet.tick = TICKS[TICKS.length - 1] + 1;
        stopAll(behaviors, running, pet, context(memory, pet.tick, pet), MindStopReason.ENTITY_REMOVED, coverage);
    }

    private static void stopAll(List<MindBehavior> behaviors, boolean[] running, StubPet pet, MindContext context,
                                MindStopReason reason, Coverage coverage) {
        for (int index = 0; index < behaviors.size(); index++) {
            if (!running[index]) continue;
            stop(behaviors.get(index), pet, context, reason, coverage);
            running[index] = false;
        }
    }

    /** The runtime stops a behavior before it releases the behavior's controls, so stop still holds them. */
    private static void stop(MindBehavior behavior, StubPet pet, MindContext context, MindStopReason reason,
                             Coverage coverage) {
        pet.leases = behavior.controls();
        behavior.stop(context, reason);
        coverage.stopped.add(behavior.identifier());
    }

    /** A module's c.settings, raising on a setting it does not have, so a misspelt one fails instead of reading nil. */
    private static LuaTable strictSettings(Map<String, Object> values) {
        LuaTable backing = PetBehaviorSettings.toLua(values);
        return strict("c.settings", name -> {
            LuaValue value = backing.rawget(name);
            return value.isnil() ? null : value;
        }, backing::rawset);
    }

    /**
     * A table that answers {@code read} for each name and raises on a name it does not know (null). Writes raise, or
     * go to {@code write} when there is one.
     */
    private static LuaTable strict(String label, Function<String, LuaValue> read, BiConsumer<LuaValue, LuaValue> write) {
        LuaTable view = new LuaTable();
        LuaTable metatable = new LuaTable();
        metatable.rawset(LuaValue.INDEX, new TwoArgFunction() {
            @Override
            public LuaValue call(LuaValue table, LuaValue key) {
                LuaValue value = key.type() == LuaValue.TSTRING ? read.apply(key.tojstring()) : null;
                if (value == null) throw new LuaError(label + " has no " + key.tojstring());
                return value;
            }
        });
        metatable.rawset(LuaValue.NEWINDEX, new ThreeArgFunction() {
            @Override
            public LuaValue call(LuaValue table, LuaValue key, LuaValue value) {
                if (write == null) throw new LuaError(label + "." + key.tojstring() + " cannot be set");
                write.accept(key, value);
                return NONE;
            }
        });
        view.setmetatable(metatable);
        return view;
    }

    /** A fresh location table each time, as c.pet hands out. */
    private static LuaTable place(double x, double y, double z) {
        LuaTable table = new LuaTable();
        table.set("world", "world");
        table.set("x", x);
        table.set("y", y);
        table.set("z", z);
        table.set("yaw", 0);
        table.set("pitch", 0);
        return table;
    }

    /**
     * A strict c.pet with exactly PetLuaTable's names and the pet file's default species values. It raises on any other
     * name, checks each method's arguments against the order and kinds PetLuaTable documents and each leased method's
     * controls against the running callback's, and records every name read. Properties are read fresh and locations
     * are fresh tables, as from the real one. It also stands in for the pet's body, and for the c.perception and
     * c.actuator calls the shipped modules make, checked as strictly.
     */
    private static final class StubPet {
        private record Method(String shape, Function<Varargs, LuaValue> something, LuaValue nothing) {
        }

        final Owner owner;
        final boolean staying, night;
        final Answers answers;
        final Coverage coverage;
        final Set<String> read;
        long tick;
        /** The module whose callback runs, as the context extension names it. */
        String module = "";
        /** The controls the running callback holds; null in can_start and sensors, which hold none. */
        Set<MindControl> leases;
        LuaTable table;
        final LivingEntity body;
        final MindPerception perception;
        final MindActuator actuator;
        /** The tick of the last nearest_player call, or -1 before the first. */
        private long lastSearch = -1;
        /** The steer_to calls of each module's trip in progress. */
        private final Map<String, Integer> tripSteers = new HashMap<>();

        StubPet(Owner owner, boolean staying, boolean night, Answers answers, Coverage coverage) {
            this.owner = owner;
            this.staying = staying;
            this.night = night;
            this.answers = answers;
            this.coverage = coverage;
            this.read = coverage.names;
            body = stub(LivingEntity.class, (proxy, method, args) ->
                    method.getName().equals("getWorld") ? WORLD : plain(proxy, method, args));
            // Only nearest_player, which refuses a range that is not finite and positive, as the native one does.
            perception = stub(MindPerception.class, (proxy, method, args) -> {
                if (!method.getName().equals("nearestPlayer")) return unknown("c.perception", proxy, method, args);
                double range = (double) args[0];
                if (!Double.isFinite(range) || range <= 0)
                    throw new IllegalArgumentException("maxDistance must be finite and positive, got " + range);
                coverage.call(owner, answers,
                        module + " c.perception:nearest_player(" + shown(LuaValue.valueOf(range)) + ")");
                if (lastSearch >= 0) coverage.search(answers, tick - lastSearch);
                lastSearch = tick;
                return answersNothing("nearest_player") ? Optional.empty() : Optional.of(PLAYER);
            });
            // Only look_at, which needs the LOOK lease and a finite point in the body's world, as the native one does.
            actuator = stub(MindActuator.class, (proxy, method, args) -> {
                if (!method.getName().equals("lookAt")) return unknown("c.actuator", proxy, method, args);
                requireLeases("c.actuator:look_at", EnumSet.of(MindControl.LOOK));
                Location at = (Location) args[0];
                if (at.getWorld() != WORLD || !Double.isFinite(at.getX()) || !Double.isFinite(at.getY())
                        || !Double.isFinite(at.getZ()))
                    throw new LuaError(module + " looked at " + at + ", not a finite point in the body's world");
                coverage.call(owner, answers, module + " c.actuator:look_at(L)");
                return null;
            });
            Map<String, LuaValue> species = Map.of("id", LuaValue.valueOf("test"), "speed", LuaValue.valueOf(SPEED),
                    "hover_height", LuaValue.valueOf(HOVER_HEIGHT), "hover_variance", LuaValue.valueOf(0.75),
                    "roam_radius", LuaValue.valueOf(5), "follow_distance", LuaValue.valueOf(FOLLOW),
                    "leash_distance", LuaValue.valueOf(LEASH), "teleport_distance", LuaValue.valueOf(TELEPORT),
                    "scale", LuaValue.valueOf(1), "baby_scale", LuaValue.valueOf(0.5));
            assertEquals(PetLuaTable.SPECIES_FIELDS, species.keySet(), "the stand-in's species fields match PetLuaTable's");
            LuaTable speciesTable = strict("c.pet.species", name -> {
                LuaValue value = species.get(name);
                if (value != null) read.add("species." + name);
                return value;
            }, null);

            Map<String, Supplier<LuaValue>> properties = new HashMap<>();
            properties.put("wild", () -> LuaValue.valueOf(owner == Owner.WILD));
            properties.put("staying", () -> LuaValue.valueOf(staying));
            properties.put("settled", () -> LuaValue.valueOf(!staying && (owner == Owner.WILD || owner.distance <= FOLLOW)));
            properties.put("baby", () -> LuaValue.FALSE);
            properties.put("migrating", () -> LuaValue.valueOf(owner == Owner.WILD));
            properties.put("model", () -> LuaValue.valueOf("em_pet_jellyfish"));
            properties.put("species", () -> speciesTable);
            properties.put("position", () -> place(x(), 70, 0.5));
            properties.put("home", () -> place(0.5, 70, 0.5));
            properties.put("anchor", () -> owner == Owner.WILD ? place(0.5, 70, 0.5)
                    : owner.present() ? ownerLocation() : LuaValue.NIL);
            properties.put("stay_anchor", () -> staying ? place(0.5, 70, 0.5) : LuaValue.NIL);
            properties.put("owner_location", () -> owner.present() ? ownerLocation() : LuaValue.NIL);
            properties.put("owner_distance", () -> LuaValue.valueOf(owner.distance));
            properties.put("night", () -> LuaValue.valueOf(night));
            properties.put("day", () -> LuaValue.valueOf(!night));
            properties.put("raining", () -> LuaValue.valueOf(night));
            properties.put("speed", () -> LuaValue.valueOf(answers == Answers.SOMETHING ? 0.05 : 0));
            properties.put("route_cruise", () -> LuaValue.valueOf(owner == Owner.WILD ? 0.3 : 0));
            assertEquals(PetLuaTable.PROPERTIES, properties.keySet(), "the stand-in's properties match PetLuaTable's");

            // Shapes: L a location, N a finite number, C a cruise (a number that may be infinite), T a list of text,
            // S text, s optional text, B true or false.
            Map<String, Method> methods = new HashMap<>();
            methods.put("steer_to", new Method("LC", args -> LuaValue.TRUE, LuaValue.FALSE));
            methods.put("stop", new Method("", args -> LuaValue.NIL, LuaValue.NIL));
            methods.put("hover_point", new Method("LN", args -> shifted(args.arg1(), 1, 0), LuaValue.NIL));
            methods.put("rest_point", new Method("LN", args -> shifted(args.arg1(), 1, 0), LuaValue.NIL));
            methods.put("ground_below", new Method("LN",
                    args -> LuaValue.valueOf(args.arg1().get("y").todouble() - (tick / 30 % 2 == 0 ? 2 : 10)), LuaValue.NIL));
            methods.put("ground_point", new Method("LN", args -> shifted(args.arg1(), 1, 0), LuaValue.NIL));
            methods.put("water_point", new Method("LNNN", args -> shifted(args.arg1(), 0, -1), LuaValue.NIL));
            methods.put("trail_point", new Method("NN", args -> owner.present()
                    ? place(0.5 + owner.distance - args.arg1().todouble(), 70 + args.arg(2).todouble(), 0.5) : LuaValue.NIL,
                    LuaValue.NIL));
            methods.put("teleport_near_owner", new Method("N", args -> LuaValue.valueOf(owner.present()), LuaValue.FALSE));
            methods.put("distance_to", new Method("L", args -> LuaValue.valueOf(distance(args.arg1())),
                    LuaValue.valueOf(Double.POSITIVE_INFINITY)));
            methods.put("route_target", new Method("", args -> owner == Owner.WILD ? place(100, 80, 100) : LuaValue.NIL,
                    LuaValue.NIL));
            methods.put("find_perch", new Method("NN", args -> place(x(), 69.9, 0.5), LuaValue.NIL));
            methods.put("nearest_campfire", new Method("NNTTN", args -> place(5, 68, 5), LuaValue.NIL));
            methods.put("campfire_usable", new Method("LTTN", args -> LuaValue.TRUE, LuaValue.FALSE));
            methods.put("set_glow", new Method("Nss", args -> LuaValue.NIL, LuaValue.NIL));
            methods.put("clear_glow", new Method("", args -> LuaValue.NIL, LuaValue.NIL));
            methods.put("scale_toward", new Method("NNNN", args -> LuaValue.NIL, LuaValue.NIL));
            methods.put("sting_contacts", new Method("BsNNNN", args -> LuaValue.valueOf(1), LuaValue.valueOf(0)));
            methods.put("sting_enemies", new Method("NsNNNN", args -> LuaValue.valueOf(1), LuaValue.valueOf(0)));
            methods.put("drizzle", new Method("NN", args -> LuaValue.TRUE, LuaValue.FALSE));
            methods.put("owner_effect", new Method("SNNN", args -> LuaValue.valueOf(owner.present()), LuaValue.FALSE));
            methods.put("play_animation", new Method("S", args -> LuaValue.TRUE, LuaValue.FALSE));
            assertEquals(PetLuaTable.METHODS, methods.keySet(), "the stand-in's methods match PetLuaTable's");
            Map<String, LuaValue> functions = new HashMap<>();
            methods.forEach((name, method) -> functions.put(name, function(name, method)));

            table = strict("c.pet", name -> {
                Supplier<LuaValue> property = properties.get(name);
                LuaValue value = property != null ? property.get() : functions.get(name);
                if (value != null) read.add(name);
                return value;
            }, null);
        }

        /** Moves along x while every method finds something; otherwise it stays put, as a blocked pet does. */
        private double x() {
            return answers == Answers.SOMETHING ? 0.5 + 0.05 * tick : 0.5;
        }

        private LuaValue ownerLocation() {
            return place(0.5 + owner.distance, 70, 0.5);
        }

        /**
         * Flickering, it is near and far in turn (0.1, 0.4, then 5 blocks, for 7 ticks each), so arrivals, bobs and far
         * cruises all happen; otherwise it is the real distance from where the pet is.
         */
        private double distance(LuaValue point) {
            if (answers == Answers.FLICKER) return new double[]{0.1, 0.4, 5}[(int) (tick / 7 % 3)];
            double dx = point.get("x").todouble() - x(), dy = point.get("y").todouble() - 70, dz = point.get("z").todouble() - 0.5;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private static LuaValue shifted(LuaValue center, double dx, double dy) {
            return place(center.get("x").todouble() + dx, center.get("y").todouble() + dy, center.get("z").todouble());
        }

        private boolean answersNothing(String method) {
            return switch (answers) {
                case SOMETHING, STUCK -> false;
                case NOTHING -> true;
                case STEER_FAILS -> method.equals("steer_to");
                case FLICKER -> Math.floorMod(tick / 10 + method.hashCode(), 3) == 0;
            };
        }

        private LuaValue function(String called, Method method) {
            // Not "name": inside the function that would be luaj's LibFunction.name field.
            return new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    // Called with a colon, the table itself comes first.
                    Varargs own = args.narg() > 0 && args.arg1() == table ? args.subargs(2) : args;
                    check(called, method.shape(), own);
                    requireLeases("c.pet:" + called, LEASED.get(called));
                    coverage.call(owner, answers, module + " " + called + "(" + arguments(method.shape(), own) + ")");
                    if (called.equals("steer_to")) {
                        tripSteers.merge(module, 1, Integer::sum);
                    } else if (called.equals("stop")) {
                        Integer steers = tripSteers.remove(module);
                        if (steers != null) coverage.trip(module, answers, steers);
                    }
                    return answersNothing(called) ? method.nothing() : method.something().apply(own);
                }
            };
        }

        /** Raises, as the real call does, unless the running callback holds every control the call needs. */
        void requireLeases(String call, Set<MindControl> needed) {
            if (needed == null || (leases != null && leases.containsAll(needed))) return;
            throw new LuaError(module + " called " + call + ", which needs " + needed + ", " + (leases == null
                    ? "from can_start or a sensor, which hold no controls" : "from a behavior declaring only " + leases));
        }

        /** The arguments as the recorded call shows them: L for a location, numbers to 9 digits, lists in braces. */
        private static String arguments(String shape, Varargs args) {
            List<String> shown = new ArrayList<>();
            for (int index = 0; index < shape.length(); index++) {
                LuaValue value = args.arg(index + 1);
                shown.add(switch (shape.charAt(index)) {
                    case 'L' -> "L";
                    case 'N', 'C' -> shown(value);
                    case 'T' -> {
                        List<String> entries = new ArrayList<>();
                        for (int entry = 1; entry <= value.checktable().rawlen(); entry++)
                            entries.add(value.checktable().rawget(entry).tojstring());
                        yield "{" + String.join(", ", entries) + "}";
                    }
                    default -> value.tojstring();
                });
            }
            return String.join(", ", shown);
        }

        /** A number as a recorded call shows it, to 9 digits. */
        private static String shown(LuaValue number) {
            double value = number.todouble();
            return Double.isFinite(value) ? new BigDecimal(value).round(new MathContext(9)).stripTrailingZeros()
                    .toPlainString() : number.tojstring();
        }

        private static void check(String name, String shape, Varargs args) {
            if (args.narg() > shape.length())
                throw new LuaError("c.pet:" + name + " takes " + shape.length() + " arguments, got " + args.narg());
            for (int index = 0; index < shape.length(); index++) {
                LuaValue value = args.arg(index + 1);
                char kind = shape.charAt(index);
                boolean fits = switch (kind) {
                    case 'L' -> isLocation(value);
                    case 'N' -> value.type() == LuaValue.TNUMBER && Double.isFinite(value.todouble());
                    case 'C' -> value.type() == LuaValue.TNUMBER && !Double.isNaN(value.todouble());
                    case 'T' -> isTextList(value);
                    case 'S' -> value.type() == LuaValue.TSTRING;
                    case 's' -> value.isnil() || value.type() == LuaValue.TSTRING;
                    case 'B' -> value.isboolean();
                    default -> throw new IllegalStateException("Unknown argument kind " + kind);
                };
                if (!fits) throw new LuaError("c.pet:" + name + " argument " + (index + 1) + " must be "
                        + describe(kind) + ", got " + value.typename() + " " + value.tojstring());
            }
        }

        private static boolean isLocation(LuaValue value) {
            if (!value.istable()) return false;
            for (String axis : List.of("x", "y", "z")) {
                LuaValue coordinate = value.get(axis);
                if (coordinate.type() != LuaValue.TNUMBER || !Double.isFinite(coordinate.todouble())) return false;
            }
            LuaValue world = value.get("world");
            return world.isnil() || world.type() == LuaValue.TSTRING;
        }

        private static boolean isTextList(LuaValue value) {
            if (!value.istable()) return false;
            LuaTable list = value.checktable();
            for (int index = 1; index <= list.rawlen(); index++)
                if (list.rawget(index).type() != LuaValue.TSTRING) return false;
            return true;
        }

        private static String describe(char kind) {
            return switch (kind) {
                case 'L' -> "a location table";
                case 'N' -> "a finite number";
                case 'C' -> "a number that is not NaN";
                case 'T' -> "a list of text";
                case 'S' -> "text";
                case 's' -> "text or nil";
                default -> "true or false";
            };
        }

        @Override
        public String toString() {
            return "owner " + owner + (staying ? ", staying" : "") + (night ? ", night and rain" : ", day") + ", answers "
                    + answers;
        }
    }

    /**
     * A Mind context at this tick whose memory really remembers, with the pet's body, c.perception and c.actuator; its
     * other parts answer with plain defaults.
     */
    private static MindContext context(Map<Object, Object> memory, long tick, StubPet pet) {
        MindMemoryView view = stub(MindMemoryView.class, (proxy, method, args) -> switch (method.getName()) {
            case "get" -> Optional.ofNullable(memory.get(args[0]));
            case "set" -> memory.put(args[0], args[1]);
            case "forget" -> memory.remove(args[0]);
            case "contains" -> memory.containsKey(args[0]);
            default -> null;
        });
        return new MindContext(pet.body, tick, 1L, view, pet.perception, pet.actuator,
                stub(MindActionRequester.class, ShippedPetModulesTest::plain));
    }

    /**
     * A call the stand-in does not answer: it fails, so a shipped module that starts using it fails here until the
     * stand-in checks it.
     */
    private static Object unknown(String part, Object proxy, java.lang.reflect.Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> part;
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == args[0];
            default -> throw new AssertionError("The stand-in's " + part + " has no " + method.getName()
                    + "; add it, checked, before a shipped module uses it");
        };
    }

    private static Object plain(Object proxy, java.lang.reflect.Method method, Object[] args) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == double.class) return 0D;
        if (type == float.class) return 0F;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == Optional.class) return Optional.empty();
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
