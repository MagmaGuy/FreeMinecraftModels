package com.magmaguy.freeminecraftmodels.interaction;

import org.bukkit.event.Event;

/**
 * Interprets Bukkit's split interaction results without treating ordinary air
 * interactions as protection cancellations.
 */
public final class InteractionProtectionPolicy {
    private InteractionProtectionPolicy() {
    }

    public static boolean isDenied(
            Event.Result useInteractedBlock,
            Event.Result useItemInHand) {
        return useInteractedBlock == Event.Result.DENY
                && useItemInHand == Event.Result.DENY;
    }
}
