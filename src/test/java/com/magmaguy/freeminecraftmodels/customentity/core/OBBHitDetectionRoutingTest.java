package com.magmaguy.freeminecraftmodels.customentity.core;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OBBHitDetectionRoutingTest {

    private static final UUID BACKING_MODEL = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID FRONT_MODEL = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void nativeBackingHitThroughTheSameVisibleHitboxKeepsTheNativeDamage() {
        assertEquals(
                OBBHitDetection.BackingMeleeRoute.ALLOW_NATIVE,
                OBBHitDetection.resolveBackingMeleeRoute(BACKING_MODEL, BACKING_MODEL, null));
    }

    @Test
    void nativeBackingHitWithNoRaytraceResultKeepsTheAuthoritativeNativeDamage() {
        assertEquals(
                OBBHitDetection.BackingMeleeRoute.ALLOW_NATIVE,
                OBBHitDetection.resolveBackingMeleeRoute(BACKING_MODEL, null, null));
    }

    @Test
    void nearerVisibleModelOwnsTheSwingInsteadOfTheBackingEntity() {
        assertEquals(
                OBBHitDetection.BackingMeleeRoute.ROUTE_VISIBLE_MODEL,
                OBBHitDetection.resolveBackingMeleeRoute(BACKING_MODEL, FRONT_MODEL, null));
    }

    @Test
    void aSwingAlreadyRoutedThroughTheVisibleHitboxCannotDamageAgain() {
        assertEquals(
                OBBHitDetection.BackingMeleeRoute.CANCEL_NATIVE,
                OBBHitDetection.resolveBackingMeleeRoute(BACKING_MODEL, BACKING_MODEL, BACKING_MODEL));
    }

    @Test
    void anAlreadyRoutedSwingStillWinsWhenTheFallbackRaytraceIsEmpty() {
        assertEquals(
                OBBHitDetection.BackingMeleeRoute.CANCEL_NATIVE,
                OBBHitDetection.resolveBackingMeleeRoute(BACKING_MODEL, null, BACKING_MODEL));
    }

    @Test
    void fallbackClassificationObservesFinalProtectionDecision() throws NoSuchMethodException {
        EventHandler nativeMeleeHandler = OBBHitDetection.class
                .getDeclaredMethod("EntityDamageByEntityEvent", EntityDamageByEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler interactHandler = OBBHitDetection.class
                .getDeclaredMethod("onPlayerInteract", PlayerInteractEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler animationHandler = OBBHitDetection.class
                .getDeclaredMethod("onPlayerAnimation", PlayerAnimationEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, nativeMeleeHandler.priority());
        assertFalse(nativeMeleeHandler.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, interactHandler.priority());
        assertFalse(interactHandler.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, animationHandler.priority());
        assertTrue(animationHandler.ignoreCancelled());
    }

    @Test
    void nativeBackingRightClickRequiresFinalAllowedMainHandEvent() {
        assertTrue(OBBHitDetection.shouldRouteNativeRightClick(
                false, EquipmentSlot.HAND, true));
        assertFalse(OBBHitDetection.shouldRouteNativeRightClick(
                true, EquipmentSlot.HAND, true));
        assertFalse(OBBHitDetection.shouldRouteNativeRightClick(
                false, EquipmentSlot.OFF_HAND, true));
        assertFalse(OBBHitDetection.shouldRouteNativeRightClick(
                false, EquipmentSlot.HAND, false));
    }

    @Test
    void nativeBackingRightClickSeparatesClaimingFromFinalObservation() throws NoSuchMethodException {
        EventHandler routeEntity = OBBHitDetection.class
                .getDeclaredMethod("routePlayerInteractEntity", PlayerInteractEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler routeAt = OBBHitDetection.class
                .getDeclaredMethod("routePlayerInteractAtEntity", PlayerInteractAtEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler observeEntity = OBBHitDetection.class
                .getDeclaredMethod("observePlayerInteractEntity", PlayerInteractEntityEvent.class)
                .getAnnotation(EventHandler.class);
        EventHandler observeAt = OBBHitDetection.class
                .getDeclaredMethod("observePlayerInteractAtEntity", PlayerInteractAtEntityEvent.class)
                .getAnnotation(EventHandler.class);

        assertEquals(EventPriority.HIGHEST, routeEntity.priority());
        assertTrue(routeEntity.ignoreCancelled());
        assertEquals(EventPriority.HIGHEST, routeAt.priority());
        assertTrue(routeAt.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, observeEntity.priority());
        assertFalse(observeEntity.ignoreCancelled());
        assertEquals(EventPriority.MONITOR, observeAt.priority());
        assertFalse(observeAt.ignoreCancelled());
    }

    @Test
    void onlyPhysicalPlayerMeleeClaimsTheSwingBeforeTargetClassification() {
        assertTrue(OBBHitDetection.shouldObservePhysicalPlayerMelee(
                true, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertTrue(OBBHitDetection.shouldObservePhysicalPlayerMelee(
                true, EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK));
        assertFalse(OBBHitDetection.shouldObservePhysicalPlayerMelee(
                false, EntityDamageEvent.DamageCause.ENTITY_ATTACK));
        assertFalse(OBBHitDetection.shouldObservePhysicalPlayerMelee(
                true, EntityDamageEvent.DamageCause.PROJECTILE));
    }

    @Test
    void animationFallbackUsesTheActualPaperHandAndSpigotAnimationType() {
        Player player = proxy(Player.class);

        assertEquals(EquipmentSlot.HAND, OBBHitDetection.resolveAnimationHand(
                new PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING)));
        assertEquals(EquipmentSlot.OFF_HAND, OBBHitDetection.resolveAnimationHand(
                new PlayerAnimationEvent(player, PlayerAnimationType.OFF_ARM_SWING)));
        assertEquals(EquipmentSlot.HAND, OBBHitDetection.resolveAnimationHand(
                new PaperStyleArmSwingEvent(player, EquipmentSlot.HAND)));
        assertEquals(EquipmentSlot.OFF_HAND, OBBHitDetection.resolveAnimationHand(
                new PaperStyleArmSwingEvent(player, EquipmentSlot.OFF_HAND)));
    }

    static final class PaperStyleArmSwingEvent extends PlayerAnimationEvent {
        private final EquipmentSlot hand;

        private PaperStyleArmSwingEvent(Player player, EquipmentSlot hand) {
            super(player, PlayerAnimationType.ARM_SWING);
            this.hand = hand;
        }

        public EquipmentSlot getHand() {
            return hand;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, arguments) -> {
                    if (method.getName().equals("toString")) return type.getSimpleName() + "Proxy";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
                    if (method.getName().equals("equals")) return instance == arguments[0];
                    Class<?> result = method.getReturnType();
                    if (!result.isPrimitive()) return null;
                    if (result == boolean.class) return false;
                    if (result == char.class) return '\0';
                    if (result == byte.class) return (byte) 0;
                    if (result == short.class) return (short) 0;
                    if (result == int.class) return 0;
                    if (result == long.class) return 0L;
                    if (result == float.class) return 0F;
                    if (result == double.class) return 0D;
                    return null;
                });
    }

}
