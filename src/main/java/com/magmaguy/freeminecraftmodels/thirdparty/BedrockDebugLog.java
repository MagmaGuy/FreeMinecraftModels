package com.magmaguy.freeminecraftmodels.thirdparty;

import com.magmaguy.magmacore.util.Logger;

/**
 * Conditional debug logging for the Bedrock display pipeline.
 *
 * <p>"Bedrock player sees no models" is the single hardest FMM symptom to
 * triage in production: the failure can be in any of FMM's per-viewer display
 * decision (Java vs Bedrock branch), EMG's per-viewer spawn/equipment packet
 * emission, Geyser's translation of those packets to Bedrock protocol, the
 * RSPM-produced merged Bedrock pack on the proxy, or the client itself.
 * Server logs alone don't say which.</p>
 *
 * <p><b>Runtime toggle — NOT a config option.</b> Deliberately not persisted
 * in {@code config.yml}: this is verbose enough to flood logs on a busy
 * server, and a config flag is too easy to forget on. Operators flip it
 * with {@code /fmm debug bedrock on|off}, reproduce the issue, then turn it
 * off. The state resets to {@code false} on every plugin enable.</p>
 *
 * <p>All log lines are prefixed with {@code [FMM-BedrockDebug]} so a single
 * grep against the server log surfaces the entire decision trail for one
 * Bedrock viewer's session.</p>
 */
public final class BedrockDebugLog {

    /**
     * Volatile so the toggle command and the (mostly async) packet-emit
     * paths see consistent state without contending on a lock. Read on
     * every viewer add and every bone display, so cheap reads matter.
     */
    private static volatile boolean enabled = false;

    private BedrockDebugLog() {}

    /**
     * @return whether {@code [FMM-BedrockDebug]} logging is currently on.
     * Callers should guard expensive log-context construction (string
     * concat over Player/Bone/Skeleton fields) by checking this first,
     * not just by calling {@link #log(String)} — the latter still pays
     * the cost of the string built at the call site.
     */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * Flip the toggle. Called from {@code /fmm debug bedrock on|off}.
     * Returns the resulting state so the command handler can echo it
     * back to the sender without a second read.
     */
    public static boolean setEnabled(boolean v) {
        enabled = v;
        return enabled;
    }

    /**
     * Emit a debug line. Cheap when disabled — skips formatting entirely.
     */
    public static void log(String message) {
        if (!enabled) return;
        Logger.info("[FMM-BedrockDebug] " + message);
    }
}
