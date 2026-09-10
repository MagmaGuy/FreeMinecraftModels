package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Collapses mirrored Bukkit packets without throttling deliberate later inputs. */
final class MagicInputDeduplicator {
    private static final long MIRROR_WINDOW_TICKS = 1L;
    private final Map<InputKey, Long> lastAcceptedTick = new HashMap<>();

    boolean accept(UUID playerId, MagicAttackKind attackKind, long tick) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(attackKind, "attackKind");
        InputKey key = new InputKey(playerId, attackKind);
        Long previous = lastAcceptedTick.get(key);
        boolean accepted = previous == null || tick - previous > MIRROR_WINDOW_TICKS || tick < previous;
        if (accepted) lastAcceptedTick.put(key, tick);
        return accepted;
    }

    void forget(UUID playerId) {
        lastAcceptedTick.keySet().removeIf(key -> key.playerId.equals(playerId));
    }

    void clear() {
        lastAcceptedTick.clear();
    }

    private record InputKey(UUID playerId, MagicAttackKind attackKind) {
    }
}
