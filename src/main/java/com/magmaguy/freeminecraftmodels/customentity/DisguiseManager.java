package com.magmaguy.freeminecraftmodels.customentity;

import org.bukkit.entity.Player;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for active player disguises. Keyed by the disguised
 * player's UUID. All disguise creation / removal must go through this class so
 * lifecycle is consistent regardless of trigger (command, API, listener).
 */
public final class DisguiseManager {
    private static final Map<UUID, PlayerDisguiseEntity> disguises = new ConcurrentHashMap<>();

    private DisguiseManager() {}

    /**
     * Disguises {@code player} as the model with {@code modelID}. If the player
     * is already disguised, the previous disguise is removed first so the new
     * one replaces it cleanly.
     *
     * @return {@code true} on success, {@code false} if the model ID is unknown.
     */
    public static boolean disguise(Player player, String modelID) {
        undisguise(player); // replace cleanly if already disguised
        PlayerDisguiseEntity entity = PlayerDisguiseEntity.create(modelID, player);
        if (entity == null) return false;
        disguises.put(player.getUniqueId(), entity);
        return true;
    }

    /**
     * Undisguises {@code player} if currently disguised. No-op otherwise.
     *
     * @return {@code true} if a disguise was removed, {@code false} if there
     *         was nothing to remove.
     */
    public static boolean undisguise(Player player) {
        PlayerDisguiseEntity entity = disguises.remove(player.getUniqueId());
        if (entity == null) return false;
        entity.remove();
        return true;
    }

    public static boolean isDisguised(Player player) {
        return disguises.containsKey(player.getUniqueId());
    }

    @Nullable
    public static PlayerDisguiseEntity getDisguise(Player player) {
        return disguises.get(player.getUniqueId());
    }

    public static Collection<PlayerDisguiseEntity> getAll() {
        return Collections.unmodifiableCollection(disguises.values());
    }

    /**
     * Plugin-disable / reload cleanup: remove every active disguise.
     */
    public static void shutdown() {
        for (PlayerDisguiseEntity entity : disguises.values()) {
            entity.remove();
        }
        disguises.clear();
    }
}
