package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.ai.MindBehavior;
import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindSensor;
import com.magmaguy.magmacore.scripting.LuaEnvironmentFactory;
import com.magmaguy.magmacore.scripting.LuaMindContextExtension;
import com.magmaguy.magmacore.shaded.luaj.vm2.Globals;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a wild body and an owned body each run of the shipped modules, and the gates that keep a wild pet's retaliation
 * out of Lua until a player hits it: which modules and sensors each program holds, when a gated behavior or sensor
 * reaches its module, and that only the shipped text of a module is treated by its ids.
 */
class PetOwnershipProgramsTest {
    private static final String CHARGE = "fmm:pet/retaliate_charge", FLEE = "fmm:pet/retaliate_flee",
            STRIKE = "fmm:pet/strike_back", STING_PLAYERS = "fmm:pet/sting_players", STING_MOBS = "fmm:pet/sting_mobs";
    /** A penguin's list, as its pet file has it: it pecks only as an owned pet, and hits back only as a wild one. */
    private static final List<PetBehaviorEntry> PENGUIN = List.of(entry("stay"), entry("defend_owner"),
            entry("follow_owner"), entry("pebble_gift"), entry("owner_effect"),
            new PetBehaviorEntry("sting", Map.of("wild", false, "pets", true)),
            new PetBehaviorEntry("retaliate", Map.of("reach", 1.5)), entry("strike_back"), entry("wander"));
    /** A Driftjelly's: it stings whoever touches it in the wild, and mobs as a pet. */
    private static final List<PetBehaviorEntry> JELLY = List.of(entry("stay"), entry("follow_owner"), entry("drift"),
            entry("sting"), new PetBehaviorEntry("retaliate", Map.of("reach", 0.5)));
    @TempDir
    Path folder;
    private final List<String> warnings = new ArrayList<>();

    private static PetBehaviorEntry entry(String module) {
        return new PetBehaviorEntry(module, Map.of());
    }

    private static String bundled(String name) {
        return PetBehaviorDefaults.text(name, resource -> PetOwnershipProgramsTest.class.getResourceAsStream("/" + resource));
    }

