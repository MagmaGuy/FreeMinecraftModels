package com.magmaguy.freeminecraftmodels.interaction;

import org.bukkit.event.Event;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionProtectionPolicyTest {

    @Test
    void onlyACompleteBukkitInteractDenialSuppressesInput() {
        assertTrue(InteractionProtectionPolicy.isDenied(
                Event.Result.DENY, Event.Result.DENY));
        assertFalse(InteractionProtectionPolicy.isDenied(
                Event.Result.DENY, Event.Result.DEFAULT));
        assertFalse(InteractionProtectionPolicy.isDenied(
                Event.Result.DEFAULT, Event.Result.DENY));
        assertFalse(InteractionProtectionPolicy.isDenied(
                Event.Result.ALLOW, Event.Result.ALLOW));
    }
}
