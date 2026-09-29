package com.magmaguy.freeminecraftmodels.customentity.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns melee claims from input observation through deferred dispatch.
 *
 * <p>The animation closes the current input association. A one-tick expiry
 * removes associations whose animation never arrives, but does not invalidate
 * work already reserved against that claim. Deferred callbacks retain their
 * claim rather than looking up whichever input the player is performing now.
 * Packet and Bukkit events do not carry a shared physical-click identifier;
 * pairing an observed input with its following animation is therefore bounded
 * to this association, not a longer cooldown.</p>
 */
final class OneTickInteractionLedger {

    // Retain the existing use-swing guard separately from melee input ownership.
    private static final int RIGHT_CLICK_SWING_SUPPRESSION_TICKS = 3;

    private final Consumer<Runnable> nextTick;
    private final Map<UUID, MeleeClaim> meleeClaims = new HashMap<>();
    private final Map<UUID, Object> observedRightClicks = new HashMap<>();
    private final Map<UUID, Object> rightClickSwingSuppressions = new HashMap<>();

    OneTickInteractionLedger(Consumer<Runnable> nextTick) {
        this.nextTick = Objects.requireNonNull(nextTick, "nextTick");
    }

    boolean claimMelee(UUID playerId, UUID targetId) {
        MeleeClaim claim = observeLeftClick(playerId);
        return reserveMelee(claim, targetId) && dispatchMelee(claim);
    }

    boolean reserveDeferredMelee(MeleeClaim claim, UUID targetId) {
        // A packet/native primary already resolved the input, including misses.
        // Its synthetic interact event must not choose another target later.
        // Native sweep routing remains eligible through claimMelee instead.
        Objects.requireNonNull(claim, "claim");
        return !claim.primary && reserveMelee(claim, targetId);
    }

    private boolean reserveMelee(MeleeClaim claim, UUID targetId) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(targetId, "targetId");
        if (claim.denied || claim.targetId != null) return false;
        claim.targetId = targetId;
        return true;
    }

    boolean dispatchMelee(MeleeClaim claim) {
        Objects.requireNonNull(claim, "claim");
        if (claim.denied || claim.targetId == null || claim.dispatched) return false;
        claim.dispatched = true;
        return true;
    }

    void denyMelee(MeleeClaim claim) {
        Objects.requireNonNull(claim, "claim").denied = true;
    }

    UUID meleeTarget(UUID playerId) {
        MeleeClaim claim = meleeClaims.get(Objects.requireNonNull(playerId, "playerId"));
        return claim == null ? null : claim.targetId;
    }

    MeleeClaim beginPrimaryLeftClick(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        MeleeClaim claim = new MeleeClaim();
        claim.primary = true;
        remember(playerId, claim);
        return claim;
    }

    MeleeClaim observeLeftClick(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        MeleeClaim claim = meleeClaims.get(playerId);
        if (claim == null) {
            claim = new MeleeClaim();
            remember(playerId, claim);
        }
        return claim;
    }

    private void remember(UUID playerId, MeleeClaim claim) {
        meleeClaims.put(playerId, claim);
        nextTick.accept(() -> meleeClaims.remove(playerId, claim));
    }

    /**
     * Closes bookkeeping even for cancelled animations. Cancellation only
     * denies an animation-only fallback, not an already allowed interact event.
     * Spigot reports synthetic air clicks as main-hand before revealing the
     * actual swing hand, so an offhand animation invalidates that pending route.
     */
    MeleeClaim finishAnimation(UUID playerId, boolean mainHand, boolean cancelled) {
        Objects.requireNonNull(playerId, "playerId");
        MeleeClaim claim = meleeClaims.get(playerId);
        if (!mainHand) {
            if (claim != null && !claim.primary) {
                claim.denied = true;
                meleeClaims.remove(playerId, claim);
            }
            return null;
        }
        if (claim != null) {
            meleeClaims.remove(playerId, claim);
            return null;
        }
        if (cancelled || rightClickSwingSuppressions.containsKey(playerId)) return null;
        return new MeleeClaim();
    }

    boolean observeRightClick(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        suppressRightClickSwing(playerId);
        Object claim = new Object();
        if (observedRightClicks.putIfAbsent(playerId, claim) != null) return false;
        nextTick.accept(() -> observedRightClicks.remove(playerId, claim));
        return true;
    }

    <T> boolean routeRightClick(
            UUID playerId,
            Supplier<Optional<T>> targetResolver,
            Consumer<T> dispatcher) {
        Objects.requireNonNull(targetResolver, "targetResolver");
        Objects.requireNonNull(dispatcher, "dispatcher");
        if (!observeRightClick(playerId)) return false;
        Objects.requireNonNull(targetResolver.get(), "resolved target")
                .ifPresent(dispatcher);
        return true;
    }

    void forget(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        meleeClaims.remove(playerId);
        observedRightClicks.remove(playerId);
        rightClickSwingSuppressions.remove(playerId);
    }

    void clear() {
        meleeClaims.clear();
        observedRightClicks.clear();
        rightClickSwingSuppressions.clear();
    }

    /**
     * Each observation replaces the player's token so a rapid follow-up click
     * restarts the window; the expiry only removes its own token, so an older
     * click's expiry can never end a newer click's suppression early.
     */
    private void suppressRightClickSwing(UUID playerId) {
        Object token = new Object();
        rightClickSwingSuppressions.put(playerId, token);
        removeAfterTicks(RIGHT_CLICK_SWING_SUPPRESSION_TICKS,
                () -> rightClickSwingSuppressions.remove(playerId, token));
    }

    private void removeAfterTicks(int remainingTicks, Runnable removal) {
        if (remainingTicks <= 1) {
            nextTick.accept(removal);
        } else {
            nextTick.accept(() -> removeAfterTicks(remainingTicks - 1, removal));
        }
    }

    static final class MeleeClaim {
        private UUID targetId;
        private boolean primary;
        private boolean denied;
        private boolean dispatched;
    }
}
