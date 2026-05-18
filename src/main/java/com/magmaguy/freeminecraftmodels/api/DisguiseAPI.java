package com.magmaguy.freeminecraftmodels.api;

import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Public entry point for the FMM disguise feature. Third-party plugins
 * (EliteMobs, scripts, etc.) should call this class rather than
 * {@link DisguiseManager} directly so internal refactors don't break them.
 */
public final class DisguiseAPI {

    private DisguiseAPI() {}

    /**
     * Disguises {@code player} as the model with {@code modelID}. If the
     * player is already disguised, the previous disguise is removed first
     * so the new one replaces it cleanly.
     *
     * @return {@code true} on success; {@code false} if the model ID is
     *         not loaded.
     */
    public static boolean disguise(Player player, String modelID) {
        return DisguiseManager.disguise(player, modelID);
    }

    /**
     * Undisguises {@code player} if currently disguised.
     *
     * @return {@code true} if a disguise was removed.
     */
    public static boolean undisguise(Player player) {
        return DisguiseManager.undisguise(player);
    }

    public static boolean isDisguised(Player player) {
        return DisguiseManager.isDisguised(player);
    }

    /**
     * @return the model ID the player is currently disguised as, or
     *         {@code null} if not disguised.
     */
    @Nullable
    public static String getDisguiseModelID(Player player) {
        PlayerDisguiseEntity entity = DisguiseManager.getDisguise(player);
        return entity != null ? entity.getEntityID() : null;
    }

    /**
     * @return a snapshot of all currently disguised players.
     */
    public static Collection<Player> getDisguisedPlayers() {
        return DisguiseManager.getAll().stream()
                .map(PlayerDisguiseEntity::getDisguisedPlayer)
                .collect(Collectors.toUnmodifiableList());
    }
}
