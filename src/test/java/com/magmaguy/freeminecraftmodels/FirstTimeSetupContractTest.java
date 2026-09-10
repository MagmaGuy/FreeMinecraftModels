package com.magmaguy.freeminecraftmodels;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class FirstTimeSetupContractTest {

    @Test
    void guidedSetupUsesTheRegisteredInitializeCommand() {
        assertFalse(FreeMinecraftModels.NIGHTBREAK_PLUGIN_SPEC.hasPresetModes());
        assertEquals(
                "/fmm initialize",
                FreeMinecraftModels.FIRST_TIME_SETUP_SPEC.initializeCommand());
    }
}
