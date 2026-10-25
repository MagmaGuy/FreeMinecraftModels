package com.magmaguy.freeminecraftmodels.pets.behavior;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PetBehaviorCatalogTest {
    @TempDir
    Path folder;
    private final List<String> warnings = new ArrayList<>(), written = new ArrayList<>();

    static String module(String name, String settings) {
        return "return ai.module { id = 'fmm:pet/" + name + "', revision = 1, settings = { " + settings + " }, "
                + "behaviors = { ai.behavior { id = 'fmm:pet/" + name + "', tick = function(c) end } } }";
    }

    private void write(String relative, String source) {
        try {
            Path file = folder.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, source);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private PetBehaviorCatalog load(List<String> defaults) {
        return PetBehaviorCatalog.load(folder, defaults, name -> {
            written.add(name);
            write(name + ".lua", module(name, "radius = 12"));
        }, warnings::add);
    }

    @Test
    void writesMissingDefaultsAndKeepsEditedOnes() {
        write("drift.lua", module("drift", "radius = 3"));
        try (PetBehaviorCatalog catalog = load(List.of("drift", "hover"))) {
            assertEquals(List.of("hover"), written);
            assertEquals(Set.of("drift", "hover"), catalog.names());
            assertEquals(3D, catalog.module("drift").orElseThrow().settings().get("radius"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void loadsUserModulesInSubfolders() {
        write("mine/my_drift.lua", module("my_drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertEquals(Set.of("my_drift"), catalog.names());
        }
    }

    @Test
    void aModulesIdMustMatchItsFileName() {
        write("my_drift.lua", module("drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("fmm:pet/my_drift"), warnings.getFirst());
    }

    @Test
    void aBrokenModuleIsSkippedAndTheRestLoad() {
        write("broken.lua", "return ai.module { id = ");
        write("hover.lua", module("hover", "height = 1"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertEquals(Set.of("hover"), catalog.names());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("broken.lua"), warnings.getFirst());
    }

    @Test
    void dependenciesLoadWhateverTheFileOrder() {
        write("a.lua", "return ai.module { id = 'fmm:pet/a', revision = 1, dependencies = { 'fmm:pet/z' } }");
        write("z.lua", "return ai.module { id = 'fmm:pet/z', revision = 1 }");
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertEquals(Set.of("a", "z"), catalog.names());
        }
        assertEquals(List.of(), warnings);
    }
}
