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
    void aDefaultMovedIntoASubfolderIsNotWrittenAgain() {
        write("mine/drift.lua", module("drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of("drift"))) {
            assertEquals(List.of(), written);
            assertEquals(Set.of("drift"), catalog.names());
            assertEquals(5D, catalog.module("drift").orElseThrow().settings().get("radius"));
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void loadsUserModulesInSubfolders() {
        write("mine/my_drift.lua", module("my_drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertEquals(Set.of("my_drift"), catalog.names());
        }
        assertEquals(List.of(), warnings);
    }

    @Test
    void aFileNameOutsideTheNameRuleIsSkipped() {
        write("my drift.lua", module("my_drift", "radius = 5"));
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("my drift.lua") && warnings.getFirst().contains("letters"),
                warnings.getFirst());
    }

    @Test
    void aFileNotSavedAsUtf8IsSkipped() throws IOException {
        Files.write(folder.resolve("latin.lua"), new byte[]{'-', '-', ' ', (byte) 0xE9, '\n'});
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("UTF-8"), warnings.getFirst());
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
        assertTrue(warnings.getFirst().contains("broken.lua") && warnings.getFirst().contains("line 1"),
                warnings.getFirst());
    }

    @Test
    void aModuleNeedingOneThatFailedSaysSo() {
        write("a.lua", "return ai.module { id = 'fmm:pet/a', revision = 1, dependencies = { 'fmm:pet/broken' } }");
        write("broken.lua", "return ai.module { id = ");
        write("c1.lua", "return ai.module { id = 'fmm:pet/c1', revision = 1, dependencies = { 'fmm:pet/c2' } }");
        write("c2.lua", "return ai.module { id = 'fmm:pet/c2', revision = 1, dependencies = { 'fmm:pet/c1' } }");
        try (PetBehaviorCatalog catalog = load(List.of())) {
            assertTrue(catalog.names().isEmpty());
        }
        assertEquals(4, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).startsWith(folder.getFileName() + "/broken.lua: "), warnings.get(0));
        assertTrue(warnings.get(1).contains("fmm:pet/broken (it failed to load too, see above)"), warnings.get(1));
        assertTrue(warnings.get(2).contains("fmm:pet/c2 (it failed to load too, see below)"), warnings.get(2));
        assertTrue(warnings.get(3).contains("fmm:pet/c1 (it failed to load too, see above)"), warnings.get(3));
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
