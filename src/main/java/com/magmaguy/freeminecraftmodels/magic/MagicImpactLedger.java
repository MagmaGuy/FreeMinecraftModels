package com.magmaguy.freeminecraftmodels.magic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** One-claim registry shared by every Bukkit collision surface for a marker projectile. */
final class MagicImpactLedger<T> {
    private final Map<UUID, T> active = new HashMap<>();

    void register(UUID projectileId, T flight) {
        Objects.requireNonNull(projectileId, "projectileId");
        Objects.requireNonNull(flight, "flight");
        if (active.putIfAbsent(projectileId, flight) != null)
            throw new IllegalStateException("Magic marker is already registered: " + projectileId);
    }

    T lookup(UUID projectileId) {
        return active.get(projectileId);
    }

    boolean claim(UUID projectileId, T expected) {
        return active.remove(projectileId, expected);
    }

    List<T> snapshot() {
        return List.copyOf(new ArrayList<>(active.values()));
    }
}
