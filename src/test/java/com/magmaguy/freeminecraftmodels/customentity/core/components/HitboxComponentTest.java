package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.easyminecraftgoals.internal.PacketEntityInteractionManager;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionContext;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HitboxComponentTest {

    private final PacketEntityInteractionManager interactionManager =
            PacketEntityInteractionManager.getInstance();

    @AfterEach
    void clearPacketInteractionRegistrations() {
        interactionManager.shutdown();
    }

    @Test
    void propPacketInteractionUsesNarrowDimensionWithoutLargeDeadCorners() {
        assertEquals(.5F, HitboxComponent.compactPacketInteractionWidth(4D, .5D), 1.0E-6F);
        assertEquals(.5F, HitboxComponent.compactPacketInteractionWidth(.5D, 4D), 1.0E-6F);
        assertEquals(.5F, HitboxComponent.compactPacketInteractionWidth(-4D, -.5D), 1.0E-6F);
    }

    @Test
    void nativeBackingAliasAlwaysPassesToTheCancellableBukkitPath() {
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.NATIVE_BACKING,
                        PacketInteractionContext.attack()));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.NATIVE_BACKING,
                        PacketInteractionContext.interact(EquipmentSlot.HAND)));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.NATIVE_BACKING,
                        PacketInteractionContext.interactAt(EquipmentSlot.HAND)));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.NATIVE_BACKING,
                        PacketInteractionContext.interact(EquipmentSlot.OFF_HAND)));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.NATIVE_BACKING,
                        PacketInteractionContext.interactAt(EquipmentSlot.OFF_HAND)));
    }

    @Test
    void clientOnlyCarrierAliasRoutesAttacksAndMainHandUseButPassesOffHandUse() {
        assertEquals(PacketEntityInteractionManager.RoutingDecision.ROUTE,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.CLIENT_ONLY_CARRIER,
                        PacketInteractionContext.attack()));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.ROUTE,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.CLIENT_ONLY_CARRIER,
                        PacketInteractionContext.interact(EquipmentSlot.HAND)));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.ROUTE,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.CLIENT_ONLY_CARRIER,
                        PacketInteractionContext.interactAt(EquipmentSlot.HAND)));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.CLIENT_ONLY_CARRIER,
                        PacketInteractionContext.interact(EquipmentSlot.OFF_HAND)));
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                HitboxComponent.aliasRoutingDecision(
                        HitboxComponent.InteractionAliasKind.CLIENT_ONLY_CARRIER,
                        PacketInteractionContext.interactAt(EquipmentSlot.OFF_HAND)));
    }

    @Test
    void packetOnlyInteractionEntityRoutesAttackAndMainHandButIgnoresOffHandUse() {
        assertTrue(HitboxComponent.shouldRoutePacketInteraction(
                PacketInteractionContext.attack()));
        assertTrue(HitboxComponent.shouldRoutePacketInteraction(
                PacketInteractionContext.interact(EquipmentSlot.HAND)));
        assertTrue(HitboxComponent.shouldRoutePacketInteraction(
                PacketInteractionContext.interactAt(EquipmentSlot.HAND)));
        assertFalse(HitboxComponent.shouldRoutePacketInteraction(
                PacketInteractionContext.interact(EquipmentSlot.OFF_HAND)));
        assertFalse(HitboxComponent.shouldRoutePacketInteraction(
                PacketInteractionContext.interactAt(EquipmentSlot.OFF_HAND)));
    }

    @Test
    void nativeBackingTakesPrecedenceWhenItSharesTheCarrierEntityId() {
        Map<Integer, HitboxComponent.InteractionAliasKind> aliases =
                HitboxComponent.desiredInteractionAliases(41, 41);

        assertEquals(Map.of(41, HitboxComponent.InteractionAliasKind.NATIVE_BACKING), aliases);
    }

    @Test
    void aliasRegistrationReconcilesKindChangesForReusedEntityId() {
        HitboxComponent.InteractionAliasRegistry registry =
                new HitboxComponent.InteractionAliasRegistry(interactionManager);
        int entityId = 42;

        registry.reconcile(
                HitboxComponent.desiredInteractionAliases(null, entityId),
                (player, context) -> { });
        PacketEntityInteractionManager.Dispatch carrierDispatch =
                interactionManager.captureDispatch(entityId);
        assertNotNull(carrierDispatch);
        assertEquals(PacketEntityInteractionManager.RoutingDecision.ROUTE,
                carrierDispatch.prepare(PacketInteractionContext.attack()).decision());

        registry.reconcile(
                HitboxComponent.desiredInteractionAliases(entityId, entityId),
                (player, context) -> { });
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                carrierDispatch.prepare(PacketInteractionContext.attack()).decision(),
                "changing the alias kind must invalidate the old carrier registration");
        PacketEntityInteractionManager.Dispatch nativeBackingDispatch =
                interactionManager.captureDispatch(entityId);
        assertNotNull(nativeBackingDispatch);
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                nativeBackingDispatch.prepare(PacketInteractionContext.attack()).decision());
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                nativeBackingDispatch.prepare(
                        PacketInteractionContext.interact(EquipmentSlot.HAND)).decision());

        registry.reconcile(
                HitboxComponent.desiredInteractionAliases(null, entityId),
                (player, context) -> { });
        assertEquals(PacketEntityInteractionManager.RoutingDecision.PASS,
                nativeBackingDispatch.prepare(
                        PacketInteractionContext.interact(EquipmentSlot.HAND)).decision(),
                "changing back to a carrier must invalidate the native registration");
        PacketEntityInteractionManager.Dispatch replacementCarrierDispatch =
                interactionManager.captureDispatch(entityId);
        assertNotNull(replacementCarrierDispatch);
        assertEquals(PacketEntityInteractionManager.RoutingDecision.ROUTE,
                replacementCarrierDispatch.prepare(PacketInteractionContext.attack()).decision());

        registry.clear();
    }

}
