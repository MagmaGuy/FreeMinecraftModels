package com.magmaguy.freeminecraftmodels.customentity.core.components;

import com.magmaguy.easyminecraftgoals.internal.PacketEntityInteractionManager;
import com.magmaguy.easyminecraftgoals.internal.PacketInteractionContext;
import com.magmaguy.freeminecraftmodels.dataconverter.HitboxBlueprint;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    void bodiedModelKeepsClicksOnItsBodyAndObbUnlessItAsksForAClickSurface() {
        assertFalse(HitboxComponent.bodiedModelTakesInteraction(false, false),
                "an ordinary boss must not gain an axis-aligned box over its rotated OBB");
        assertTrue(HitboxComponent.bodiedModelTakesInteraction(false, true),
                "a model that asks for a click surface gets one, with or without a hitbox bone");
    }

    @Test
    void steerableMountTakesAClickSurface() {
        assertTrue(HitboxComponent.bodiedModelTakesInteraction(true, false));
    }

    @Test
    void clickSurfaceWithoutAHitboxBoneCoversTheModelsOwnBounds() {
        HitboxComponent.InteractionDimensions dimensions =
                HitboxComponent.interactionDimensions(null, 1.5D, 2.25D, true, 2D);
        assertEquals(3F, dimensions.width(), 1.0E-6F);
        assertEquals(4.5F, dimensions.height(), 1.0E-6F);
    }

    @Test
    void hitboxBoneSizesTheClickSurfaceWhenTheModelHasOne() {
        HitboxComponent.InteractionDimensions dimensions =
                HitboxComponent.interactionDimensions(hitbox(-4, 5, -6, 4, 34, 6), 9D, 9D, true, 1D);
        // The narrower of the cube's 8 and 12 pixel sides, and its 29 pixel height.
        assertEquals(.5F, dimensions.width(), 1.0E-6F);
        assertEquals(29F / 16F, dimensions.height(), 1.0E-4F);
    }

    @Test
    void modelNotClickedAsAWholeKeepsTheDefaultBoxWithoutAHitboxBone() {
        HitboxComponent.InteractionDimensions dimensions =
                HitboxComponent.interactionDimensions(null, 9D, 9D, false, 1D);
        assertEquals(1F, dimensions.width(), 1.0E-6F);
        assertEquals(2F, dimensions.height(), 1.0E-6F);
    }

    private static HitboxBlueprint hitbox(double fromX, double fromY, double fromZ,
                                          double toX, double toY, double toZ) {
        Map<String, Object> cube = new HashMap<>();
        cube.put("from", new ArrayList<>(List.of(fromX, fromY, fromZ)));
        cube.put("to", new ArrayList<>(List.of(toX, toY, toZ)));
        HashMap<String, Object> values = new HashMap<>();
        values.put("cube", cube);
        Map<String, Object> bone = new HashMap<>();
        bone.put("children", new ArrayList<>(List.of("cube")));
        return new HitboxBlueprint(bone, values, "test_model", null);
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
