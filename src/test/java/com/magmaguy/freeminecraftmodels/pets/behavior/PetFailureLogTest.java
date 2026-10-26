package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.magmacore.ai.MindRunawayException;
import com.magmaguy.shaded.luaj.vm2.LuaError;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A failed pet Mind callback, logged once per program, callback and kind, saying what still runs. */
class PetFailureLogTest {
    private static final String PROGRAM = "fmm:pet/creature/jellyfish/em_pet_jellyfish";
    private static final String TICK = "fmm:pet/drift.tick";

    private static MindFailure failure(String program, String callback, MindFailure.Kind kind, Throwable cause) {
        return new MindFailure(UUID.randomUUID(), program, callback, kind, cause);
    }

    @Test
    void aFailingCallbackIsLoggedOncePerProgramCallbackAndKindUntilCleared() {
        List<String> said = new ArrayList<>();
        PetFailureLog log = new PetFailureLog(said::add);
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
                PetFailureLog.message(failure(PROGRAM, TICK, MindFailure.Kind.CALLBACK_EXCEPTION,
                        new LuaError("pet_behaviors/drift.lua:3 attempt to index ? (a nil value)"))));
        assertEquals("The pet behavior callback " + TICK + " in " + PROGRAM + " ran past its CPU time or instruction"
                        + " limit: Lua callback exceeded 20000 instructions" + behavior + once,
                PetFailureLog.message(failure(PROGRAM, TICK, MindFailure.Kind.RUNAWAY_LIMIT_EXCEEDED,
                        new MindRunawayException("Lua callback exceeded 20000 instructions", null))));
        // A Java failure keeps its type, which says more than its message alone.
        assertEquals("The pet behavior callback " + TICK + " in " + PROGRAM + " failed: java.lang.IllegalStateException:"
                        + " no body" + behavior + once,
                PetFailureLog.message(failure(PROGRAM, TICK, MindFailure.Kind.CALLBACK_EXCEPTION,
                        new IllegalStateException("no body"))));
        // A behavior without can_continue fails its can_start under can_continue's name.
        assertEquals("The pet behavior callback fmm:pet/drift.can_continue (or can_start, which runs there when a"
                        + " behavior has no can_continue) in " + PROGRAM + " failed: boom" + behavior + once,
                PetFailureLog.message(failure(PROGRAM, "fmm:pet/drift.can_continue",
                        MindFailure.Kind.CALLBACK_EXCEPTION, new LuaError("boom"))));
        assertEquals("The pet behavior callback fmm:pet/sting_players.sense in " + PROGRAM + " failed: boom. MagmaCore"
                        + " runs that sensor again at its next interval; the pet's behaviors keep running." + once,
                PetFailureLog.message(failure(PROGRAM, "fmm:pet/sting_players.sense",
                        MindFailure.Kind.CALLBACK_EXCEPTION, new LuaError("boom"))));
    }
}
