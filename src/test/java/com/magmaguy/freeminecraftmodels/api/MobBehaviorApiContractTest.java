package com.magmaguy.freeminecraftmodels.api;

import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Other plugins look {@link MobBehaviorAPI}'s methods up by name and parameter types on the running jar, EliteMobs
 * among them, so a rename or a changed parameter breaks them without a compile error anywhere. This pins them.
 */
class MobBehaviorApiContractTest {

    private static Method publicStatic(String name, Class<?> returns, Class<?>... parameters) throws NoSuchMethodException {
        Method method = MobBehaviorAPI.class.getMethod(name, parameters);
        assertTrue(Modifier.isPublic(method.getModifiers()) && Modifier.isStatic(method.getModifiers()), name);
        assertEquals(returns, method.getReturnType(), name);
        return method;
    }

    @Test
    void theMethodsOtherPluginsLookUpKeepTheirNamesAndPlainTypes() throws NoSuchMethodException {
        assertTrue(Modifier.isPublic(MobBehaviorAPI.class.getModifiers()));
        assertEquals("com.magmaguy.freeminecraftmodels.api.MobBehaviorAPI", MobBehaviorAPI.class.getName());
        publicStatic("isAvailable", boolean.class);
        publicStatic("behaviors", List.class);
        publicStatic("overrideAI", String.class, LivingEntity.class, List.class, UUID.class, long.class, Map.class);
        publicStatic("restoreAI", boolean.class, LivingEntity.class);
        publicStatic("isAIOverridden", boolean.class, Entity.class);
    }

    @Test
    void withoutTheFeatureRunningEveryCallAnswersWithoutThrowing() {
        // No server and no Mind host here, as on a server older than 1.21.4.
        assertFalse(MobBehaviorAPI.isAvailable());
        assertEquals(List.of(), MobBehaviorAPI.behaviors());
        String refused = MobBehaviorAPI.overrideAI(null, List.of("guardian"), null, 0L, null);
        assertNotNull(refused);
        assertTrue(refused.contains("not available"), refused);
        assertFalse(MobBehaviorAPI.restoreAI(null));
        assertFalse(MobBehaviorAPI.isAIOverridden(null));
    }
}
