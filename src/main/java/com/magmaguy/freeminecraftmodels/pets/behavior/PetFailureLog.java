package com.magmaguy.freeminecraftmodels.pets.behavior;

import com.magmaguy.magmacore.ai.MindFailure;
import com.magmaguy.shaded.luaj.vm2.LuaError;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Logs a failed pet Mind callback once per program, callback and kind of failure until {@link #clear}, which each
 * load calls: a module failing on every tick of a hundred pets says so once. MagmaCore stops a behavior whose callback
 * failed, or does not start it, and tries it again from the next tick. A behavior that keeps passing can_start and
 * failing after it holds its controls each tick it runs, so lower-priority behaviors that move or turn the pet may
 * never run meanwhile: a failing drift tick keeps hover from running. A failed sensor runs again at its next interval.
 * Server thread only.
 */
public final class PetFailureLog {
    private final Consumer<String> sink;
    private final Set<String> said = new HashSet<>();

    public PetFailureLog(Consumer<String> sink) {
        this.sink = sink;
    }

    public void report(MindFailure failure) {
        if (said.add(failure.programIdentifier() + "\n" + failure.callbackIdentifier() + "\n" + failure.kind()))
            sink.accept(message(failure));
    }

    public void clear() {
        said.clear();
    }

    static String message(MindFailure failure) {
        boolean runaway = failure.kind() == MindFailure.Kind.RUNAWAY_LIMIT_EXCEEDED;
        Throwable cause = failure.cause();
        // A Lua error's message names the file and line, and a runaway's the limit; anything else needs its type.
        String reason = cause == null ? "no reason was given"
                : (cause instanceof LuaError || runaway) && cause.getMessage() != null ? cause.getMessage()
                : cause.toString();
        String what = runaway ? "ran past its CPU time or instruction limit" : "failed";
        String callback = failure.callbackIdentifier();
        // MagmaCore runs can_start in place of a missing can_continue, under can_continue's name.
        String also = callback.endsWith(".can_continue") ? " (or can_start, which runs there when a behavior has"
                + " no can_continue)" : "";
        String outcome = callback.endsWith(".sense")
                ? "MagmaCore runs that sensor again at its next interval; the pet's behaviors keep running."
                : "MagmaCore stops that behavior, or does not start it, and tries it again from the next tick;"
                + " while it keeps failing, lower-priority behaviors that move or turn the pet may not run. The"
                + " pet's sensors keep running.";
        return "The pet behavior callback " + callback + also + " in " + failure.programIdentifier() + " " + what
                + ": " + reason + ". " + outcome + " This is logged once per program, callback and kind of failure"
                + " until the next reload.";
    }
}
