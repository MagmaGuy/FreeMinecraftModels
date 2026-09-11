package com.magmaguy.freeminecraftmodels.customentity.core.components;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class InteractionComponentEventOrderTest {

    @Test
    void ordinaryModeledCallbacksAreNotEventListenersThatCanRunBeforeDispatchCompletes() {
        assertFalse(Arrays.stream(InteractionComponent.InteractionComponentEvents.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("onLeftClick")
                        || method.getName().equals("onRightClick")));
    }
}
