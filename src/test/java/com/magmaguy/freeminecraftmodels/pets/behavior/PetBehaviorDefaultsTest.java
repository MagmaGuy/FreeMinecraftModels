package com.magmaguy.freeminecraftmodels.pets.behavior;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Writing a shipped behavior module into the folder, and clearing what an interrupted write left there. */
class PetBehaviorDefaultsTest {
    private static final Function<String, InputStream> JAR =
            name -> PetBehaviorDefaultsTest.class.getResourceAsStream("/" + name);

    @TempDir
    Path folder;

    private List<String> files() throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    /**
     * outdated-config-keys.yml archives a server's copy of a module whose text is one of the hashes listed for its
     * file, and the module is then written again. A shipped module listed there would be archived and rewritten on
     * every start, so none may be: migrate.lua came back after its earlier versions were listed.
     */
    @Test
    void noShippedModuleIsListedForArchiving() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration rules = new org.bukkit.configuration.file.YamlConfiguration();
        try (InputStream in = JAR.apply("outdated-config-keys.yml")) {
            rules.loadFromString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        java.util.Map<String, java.util.Set<String>> listed = new java.util.HashMap<>();
        for (java.util.Map<?, ?> rule : rules.getMapList("pet_behaviors"))
            for (Object file : (List<?>) rule.get("files"))
                for (Object hash : (List<?>) rule.get("sha256"))
                    listed.computeIfAbsent(file.toString(), ignored -> new java.util.HashSet<>()).add(hash.toString());
        assertTrue(listed.containsKey("migrate.lua"), "the earlier migrate.lua versions are still archived");
        // The tester builds' march.lua ran every colony's routine; the routine is off by default now.
        assertTrue(listed.get("march.lua").contains("8a4cd22fa31f368d73cfa58268b110a6c946e76f6fce159bb6d760422e2bece5"),
                "the march.lua with routine on by default is archived");
        java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
        for (String name : PetBehaviorCatalog.DEFAULTS) {
            String text = PetBehaviorDefaults.text(name, JAR);
            assertTrue(text != null, "the jar ships " + name + ".lua");
            // The rule hashes a file's bytes with its carriage returns removed.
            String hash = java.util.HexFormat.of().formatHex(sha256.digest(
                    text.replace("\r", "").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertTrue(!listed.getOrDefault(name + ".lua", java.util.Set.of()).contains(hash),
                    "the shipped " + name + ".lua is listed for archiving, so it would be replaced on every start");
        }
    }

    @Test
    void onlyOldLeftoverDefaultsAreRemoved() throws IOException {
        long now = System.currentTimeMillis();
        FileTime old = FileTime.fromMillis(now - 120_000L), recent = FileTime.fromMillis(now - 10_000L);
        for (String name : List.of("drift.lua.k3j2h1abc.tmp", "night_glow.lua.0.tmp", "drift.lua.writing.tmp",
                "drift.lua", "notes.tmp", "drift.lua.tmp", "drift.lua.K3J2.tmp", "my.drift.lua.abc.tmp",
                "drift.lua.abcdefghijklmn.tmp")) {
            Files.writeString(folder.resolve(name), "-- kept unless a leftover");
            Files.setLastModifiedTime(folder.resolve(name), name.contains("writing") ? recent : old);
        }
        Path directory = Files.createDirectory(folder.resolve("hover.lua.abc.tmp"));
        Files.setLastModifiedTime(directory, old);
        Path nested = Files.createDirectory(folder.resolve("mine")).resolve("swim.lua.abc.tmp");
        Files.writeString(nested, "-- below the folder");
        Files.setLastModifiedTime(nested, old);

        PetBehaviorDefaults.removeLeftovers(folder, now);

        // Gone: the two old files named as write names them. One being written, a minute young, stays.
        assertEquals(List.of("drift.lua", "drift.lua.K3J2.tmp", "drift.lua.abcdefghijklmn.tmp", "drift.lua.tmp",
                "drift.lua.writing.tmp", "hover.lua.abc.tmp", "mine", "my.drift.lua.abc.tmp", "notes.tmp"), files());
        assertTrue(Files.exists(nested));
        // A folder that does not exist yet is nothing to clean.
        PetBehaviorDefaults.removeLeftovers(folder.resolve("missing"), now);
    }

    @Test
    void aDefaultIsWrittenAsShippedAndNothingElseIsLeft() throws IOException {
        PetBehaviorDefaults.write(folder, "drift", JAR);
        byte[] shipped;
        try (InputStream in = JAR.apply("pet_behaviors/drift.lua")) {
            shipped = in.readAllBytes();
        }
        assertArrayEquals(shipped, Files.readAllBytes(folder.resolve("drift.lua")));
        assertEquals(List.of("drift.lua"), files());
    }

    @Test
    void everyShippedDefaultCanBeWritten() throws IOException {
        for (String name : PetBehaviorCatalog.DEFAULTS) PetBehaviorDefaults.write(folder, name, JAR);
        assertEquals(PetBehaviorCatalog.DEFAULTS.stream().map(name -> name + ".lua").sorted().toList(), files());
    }

    @Test
    void aDefaultTheJarLacksWritesNothing() throws IOException {
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> PetBehaviorDefaults.write(folder, "drift", name -> null));
        assertInstanceOf(FileNotFoundException.class, failure.getCause());
        assertEquals(List.of(), files());
    }

    @Test
    void aWriteThatFailsHalfwayLeavesNoFile() throws IOException {
        InputStream breaking = new InputStream() {
            private int read;

            @Override
            public int read() throws IOException {
                if (read++ >= 10) throw new IOException("No space left on device");
                return '-';
            }
        };
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> PetBehaviorDefaults.write(folder, "drift", name -> breaking));
        assertEquals("No space left on device", failure.getCause().getMessage());
        assertEquals(List.of(), files());
    }
}
