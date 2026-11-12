package com.magmaguy.freeminecraftmodels.pets;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The creatures each owner's pets have given up on: one a pet's hit did not go through on (see {@link PetStrike}),
 * because it cannot be hurt, is protected where it stands, or another plugin said no. For a while that owner's pets
 * neither go after it nor sting it, whatever it does, so a pet never chases and pecks at something it cannot hurt.
 * Once the while is over they try again. Server thread only.
 */
final class PetTruce {
    private final Map<UUID, Map<UUID, Long>> spared = new HashMap<>();

    /** This owner's pets leave the creature alone until the tick {@code until}. */
    void spare(UUID owner, UUID creature, long until) {
        if (owner == null || creature == null) return;
        spared.computeIfAbsent(owner, ignored -> new HashMap<>()).put(creature, until);
    }

    /** Whether this owner's pets leave the creature alone at the tick {@code now}. */
    boolean spares(UUID owner, UUID creature, long now) {
        Map<UUID, Long> creatures = owner == null || creature == null ? null : spared.get(owner);
        if (creatures == null) return false;
        Long until = creatures.get(creature);
        if (until == null) return false;
        if (now < until) return true;
        creatures.remove(creature);
        if (creatures.isEmpty()) spared.remove(owner);
        return false;
    }

    /** The owner left: their pets' truces go with them. */
    void forget(UUID owner) {
        spared.remove(owner);
    }

    void clear() {
        spared.clear();
    }
}
