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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetProgramsTest {
    @TempDir
    Path folder;
    private final List<String> warnings = new ArrayList<>();

    private PetPrograms programs(String... names) throws IOException {
        for (String name : names) Files.writeString(folder.resolve(name + ".lua"), PetBehaviorCatalogTest.module(name, "radius = 12"));
        return new PetPrograms(PetBehaviorCatalog.load(folder, List.of(), ignored -> { }, warnings::add), warnings::add);
    }

    @Test
    void aProgramComposesTheListedModulesWithTheirSettings() throws IOException {
        PetPrograms.Program program = programs("stay", "drift").program("jellyfish", "em_pet_jellyfish",
                List.of(new PetBehaviorEntry("stay", Map.of()), new PetBehaviorEntry("drift", Map.of("radius", 8))), "jellyfish.pet.yml");
        assertEquals(List.of("fmm:pet/stay", "fmm:pet/drift"),
                program.definition().resolvedModules().stream().map(module -> module.identifier()).toList());
        assertEquals(8D, program.settings().get("fmm:pet/drift").get("radius"));
        assertEquals(12D, program.settings().get("fmm:pet/stay").get("radius"));
        assertEquals(List.of(), warnings);
    }

    @Test
    void anUnknownModuleIsLeftOutWithAWarning() throws IOException {
        PetPrograms.Program program = programs("stay").program("jellyfish", "em_pet_jellyfish",
                List.of(new PetBehaviorEntry("stay", Map.of()), new PetBehaviorEntry("dance", Map.of())), "jellyfish.pet.yml");
        assertEquals(1, program.definition().resolvedModules().size());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("dance") && warnings.getFirst().contains("jellyfish.pet.yml"), warnings.getFirst());
    }

    @Test
    void anUnknownModuleNamesTheOnesThereWhenThereAreFew() throws IOException {
        programs("stay", "drift", "hover").program("jellyfish", "em_pet_jellyfish",
                List.of(new PetBehaviorEntry("dirft", Map.of())), "jellyfish.pet.yml");
        assertEquals(List.of("jellyfish.pet.yml: lists the pet behavior dirft, which is not in pet_behaviors/ (the ones"
                + " there are drift, hover and stay); the creature runs without it."), warnings);
    }

    @Test
    void eachSpeciesAndVariantIsBuiltOnce() throws IOException {
        PetPrograms programs = programs("stay");
        List<PetBehaviorEntry> list = List.of(new PetBehaviorEntry("stay", Map.of()));
        assertSame(programs.program("jellyfish", "blue", list, "x"), programs.program("jellyfish", "blue", list, "x"));
    }

    @Test
    void eachVariantHasItsOwnProgram() throws IOException {
        PetPrograms programs = programs("stay", "drift");
        PetPrograms.Program blue = programs.program("jellyfish", "em_pet_jellyfish_blue",
                List.of(new PetBehaviorEntry("stay", Map.of())), "jellyfish.pet.yml");
        PetPrograms.Program gold = programs.program("jellyfish", "em_pet_jellyfish_gold",
                List.of(new PetBehaviorEntry("stay", Map.of()), new PetBehaviorEntry("drift", Map.of())), "jellyfish.pet.yml");
        assertNotSame(blue, gold);
        assertEquals(1, blue.definition().resolvedModules().size());
        assertEquals(2, gold.definition().resolvedModules().size());
    }

    @Test
    void modulesThatDoNotFitTogetherLeaveTheCreatureWithNoneAndWarnOnce() throws IOException {
        // Each module loads on its own; only together do they declare the same memory.
        for (String name : List.of("stay", "drift"))
            Files.writeString(folder.resolve(name + ".lua"), "return ai.module { id = 'fmm:pet/" + name
                    + "', revision = 1, memories = { home = 'position' }, behaviors = { ai.behavior { id = 'fmm:pet/"
                    + name + "', tick = function(c) end } } }");
        PetPrograms programs = new PetPrograms(PetBehaviorCatalog.load(folder, List.of(), ignored -> { }, warnings::add),
                warnings::add);
        assertEquals(List.of(), warnings);
        List<PetBehaviorEntry> list = List.of(new PetBehaviorEntry("stay", Map.of()), new PetBehaviorEntry("drift", Map.of()));
        PetPrograms.Program program = programs.program("jellyfish", "em_pet_jellyfish_gold", list, "jellyfish.pet.yml");
        assertSame(program, programs.program("jellyfish", "em_pet_jellyfish_gold", list, "jellyfish.pet.yml"));

        assertEquals(List.of(), program.definition().resolvedModules());
        assertEquals(Map.of(), program.settings());
        assertTrue(program.definition().instantiate().behaviors().isEmpty());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().startsWith("jellyfish.pet.yml, variant em_pet_jellyfish_gold: ")
                && warnings.getFirst().contains("home")
                && warnings.getFirst().endsWith("; the creature runs none of them."), warnings.getFirst());
        assertTrue(!warnings.getFirst().contains("Exception"), warnings.getFirst());
    }

    @Test
    void eachLocomotionHasADefaultList() {
        assertEquals(List.of("migrate", "stay", "follow_owner", "drift", "hover"), names(MindBodyLocomotion.FLYING));
        assertEquals(List.of("stay", "follow_owner", "wander", "idle_look"), names(MindBodyLocomotion.GROUNDED));
        assertEquals(List.of("stay", "follow_owner", "wander", "idle_look"), names(MindBodyLocomotion.AMPHIBIOUS));
        assertEquals(List.of("stay", "follow_owner", "swim"), names(MindBodyLocomotion.AQUATIC));
    }

    private static List<String> names(MindBodyLocomotion locomotion) {
        return PetPrograms.defaults(locomotion).stream().map(PetBehaviorEntry::module).toList();
    }
}
