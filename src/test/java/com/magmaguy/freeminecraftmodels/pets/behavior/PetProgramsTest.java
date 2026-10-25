package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetProgramsTest {
    private static final String FILE = "jellyfish.pet.yml";
    private static final String GOLD = "em_pet_jellyfish_gold";
    @TempDir
    Path folder;
    private final List<String> warnings = new ArrayList<>();

    /** Writes a module with a radius setting for each name, then loads the folder. */
    private PetBehaviorCatalog catalog(String... names) throws IOException {
        for (String name : names) write(name, PetBehaviorCatalogTest.module(name, "radius = 12"));
        return load();
    }

    private PetBehaviorCatalog load() {
        return PetBehaviorCatalog.load(folder, List.of(), ignored -> { }, warnings::add);
    }

    private void write(String name, String source) throws IOException {
        Files.writeString(folder.resolve(name + ".lua"), source);
    }

    /** A module with one behavior of its own name and the given fields, such as memories or dependencies. */
    private static String module(String name, String fields) {
        return "return ai.module { id = 'fmm:pet/" + name + "', revision = 1, " + fields + ", behaviors = {"
                + " ai.behavior { id = 'fmm:pet/" + name + "', tick = function(c) end } } }";
    }

    private static PetBehaviorEntry entry(String module) {
        return new PetBehaviorEntry(module, Map.of());
    }

    private static List<String> ids(PetPrograms.Program program) {
        return program.definition().resolvedModules().stream().map(module -> module.identifier()).toList();
    }

    @Test
    void aProgramComposesTheListedModulesWithTheirSettings() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay", "drift")) {
            PetPrograms.Program program = new PetPrograms(catalog, warnings::add).program("jellyfish", "em_pet_jellyfish",
                    List.of(entry("stay"), new PetBehaviorEntry("drift", Map.of("radius", 8))), FILE);
            assertEquals(List.of("fmm:pet/stay", "fmm:pet/drift"), ids(program));
            assertEquals(8D, program.settings().get("fmm:pet/drift").get("radius"));
            assertEquals(12D, program.settings().get("fmm:pet/stay").get("radius"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void anUnknownModuleIsLeftOutWithAWarning() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay")) {
            PetPrograms.Program program = new PetPrograms(catalog, warnings::add).program("jellyfish", "em_pet_jellyfish",
                    List.of(entry("stay"), entry("dance")), FILE);
            assertEquals(List.of("fmm:pet/stay"), ids(program));
        }
        assertEquals(List.of(FILE + ": there is no pet behavior dance in pet_behaviors/ (the only one there is stay);"
                + " the creature runs without it."), warnings);
    }

    @Test
    void anUnknownModuleSuggestsACloseName() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay", "drift", "hover")) {
            new PetPrograms(catalog, warnings::add).program("jellyfish", GOLD, List.of(entry("dirft")), FILE);
        }
        assertEquals(List.of(FILE + ": there is no pet behavior dirft in pet_behaviors/ (did you mean drift?);"
                + " the creature runs without it."), warnings);
    }

    @Test
    void anUnknownModuleNamesTheOnesThereWhenThereAreFew() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay", "drift", "hover")) {
            new PetPrograms(catalog, warnings::add).program("jellyfish", GOLD, List.of(entry("dance")), FILE);
        }
        assertEquals(List.of(FILE + ": there is no pet behavior dance in pet_behaviors/ (the ones there are drift,"
                + " hover and stay); the creature runs without it."), warnings);
    }

    @Test
    void aModuleThatFailedToLoadIsNamedAsSuch() throws IOException {
        write("drift", "return ai.module { id = ");
        try (PetBehaviorCatalog catalog = catalog("stay")) {
            assertEquals(1, warnings.size(), warnings.toString());
            PetPrograms.Program program = new PetPrograms(catalog, warnings::add).program("jellyfish", GOLD,
                    List.of(entry("stay"), entry("drift")), FILE);
            assertEquals(List.of("fmm:pet/stay"), ids(program));
        }
        assertEquals(List.of(FILE + ": the pet behavior drift failed to load (see the warning about "
                + folder.getFileName() + "/drift.lua); the creature runs without it."), warnings.subList(1, warnings.size()));
    }

    @Test
    void aSpeciesListsProblemsAreSaidOncePerLoad() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay")) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            List<PetBehaviorEntry> list = List.of(new PetBehaviorEntry("stay", Map.of("radius", "far")), entry("dance"));
            for (String variant : List.of("em_pet_jellyfish_blue", GOLD, "em_pet_jellyfish_blue"))
                assertEquals(List.of("fmm:pet/stay"), ids(programs.program("jellyfish", variant, list, FILE)));
        }
        assertEquals(2, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("dance"), warnings.get(0));
        assertTrue(warnings.get(1).contains("radius"), warnings.get(1));
    }

    @Test
    void eachSpeciesAndVariantIsBuiltOnce() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay")) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            List<PetBehaviorEntry> list = List.of(entry("stay"));
            assertSame(programs.program("jellyfish", "blue", list, "x"), programs.program("jellyfish", "blue", list, "x"));
        }
    }

    @Test
    void eachVariantHasItsOwnProgram() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay", "drift")) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            PetPrograms.Program blue = programs.program("jellyfish", "em_pet_jellyfish_blue", List.of(entry("stay")), FILE);
            PetPrograms.Program gold = programs.program("jellyfish", GOLD, List.of(entry("stay"), entry("drift")), FILE);
            assertNotSame(blue, gold);
            assertEquals(List.of("fmm:pet/stay"), ids(blue));
            assertEquals(List.of("fmm:pet/stay", "fmm:pet/drift"), ids(gold));
        }
    }

    @Test
    void aRepeatedModuleRunsOnceWithItsFirstSettings() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay")) {
            PetPrograms.Program program = new PetPrograms(catalog, warnings::add).program("jellyfish", GOLD,
                    List.of(new PetBehaviorEntry("stay", Map.of("radius", 8)), new PetBehaviorEntry("Stay", Map.of("radius", 9))),
                    FILE);
            assertEquals(List.of("fmm:pet/stay"), ids(program));
            assertEquals(8D, program.settings().get("fmm:pet/stay").get("radius"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aModuleThatClashesWithTheOnesBeforeItIsLeftOutAndTheRestRun() throws IOException {
        // Each module loads on its own; stay and drift only clash together, since both declare the memory home.
        write("stay", module("stay", "memories = { home = 'position' }"));
        write("anchor", module("anchor", "settings = { depth = 2 }"));
        write("drift", module("drift", "settings = { radius = 12 }, dependencies = { 'fmm:pet/anchor' },"
                + " memories = { home = 'position' }"));
        write("hover", module("hover", "settings = { height = 1 }"));
        try (PetBehaviorCatalog catalog = load()) {
            assertEquals(List.of(), warnings);
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            List<PetBehaviorEntry> list = List.of(entry("stay"), new PetBehaviorEntry("drift", Map.of("radius", "far")),
                    entry("hover"));
            PetPrograms.Program program = programs.program("jellyfish", GOLD, list, FILE);
            assertSame(program, programs.program("jellyfish", GOLD, list, FILE));

            assertEquals(List.of("fmm:pet/stay", "fmm:pet/hover"), ids(program));
            // Only what runs has settings: drift's bad radius is never reported, and its dependency stays out.
            assertEquals(List.of("fmm:pet/stay", "fmm:pet/hover"), List.copyOf(program.settings().keySet()));
            assertEquals(2, program.definition().instantiate().behaviors().size());
            // Another variant of the species clashes the same way, which was said already.
            assertEquals(List.of("fmm:pet/stay", "fmm:pet/hover"),
                    ids(programs.program("jellyfish", "em_pet_jellyfish_blue", list, FILE)));
        }
        assertEquals(List.of(FILE + ": the pet behavior drift cannot run alongside the ones listed before it (Duplicate"
                + " Mind memory alias 'home' in fmm:pet/stay and fmm:pet/drift); the creature runs without drift."), warnings);
    }

    /** Writes a and d, which both declare the memory home, and x, which needs d. */
    private void writeClashThroughADependency() throws IOException {
        write("a", module("a", "memories = { home = 'position' }"));
        write("d", module("d", "memories = { home = 'position' }"));
        write("x", module("x", "dependencies = { 'fmm:pet/d' }"));
    }

    @Test
    void aModuleWhoseDependencyClashesIsLeftOutWithIt() throws IOException {
        writeClashThroughADependency();
        try (PetBehaviorCatalog catalog = load()) {
            PetPrograms.Program program = new PetPrograms(catalog, warnings::add).program("jellyfish", GOLD,
                    List.of(entry("a"), entry("x")), FILE);
            assertEquals(List.of("fmm:pet/a"), ids(program));
            assertEquals(List.of("fmm:pet/a"), List.copyOf(program.settings().keySet()));
        }
        assertEquals(List.of(FILE + ": the pet behavior x needs pet behaviors that cannot run alongside the ones listed"
                + " before it (Duplicate Mind memory alias 'home' in fmm:pet/a and fmm:pet/d); the creature runs without"
                + " x."), warnings);
    }

    @Test
    void aModuleNeedingOneLeftOutIsLeftOutToo() throws IOException {
        writeClashThroughADependency();
        try (PetBehaviorCatalog catalog = load()) {
            PetPrograms.Program program = new PetPrograms(catalog, warnings::add).program("jellyfish", GOLD,
                    List.of(entry("a"), entry("d"), entry("x")), FILE);
            assertEquals(List.of("fmm:pet/a"), ids(program));
            assertEquals(List.of("fmm:pet/a"), List.copyOf(program.settings().keySet()));
        }
        String reason = " (Duplicate Mind memory alias 'home' in fmm:pet/a and fmm:pet/d); the creature runs without ";
        assertEquals(List.of(FILE + ": the pet behavior d cannot run alongside the ones listed before it" + reason + "d.",
                FILE + ": the pet behavior x needs pet behaviors that cannot run alongside the ones listed before it"
                        + reason + "x."), warnings);
    }

    @Test
    void aDependencyRunsWithItsDefaultsUnlessTheListTunesIt() throws IOException {
        write("base", module("base", "settings = { speed = 1 }"));
        write("follow", module("follow", "settings = { gap = 3 }, dependencies = { 'fmm:pet/base' }"));
        try (PetBehaviorCatalog catalog = load()) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            PetPrograms.Program plain = programs.program("jellyfish", "em_pet_jellyfish_blue", List.of(entry("follow")), FILE);
            assertEquals(List.of("fmm:pet/base", "fmm:pet/follow"), ids(plain));
            assertEquals(Map.of("speed", 1D), plain.settings().get("fmm:pet/base"));
            assertEquals(Map.of("gap", 3D), plain.settings().get("fmm:pet/follow"));

            PetPrograms.Program tuned = programs.program("jellyfish", GOLD,
                    List.of(entry("follow"), new PetBehaviorEntry("base", Map.of("speed", 3))), FILE);
            assertEquals(Map.of("speed", 3D), tuned.settings().get("fmm:pet/base"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aClosedCatalogLeavesTheCreatureWithNoneAndOneWarning() throws IOException {
        PetBehaviorCatalog catalog = catalog("stay", "drift");
        catalog.close();
        PetPrograms programs = new PetPrograms(catalog, warnings::add);
        List<PetBehaviorEntry> list = List.of(entry("stay"), entry("drift"));
        PetPrograms.Program program = programs.program("jellyfish", GOLD, list, FILE);
        assertSame(program, programs.program("jellyfish", GOLD, list, FILE));

        assertEquals(List.of(), ids(program));
        assertEquals(Map.of(), program.settings());
        assertTrue(program.definition().instantiate().behaviors().isEmpty());
        assertEquals(List.of(), ids(programs.program("jellyfish", "em_pet_jellyfish_blue", list, FILE)));
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith(FILE + ": could not build its pet behaviors (")
                && warnings.getFirst().endsWith("); the creature runs none of them."), warnings.getFirst());
        assertFalse(warnings.getFirst().contains("Exception"), warnings.getFirst());
    }

    @Test
    void namesBecomeAProgramIdInLowerCaseWithoutSpaces() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay")) {
            PetPrograms.Program program = new PetPrograms(catalog, warnings::add).program("Jelly Fish", "EM Pet Jellyfish",
                    List.of(entry("stay")), FILE);
            assertEquals("fmm:pet/creature/jelly_fish/em_pet_jellyfish", program.definition().programIdentifier());
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aMissingSpeciesOrVariantIsAProgrammingError() throws IOException {
        try (PetBehaviorCatalog catalog = catalog("stay")) {
            PetPrograms programs = new PetPrograms(catalog, warnings::add);
            assertThrows(NullPointerException.class, () -> programs.program(null, GOLD, List.of(), FILE));
            assertThrows(NullPointerException.class, () -> programs.program("jellyfish", null, List.of(), FILE));
        }
    }

    @Test
    void eachLocomotionHasADefaultList() {
        assertEquals(List.of("migrate", "stay", "follow_owner", "drift", "hover"), names(MindBodyLocomotion.FLYING));
        assertEquals(List.of("stay", "follow_owner", "wander", "idle_look"), names(MindBodyLocomotion.GROUNDED));
        assertEquals(List.of("stay", "follow_owner", "wander", "idle_look"), names(MindBodyLocomotion.AMPHIBIOUS));
        assertEquals(List.of("stay", "follow_owner", "swim"), names(MindBodyLocomotion.AQUATIC));
        assertEquals(List.of("stay", "follow_owner", "idle_look"), names(MindBodyLocomotion.STATIONARY));
    }

    private static List<String> names(MindBodyLocomotion locomotion) {
        return PetPrograms.defaults(locomotion).stream().map(PetBehaviorEntry::module).toList();
    }
}
