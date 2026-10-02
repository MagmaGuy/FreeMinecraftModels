package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Filmic mode sends every loaded model to every player who has its chunk loaded,
 * skipping the line-of-sight raytrace and {@code maxModelViewDistance} in
 * {@link SkeletonWatchers}. Recording mods such as ReplayMod can only replay the
 * packets the recording client received, so a model culled for that player is
 * missing or frozen in the replay.
 *
 * <p>{@code filmicMode} in config.yml is the persistent setting. {@code /fmm filmic on|off}
 * sets a temporary override that never touches the file. The override lasts until
 * the server restarts (statics reset) or until a config load reads a different
 * {@code filmicMode} value than the previous load did.</p>
 */
public final class FilmicMode {

    private static volatile Boolean commandOverride;
    private static volatile Boolean lastConfigValue;

    private FilmicMode() {
    }

    public static boolean isEnabled() {
        Boolean override = commandOverride;
        return override != null ? override : DefaultConfig.filmicMode;
    }

    public static boolean isCommandOverrideActive() {
        return commandOverride != null;
    }

    /**
     * Sets the temporary state from {@code /fmm filmic}. Choosing the value config.yml
     * already holds clears the override, so a later config change applies directly.
     */
    public static void setFromCommand(boolean enabled) {
        commandOverride = enabled == DefaultConfig.filmicMode ? null : enabled;
    }

    /**
     * Called on every config.yml load. Flipping the option discards the command
     * override; reloading with an unchanged value keeps it.
     */
    public static void onConfigLoaded(boolean configValue) {
        Boolean previous = lastConfigValue;
        if (previous != null && previous != configValue) commandOverride = null;
        lastConfigValue = configValue;
    }

    /**
     * Approximates the chunks the server has sent this player: the world's view
     * distance capped by the client's render distance, plus one chunk of margin
     * because vanilla's circular chunk area reaches slightly past that radius.
     * Sending a model outside this area is wasted, as the client can't render it.
     */
    static boolean isWithinSentChunks(Player player, Location modelLocation, int worldViewDistance) {
        int radius = Math.max(2, Math.min(player.getClientViewDistance(), worldViewDistance)) + 1;
        Location playerLocation = player.getLocation();
        return Math.abs((playerLocation.getBlockX() >> 4) - (modelLocation.getBlockX() >> 4)) <= radius
                && Math.abs((playerLocation.getBlockZ() >> 4) - (modelLocation.getBlockZ() >> 4)) <= radius;
    }
}
