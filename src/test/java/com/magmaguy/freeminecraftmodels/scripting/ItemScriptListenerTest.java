package com.magmaguy.freeminecraftmodels.scripting;

import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemScriptListenerTest {

    @Test
    void internalMagicMarkerNeverInspectsPaperArrowWeaponWrapper() {
        Player shooter = proxy(Player.class, new AtomicInteger(), null, null, null);
        AtomicInteger weaponLookups = new AtomicInteger();
        AbstractArrow marker = proxy(
                AbstractArrow.class,
                weaponLookups,
                shooter,
                null,
                new AssertionError("Internal markers must not expose their weapon wrapper"));
        ItemScriptListener listener = new ItemScriptListener(
                projectile -> true,
                NamespacedKey.minecraft("fmm_test_item"));

        assertDoesNotThrow(() -> listener.onProjectileHit(new ProjectileHitEvent(marker)));
        assertEquals(0, weaponLookups.get());
    }

    @Test
    void unmarkedPluginArrowWithInvalidPaperWeaponWrapperFallsBackCleanly() {
        AtomicInteger fallbackLookups = new AtomicInteger();
        PlayerInventory inventory = proxy(
                PlayerInventory.class,
                fallbackLookups,
                null,
                null,
                null);
        Player shooter = proxy(Player.class, new AtomicInteger(), null, inventory, null);
        AtomicInteger weaponLookups = new AtomicInteger();
        AbstractArrow arrow = proxy(
                AbstractArrow.class,
                weaponLookups,
                shooter,
                null,
                new NullPointerException("null NMS weapon"));
        ItemScriptListener listener = new ItemScriptListener(
                projectile -> false,
                NamespacedKey.minecraft("fmm_test_item"));

        assertDoesNotThrow(() -> listener.onProjectileHit(new ProjectileHitEvent(arrow)));
        assertEquals(1, weaponLookups.get());
        assertEquals(1, fallbackLookups.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(
            Class<T> type,
            AtomicInteger relevantLookups,
            Player shooter,
            PlayerInventory inventory,
            Throwable weaponFailure) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, arguments) -> {
                    return switch (method.getName()) {
                        case "getShooter" -> shooter;
                        case "getInventory" -> inventory;
                        case "getItemInMainHand" -> {
                            relevantLookups.incrementAndGet();
                            yield null;
                        }
                        case "getWeapon" -> {
                            relevantLookups.incrementAndGet();
                            if (weaponFailure != null) throw weaponFailure;
                            yield null;
                        }
                        case "toString" -> type.getSimpleName() + "Proxy";
                        case "hashCode" -> System.identityHashCode(instance);
                        case "equals" -> instance == arguments[0];
                        default -> defaultValue(method.getReturnType());
                    };
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
