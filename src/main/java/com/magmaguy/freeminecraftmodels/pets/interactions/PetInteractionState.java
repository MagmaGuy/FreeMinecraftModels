package com.magmaguy.freeminecraftmodels.pets.interactions;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.pets.PetControl;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * What the interactions remember about one pet body. The world pass writes it and the behaviors read it, all on the
 * server thread. Entries go with their pet: the map holds its controls weakly.
 */
final class PetInteractionState {
    private static final Map<PetControl, PetInteractionState> STATES = new WeakHashMap<>();

    boolean raining;
    double scale = Double.NaN;
    long nextRainCheck;
    long nextGlowCheck;
    long nextMobSting;
    long nextDrizzle;
    final Map<UUID, Long> stingReadyAt = new HashMap<>();
    // The model the glow was last applied to; a respawned body brings a new model that needs it again.
    DynamicEntity glowModel;
    boolean glowAnimating;

    private PetInteractionState() {
    }

    static PetInteractionState of(PetControl control) {
        return STATES.computeIfAbsent(control, ignored -> new PetInteractionState());
    }
}