    /** Loads the folder as a server does: missing defaults written from the jar, and the catalog told their texts. */
    private PetBehaviorCatalog catalog() {
        return PetBehaviorCatalog.load(folder, PetBehaviorCatalog.DEFAULTS, name -> {
            try (InputStream in = PetOwnershipProgramsTest.class.getResourceAsStream("/pet_behaviors/" + name + ".lua")) {
                Files.copy(in, folder.resolve(name + ".lua"));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }, warnings::add, PetOwnershipProgramsTest::bundled);
    }

    private static Set<String> behaviors(MindProgram program) {
        return program.behaviors().stream().map(MindBehavior::identifier).collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> sensors(MindProgram program) {
        return program.sensors().stream().map(MindSensor::identifier).collect(Collectors.toCollection(TreeSet::new));
    }

    private static boolean gated(Object behaviorOrSensor) {
        return behaviorOrSensor.getClass().getName().startsWith(PetPrograms.class.getName() + "$Hurt");
    }

    @Test
    void theGatesPassOnEveryMethodOfTheBehaviorOrSensorTheyWrap() throws ClassNotFoundException {
        // A method MagmaCore adds with a default would otherwise answer its default for a gated behavior, silently.
        for (Class<?>[] pair : new Class<?>[][]{
                {MindBehavior.class, Class.forName(PetPrograms.class.getName() + "$HurtBehavior")},
                {MindSensor.class, Class.forName(PetPrograms.class.getName() + "$HurtSensor")}}) {
            List<String> missing = new ArrayList<>();
            for (Method method : pair[0].getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) continue;
                try {
                    pair[1].getDeclaredMethod(method.getName(), method.getParameterTypes());
                } catch (NoSuchMethodException absent) {
                    missing.add(method.getName());
                }
            }
            assertEquals(List.of(), missing, pair[1].getSimpleName() + " passes on every " + pair[0].getSimpleName() + " method");
        }
    }

    @Test
    void aWildBodyAndAnOwnedBodyEachRunOnlyTheirOwnModules() {
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            PetPrograms.Program wild = programs.program("penguin", "penguin", PetPrograms.Ownership.WILD, PENGUIN, "penguin.pet.yml");
            PetPrograms.Program owned = programs.program("penguin", "penguin", PetPrograms.Ownership.OWNED, PENGUIN, "penguin.pet.yml");
            for (String module : List.of("stay", "defend_owner", "follow_owner", "pebble_gift", "owner_effect", "sting")) {
                assertFalse(wild.runs(module), "a wild penguin leaves out " + module);
                assertTrue(owned.runs(module), "an owned penguin runs " + module);
            }
            for (String module : List.of("retaliate", "strike_back")) {
                assertTrue(wild.runs(module), "a wild penguin runs " + module);
                assertFalse(owned.runs(module), "an owned penguin leaves out " + module);
            }
            assertTrue(wild.runs("wander") && owned.runs("wander"), "a module without a switch runs for both");
            MindProgram wildMind = wild.instantiate(LuaMindContextExtension.NONE, past -> false);
            MindProgram ownedMind = owned.instantiate(LuaMindContextExtension.NONE, past -> false);
            assertEquals(Set.of(CHARGE, FLEE, "fmm:pet/wander"), behaviors(wildMind));
            assertEquals(Set.of(STRIKE), sensors(wildMind));
            assertFalse(behaviors(ownedMind).contains(CHARGE) || behaviors(ownedMind).contains(FLEE));
            assertEquals(Set.of(STING_MOBS), sensors(ownedMind).stream().filter(id -> id.contains("st")).collect(Collectors.toSet()),
                    "an owned penguin pecks mobs, and has neither the wild side of sting nor strike_back");
            assertFalse(wild.readsContacts(), "a penguin's model records no touches: nothing of its reads them");
            assertFalse(owned.readsContacts());

            // The Driftjelly stings on both sides, so each body keeps one of sting's two sensors.
            PetPrograms.Program wildJelly = programs.program("jelly", "jelly", PetPrograms.Ownership.WILD, JELLY, "jelly.pet.yml");
            PetPrograms.Program ownedJelly = programs.program("jelly", "jelly", PetPrograms.Ownership.OWNED, JELLY, "jelly.pet.yml");
            assertEquals(Set.of(STING_MOBS), wildJelly.leftOutSensors());
            assertEquals(Set.of(STING_PLAYERS), ownedJelly.leftOutSensors());
            assertEquals(Set.of(STING_PLAYERS), sensors(wildJelly.instantiate(LuaMindContextExtension.NONE, past -> false)));
            assertEquals(Set.of(STING_MOBS), sensors(ownedJelly.instantiate(LuaMindContextExtension.NONE, past -> false)));
            assertTrue(wildJelly.readsContacts(), "a wild jelly's model records the touches its sting reads");
            assertFalse(ownedJelly.readsContacts(), "an owned one's records none");
            // A program for either ownership leaves nothing out.
            PetPrograms.Program either = programs.program("jelly", "jelly", JELLY, "jelly.pet.yml");
            assertEquals(Set.of(), either.leftOutSensors());
            assertTrue(either.runs("stay") && either.runs("retaliate"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void onlyAWildBodyRunsMigrate() {
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            List<PetBehaviorEntry> flyer = PetPrograms.defaults(com.magmaguy.magmacore.ai.MindBodyLocomotion.FLYING);
            // A migrant is a wild body; taming one spawns an owned body, which is in no flock.
            PetPrograms.Program wild = programs.program("jelly", "flyer", PetPrograms.Ownership.WILD, flyer, "jelly.pet.yml");
            PetPrograms.Program owned = programs.program("jelly", "flyer", PetPrograms.Ownership.OWNED, flyer, "jelly.pet.yml");
            assertTrue(wild.runs("migrate"), "a wild flyer runs migrate");
            assertFalse(owned.runs("migrate"), "an owned flyer leaves it out");
            assertTrue(behaviors(wild.instantiate(LuaMindContextExtension.NONE, past -> false)).contains("fmm:pet/migrate"));
            assertFalse(behaviors(owned.instantiate(LuaMindContextExtension.NONE, past -> false)).contains("fmm:pet/migrate"));
            // A pet file may still switch it on for owned pets, where it never finds a route.
            PetPrograms.Program asked = programs.program("jelly", "asked", PetPrograms.Ownership.OWNED,
                    List.of(new PetBehaviorEntry("migrate", Map.of("pets", true))), "jelly.pet.yml");
            assertTrue(asked.runs("migrate"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aGatedBehaviorOrSensorReachesItsModuleOnlyAfterARecentHit() {
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms.Program wild = new PetPrograms(catalog, warnings::add)
                    .program("penguin", "penguin", PetPrograms.Ownership.WILD, PENGUIN, "penguin.pet.yml");
            assertEquals(Set.of("fmm:pet/retaliate", "fmm:pet/strike_back"), wild.gated());
            // The gate closed: each answers without its module, and says how far past the hit it may still act.
            Map<String, Long> asked = new HashMap<>();
            String[] asking = new String[1];
            MindProgram closed = wild.instantiate(LuaMindContextExtension.NONE, past -> {
                asked.put(asking[0], past);
                return false;
            });
            for (MindBehavior behavior : closed.behaviors()) {
                boolean hurtGated = behavior.identifier().equals(CHARGE) || behavior.identifier().equals(FLEE);
                assertEquals(hurtGated, gated(behavior), behavior.identifier());
                if (!hurtGated) continue;
                asking[0] = behavior.identifier();
                // No context: a closed gate must answer before anything reads one.
                assertFalse(behavior.canStart(null), behavior.identifier() + " cannot start without a recent hit");
            }
            for (MindSensor sensor : closed.sensors()) {
                assertTrue(gated(sensor), sensor.identifier());
                asking[0] = sensor.identifier();
                sensor.sense(null);
            }
            // The charge and the strike can act while c.pet.attacker is set; the flee for flee_seconds (4) more.
            assertEquals(Map.of(CHARGE, 0L, STRIKE, 0L, FLEE, 80L), asked);
            // What a gated behavior declares is its module's own.
            MindProgram plain = wild.definition().instantiate();
            for (MindBehavior behavior : closed.behaviors()) {
                MindBehavior own = plain.behaviors().stream()
                        .filter(other -> other.identifier().equals(behavior.identifier())).findFirst().orElseThrow();
                assertEquals(own.priority(), behavior.priority());
                assertEquals(own.controls(), behavior.controls());
                assertEquals(own.probeInterval(), behavior.probeInterval());
                assertEquals(own.tickInterval(), behavior.tickInterval());
            }
            assertEquals(1, PetPrograms.longestTickInterval(closed), "no shipped behavior runs less than every tick");
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void strikeBackWithItsDelayIsTheShippedDefaultAndTheCopyBeforeItAnOlderOne() throws IOException {
        // The text this build ships, delay_ticks and all, is the shipped default: its sensor waits behind the hit gate.
        assertTrue(bundled("strike_back").contains("delay_ticks = 0"), "the shipped strike_back has delay_ticks");
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms.Program wild = new PetPrograms(catalog, warnings::add)
                    .program("penguin", "penguin", PetPrograms.Ownership.WILD, PENGUIN, "penguin.pet.yml");
            assertTrue(wild.gated().contains(STRIKE));
            MindProgram mind = wild.instantiate(LuaMindContextExtension.NONE, past -> false);
            assertTrue(mind.sensors().stream().anyMatch(sensor -> sensor.identifier().equals(STRIKE) && gated(sensor)));
        }
        // The copy a server that ran 141716d to d2a5b49 has in its folder is not this build's text, so it is not
        // taken for the shipped default: it loads, runs as it declares, and nothing gates it. It strikes as it did.
        String before;
        try (InputStream in = PetOwnershipProgramsTest.class.getResourceAsStream("/pet_behaviors_before/strike_back.lua")) {
            before = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(before.contains("delay_ticks"));
        Files.writeString(folder.resolve("strike_back.lua"), before);
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms.Program wild = new PetPrograms(catalog, warnings::add)
                    .program("penguin", "penguin", PetPrograms.Ownership.WILD, PENGUIN, "penguin.pet.yml");
            assertTrue(wild.runs("strike_back"));
            assertEquals(Set.of("fmm:pet/retaliate"), wild.gated());
            MindProgram mind = wild.instantiate(LuaMindContextExtension.NONE, past -> false);
            assertTrue(mind.sensors().stream().anyMatch(sensor -> sensor.identifier().equals(STRIKE) && !gated(sensor)));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void onlyTheShippedTextOfAModuleIsGated() throws IOException {
        catalog().close();
        // An owner's edit: the flee may now start on something other than a hit, so nothing may gate it.
        for (String module : List.of("retaliate", "strike_back", "sting")) {
            Path file = folder.resolve(module + ".lua");
            Files.writeString(file, Files.readString(file) + "\n-- edited\n");
        }
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            PetPrograms.Program wild = programs.program("penguin", "penguin", PetPrograms.Ownership.WILD, PENGUIN, "penguin.pet.yml");
            assertEquals(Set.of(), wild.gated());
            MindProgram mind = wild.instantiate(LuaMindContextExtension.NONE, past -> {
                throw new AssertionError("an edited module is asked as it declares, not through the hit gate");
            });
            assertEquals(Set.of(CHARGE, FLEE, "fmm:pet/wander"), behaviors(mind));
            mind.behaviors().forEach(behavior -> assertFalse(gated(behavior), behavior.identifier()));
            mind.sensors().forEach(sensor -> assertFalse(gated(sensor), sensor.identifier()));
            PetPrograms.Program jelly = programs.program("jelly", "jelly", PetPrograms.Ownership.OWNED, JELLY, "jelly.pet.yml");
            assertEquals(Set.of(), jelly.leftOutSensors(), "an edited sting keeps both its sensors");
            // The switches are the module's own settings, so they still hold for an edited file.
            assertFalse(jelly.runs("retaliate"));
        }
        // The shipped text saved with other line endings is still the shipped text.
        for (String module : List.of("retaliate", "strike_back", "sting"))
            Files.write(folder.resolve(module + ".lua"),
                    bundled(module).replace("\r", "").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8));
        try (PetBehaviorCatalog catalog = catalog()) {
            PetPrograms.Program wild = new PetPrograms(catalog, warnings::add)
                    .program("penguin", "penguin", PetPrograms.Ownership.WILD, PENGUIN, "penguin.pet.yml");
            assertEquals(Set.of("fmm:pet/retaliate", "fmm:pet/strike_back"), wild.gated());
        }
        // A catalog loaded without the texts knows no module as shipped.
        try (PetBehaviorCatalog catalog = PetBehaviorCatalog.load(folder, List.of(), ignored -> {
        }, warnings::add)) {
            assertEquals(Set.of(), new PetPrograms(catalog, warnings::add)
                    .program("penguin", "penguin", PetPrograms.Ownership.WILD, PENGUIN, "penguin.pet.yml").gated());
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void theFleeWindowIsTheModulesOwnTicks() {
        // retaliate.lua turns flee_seconds into ticks with this expression; the gate must agree with it for any value.
        String ticks = "math.max(1, math.floor(math.min(seconds * 20, 2147483647)))";
        assertTrue(bundled("retaliate").contains("return " + ticks), "retaliate.lua's ticks() is the expression checked here");
        Globals lua = LuaEnvironmentFactory.createGlobals();
        for (double seconds : new double[]{0, -3, 0.01, 0.26, 4, 2.5, 1e12, Double.POSITIVE_INFINITY}) {
            long expected = lua.load("local seconds = ... return " + ticks).call(
                    com.magmaguy.magmacore.shaded.luaj.vm2.LuaValue.valueOf(seconds)).tolong();
            Map<String, Long> windows = PetPrograms.hurtWindows(Map.of("fmm:pet/retaliate", Map.of("flee_seconds", seconds)));
            assertEquals(Map.of(CHARGE, 0L, FLEE, expected), windows, "flee_seconds " + seconds);
        }
        // Without a number to go by the flee is asked as it declares; the charge's window needs no setting.
        Map<String, Object> noNumber = new HashMap<>();
        noNumber.put("flee_seconds", "soon");
        assertEquals(Map.of(CHARGE, 0L), PetPrograms.hurtWindows(Map.of("fmm:pet/retaliate", noNumber)));
        assertEquals(Map.of(CHARGE, 0L), PetPrograms.hurtWindows(Map.of("fmm:pet/retaliate", Map.of("flee_seconds", Double.NaN))));
        assertEquals(Map.of(CHARGE, 0L), PetPrograms.hurtWindows(Map.of("fmm:pet/retaliate", Map.of())));
        assertEquals(Map.of(), PetPrograms.hurtWindows(Map.of("fmm:pet/wander", Map.of())), "a program without retaliate");
    }
}
