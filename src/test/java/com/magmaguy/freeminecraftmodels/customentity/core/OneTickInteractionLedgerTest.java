package com.magmaguy.freeminecraftmodels.customentity.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OneTickInteractionLedgerTest {

    private static final UUID PLAYER =
            UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET =
            UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OTHER_TARGET =
            UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void deniedLeftClickCannotBeResurrectedByTheFollowingArmSwing() {
        ManualNextTick nextTick = new ManualNextTick();
        OneTickInteractionLedger ledger = new OneTickInteractionLedger(nextTick::schedule);

        assertTrue(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));

        ledger.observeLeftClick(PLAYER);

        // The paired swing packet can be processed one or two ticks after the
        // click that owns it, so the fallback stays suppressed across those
        // boundaries and only reopens once no paired swing can still arrive.
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        nextTick.advance();
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        nextTick.advance();
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        nextTick.advance();
        assertTrue(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
    }

    @Test
    void aRoutedPacketAttackOwnsItsArmSwingAcrossTheTickBoundary() {
        ManualNextTick nextTick = new ManualNextTick();
        OneTickInteractionLedger ledger = new OneTickInteractionLedger(nextTick::schedule);

        // Scheduler-heartbeat tick N: the intercepted ATTACK packet routes the
        // click (observe + claim, mirroring routePacketLeftClick).
        ledger.observeLeftClick(PLAYER);
        assertTrue(ledger.claimMelee(PLAYER, TARGET));

        // Tick N+1's heartbeat expires the melee claim; under load the paired
        // swing packet is only processed afterwards, in that tick's packet
        // phase. The swing must still be recognized as already routed.
        nextTick.advance();
        assertNull(ledger.meleeTarget(PLAYER));
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));

        // The suppression is not a route gate: the player's next physical
        // click one tick later must still claim and route normally.
        assertTrue(ledger.claimMelee(PLAYER, TARGET));
    }

    @Test
    void aFreshClickRefreshesTheSwingSuppressionInsteadOfInheritingTheOldWindow() {
        ManualNextTick nextTick = new ManualNextTick();
        OneTickInteractionLedger ledger = new OneTickInteractionLedger(nextTick::schedule);

        ledger.observeLeftClick(PLAYER);
        nextTick.advance();
        nextTick.advance();
        ledger.observeLeftClick(PLAYER);

        // The first click's expiry fires here; it must not end the second
        // click's freshly started window.
        nextTick.advance();
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        nextTick.advance();
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        nextTick.advance();
        assertTrue(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
    }

    @Test
    void packetRightClickStaysObservedWhenTargetResolutionMisses() {
        ManualNextTick nextTick = new ManualNextTick();
        OneTickInteractionLedger ledger = new OneTickInteractionLedger(nextTick::schedule);
        List<String> dispatchedTargets = new ArrayList<>();

        assertTrue(ledger.routeRightClick(
                PLAYER, Optional::<String>empty, dispatchedTargets::add));

        assertTrue(dispatchedTargets.isEmpty());
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        assertFalse(ledger.routeRightClick(
                PLAYER, () -> Optional.of("late-target"), dispatchedTargets::add));
        assertTrue(dispatchedTargets.isEmpty());
        nextTick.advance();
        // The use-swing paired with the right click can process a tick late,
        // so the arm-swing fallback stays owned across the boundary...
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));

        // ...while the right-click route gate has already reopened for the
        // player's next genuine right click.
        assertTrue(ledger.routeRightClick(
                PLAYER, () -> Optional.of("resolved-target"), dispatchedTargets::add));
        assertEquals(List.of("resolved-target"), dispatchedTargets);
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
    }

    @Test
    void routedMeleeOwnsTheFollowingAnimationUntilItsExactClaimExpires() {
        ManualNextTick nextTick = new ManualNextTick();
        OneTickInteractionLedger ledger = new OneTickInteractionLedger(nextTick::schedule);

        assertTrue(ledger.claimMelee(PLAYER, TARGET));
        assertEquals(TARGET, ledger.meleeTarget(PLAYER));
        assertFalse(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        assertFalse(ledger.claimMelee(PLAYER, OTHER_TARGET));

        nextTick.advance();

        assertNull(ledger.meleeTarget(PLAYER));
        assertTrue(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
    }

    @Test
    void forgettingAPlayerClearsEveryInputClaimImmediately() {
        ManualNextTick nextTick = new ManualNextTick();
        OneTickInteractionLedger ledger = new OneTickInteractionLedger(nextTick::schedule);
        ledger.claimMelee(PLAYER, TARGET);
        ledger.observeRightClick(PLAYER);
        ledger.observeLeftClick(PLAYER);

        ledger.forget(PLAYER);

        assertNull(ledger.meleeTarget(PLAYER));
        assertTrue(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
    }

    @Test
    void clearingTheLedgerDropsClaimsForEveryPlayer() {
        ManualNextTick nextTick = new ManualNextTick();
        OneTickInteractionLedger ledger = new OneTickInteractionLedger(nextTick::schedule);
        UUID otherPlayer = UUID.fromString("00000000-0000-0000-0000-000000000004");
        ledger.claimMelee(PLAYER, TARGET);
        ledger.observeRightClick(otherPlayer);

        ledger.clear();

        assertTrue(ledger.shouldRouteAnimation(PLAYER, "ARM_SWING"));
        assertTrue(ledger.shouldRouteAnimation(otherPlayer, "ARM_SWING"));
    }

    @Test
    void offHandAnimationIsNeverAnAttackFallback() {
        OneTickInteractionLedger ledger =
                new OneTickInteractionLedger(task -> { });

        assertFalse(ledger.shouldRouteAnimation(PLAYER, "OFF_ARM_SWING"));
    }

    private static final class ManualNextTick {
        private final Queue<Runnable> tasks = new ArrayDeque<>();

        void schedule(Runnable task) {
            tasks.add(task);
        }

        void advance() {
            int due = tasks.size();
            for (int i = 0; i < due; i++) tasks.remove().run();
        }
    }
}
