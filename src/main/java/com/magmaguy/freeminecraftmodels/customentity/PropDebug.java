package com.magmaguy.freeminecraftmodels.customentity;

/**
 * Runtime toggle for prop diagnostic checks (currently the stacked-props
 * proximity scan in {@link PropEntity}). Same shape as
 * {@link com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog}:
 * deliberately NOT a config option, resets to off on restart, flipped via
 * {@code /fmm debug props on|off}.
 */
public final class PropDebug {

    // Volatile: the toggle command runs on the main thread but chunk entity-load
    // sweeps can observe it from callback contexts.
    private static volatile boolean enabled = false;

    private PropDebug() {
    }

    public static boolean enabled() {
        return enabled;
    }

    public static boolean setEnabled(boolean value) {
        enabled = value;
        return enabled;
    }
}
