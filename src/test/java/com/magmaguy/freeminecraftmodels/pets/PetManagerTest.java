package com.magmaguy.freeminecraftmodels.pets;

import com.magmaguy.freeminecraftmodels.pets.behavior.PetBehaviorCatalog;
import com.magmaguy.magmacore.ai.MindBodyLocomotion;
import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindRunawayException;
import com.magmaguy.shaded.luaj.vm2.LuaError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the pet manager that need no server: how warnings name a species' program, writing a shipped behavior
 * module and clearing what an interrupted write left, and logging a failed Mind callback once.
 */
class PetManagerTest {
    private static final String PROGRAM = "fmm:pet/creature/jellyfish/em_pet_jellyfish";
    private static final String TICK = "fmm:pet/drift.tick";
    private static final Function<String, InputStream> JAR = name -> PetManagerTest.class.getResourceAsStream("/" + name);

    @TempDir
    Path folder;

    private List<String> files() throws IOException {
        try (Stream<Path> list = Files.list(folder)) {
            return list.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static MindFailure failure(String program, String callback, MindFailure.Kind kind, Throwable cause) {
        return new MindFailure(UUID.randomUUID(), program, callback, kind, cause);
    }

    @Test
    void warningsSayWhenAListIsTheLocomotionsDefault() {
        assertEquals("jellyfish.pet.yml", PetManager.where("jellyfish.pet.yml", true, MindBodyLocomotion.FLYING,
                "em_pet_jellyfish_gold", true));
        assertEquals("jellyfish.pet.yml (default behaviors for FLYING)",
                PetManager.where("jellyfish.pet.yml", false, MindBodyLocomotion.FLYING, "em_pet_jellyfish", false));
        assertEquals("fox.pet.yml (default behaviors for GROUNDED)",
                PetManager.where("fox.pet.yml", false, MindBodyLocomotion.GROUNDED, "fox", false));
        // A variant with a list of its own adds to the defaults; warnings about its entries say whose they are.
        assertEquals("jellyfish.pet.yml (default behaviors for FLYING, plus variant em_pet_jellyfish_gold's list)",
                PetManager.where("jellyfish.pet.yml", false, MindBodyLocomotion.FLYING, "em_pet_jellyfish_gold", true));
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

        PetManager.removeLeftoverDefaults(folder, now);

        // Gone: the two old files named as writeDefault names them. One being written, a minute young, stays.
        assertEquals(List.of("drift.lua", "drift.lua.K3J2.tmp", "drift.lua.abcdefghijklmn.tmp", "drift.lua.tmp",
                "drift.lua.writing.tmp", "hover.lua.abc.tmp", "mine", "my.drift.lua.abc.tmp", "notes.tmp"), files());
        assertTrue(Files.exists(nested));
        // A folder that does not exist yet is nothing to clean.
        PetManager.removeLeftoverDefaults(folder.resolve("missing"), now);
    }

    @Test
    void aDefaultIsWrittenAsShippedAndNothingElseIsLeft() throws IOException {
        PetManager.writeDefault(folder, "drift", JAR);
        byte[] shipped;
        try (InputStream in = JAR.apply("pet_behaviors/drift.lua")) {
            shipped = in.readAllBytes();
        }
        assertArrayEquals(shipped, Files.readAllBytes(folder.resolve("drift.lua")));
        assertEquals(List.of("drift.lua"), files());
    }

    @Test
    void everyShippedDefaultCanBeWritten() throws IOException {
        for (String name : PetBehaviorCatalog.DEFAULTS) PetManager.writeDefault(folder, name, JAR);
        assertEquals(PetBehaviorCatalog.DEFAULTS.stream().map(name -> name + ".lua").sorted().toList(), files());
    }

    @Test
    void aDefaultTheJarLacksWritesNothing() throws IOException {
        UncheckedIOException failure = assertThrows(UncheckedIOException.class,
                () -> PetManager.writeDefault(folder, "drift", name -> null));
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
                () -> PetManager.writeDefault(folder, "drift", name -> breaking));
        assertEquals("No space left on device", failure.getCause().getMessage());
        assertEquals(List.of(), files());
    }

    @Test
    void aFailingCallbackIsLoggedOncePerProgramCallbackAndKindUntilCleared() {
        List<String> said = new ArrayList<>();
        PetManager.FailureLog log = new PetManager.FailureLog(said::add);
        LuaError error = new LuaError("pet_behaviors/drift.lua:3 attempt to index ? (a nil value)");
        // A hundred pets failing the same callback on every tick.
        for (int pet = 0; pet < 100; pet++) log.report(failure(PROGRAM, TICK, MindFailure.Kind.CALLBACK_EXCEPTION, error));
        assertEquals(1, said.size());
        log.report(failure(PROGRAM, "fmm:pet/drift.can_start", MindFailure.Kind.CALLBACK_EXCEPTION, error));
        log.report(failure("fmm:pet/creature/jellyfish/em_pet_jellyfish_blue", TICK, MindFailure.Kind.CALLBACK_EXCEPTION, error));
        log.report(failure(PROGRAM, TICK, MindFailure.Kind.RUNAWAY_LIMIT_EXCEEDED,
                new MindRunawayException("Lua callback exceeded 20000 instructions", null)));
        // Another message for the same callback and kind is the same failure.
        log.report(failure(PROGRAM, TICK, MindFailure.Kind.CALLBACK_EXCEPTION, new LuaError("something else")));
        assertEquals(4, said.size());
        log.clear();
        log.report(failure(PROGRAM, TICK, MindFailure.Kind.CALLBACK_EXCEPTION, error));
        assertEquals(5, said.size());
    }

    @Test
    void theMessageNamesTheCallbackAndSaysWhatStillRuns() {
        String behavior = ". MagmaCore stops that behavior, or does not start it, and tries it again from the next"
                + " tick; while it keeps failing, lower-priority behaviors that move or turn the pet may not run. The"
                + " pet's sensors keep running.";
        String once = " This is logged once per program, callback and kind of failure until the next reload.";
        assertEquals("The pet behavior callback " + TICK + " in " + PROGRAM + " failed: pet_behaviors/drift.lua:3 attempt"
                        + " to index ? (a nil value)" + behavior + once,
                PetManager.FailureLog.message(failure(PROGRAM, TICK, MindFailure.Kind.CALLBACK_EXCEPTION,
                        new LuaError("pet_behaviors/drift.lua:3 attempt to index ? (a nil value)"))));
        assertEquals("The pet behavior callback " + TICK + " in " + PROGRAM + " ran past its CPU time or instruction"
                        + " limit: Lua callback exceeded 20000 instructions" + behavior + once,
                PetManager.FailureLog.message(failure(PROGRAM, TICK, MindFailure.Kind.RUNAWAY_LIMIT_EXCEEDED,
                        new MindRunawayException("Lua callback exceeded 20000 instructions", null))));
        // A Java failure keeps its type, which says more than its message alone.
        assertEquals("The pet behavior callback " + TICK + " in " + PROGRAM + " failed: java.lang.IllegalStateException:"
                        + " no body" + behavior + once,
                PetManager.FailureLog.message(failure(PROGRAM, TICK, MindFailure.Kind.CALLBACK_EXCEPTION,
                        new IllegalStateException("no body"))));
        // A behavior without can_continue fails its can_start under can_continue's name.
        assertEquals("The pet behavior callback fmm:pet/drift.can_continue (or can_start, which runs there when a"
                        + " behavior has no can_continue) in " + PROGRAM + " failed: boom" + behavior + once,
                PetManager.FailureLog.message(failure(PROGRAM, "fmm:pet/drift.can_continue",
                        MindFailure.Kind.CALLBACK_EXCEPTION, new LuaError("boom"))));
        assertEquals("The pet behavior callback fmm:pet/sting_players.sense in " + PROGRAM + " failed: boom. MagmaCore"
                        + " runs that sensor again at its next interval; the pet's behaviors keep running." + once,
                PetManager.FailureLog.message(failure(PROGRAM, "fmm:pet/sting_players.sense",
                        MindFailure.Kind.CALLBACK_EXCEPTION, new LuaError("boom"))));
    }
}
