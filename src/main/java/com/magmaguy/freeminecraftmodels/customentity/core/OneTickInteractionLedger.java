package com.magmaguy.freeminecraftmodels.customentity.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Owns the input claims used to classify an arm swing exactly once.
 *
 * <p>Route-gating claims (melee ownership, the right-click gate) last one tick
 * so back-to-back genuine clicks are never swallowed. The arm-swing fallback
 * suppression written by {@link #observeLeftClick} and {@link #observeRightClick}
 * deliberately outlives them: a click's paired swing packet is processed in a
 * tick's packet phase, which runs after the scheduler heartbeat that would
 * remove a one-tick entry, so under load the swing can arrive one or two ticks
 * after the input that owns it. Suppressing the fallback for
 * {@link #SWING_FALLBACK_SUPPRESSION_TICKS} ticks keeps that late swing from
 * being reclassified as a second click.</p>
 */
final class OneTickInteractionLedger {

    /**
     * How many ticks an observed click keeps suppressing the arm-swing
     * fallback. A suppression written at the heartbeat of tick N is removed at
     * the heartbeat of tick N+3, so it covers swings processed in the packet
     * phases of ticks N through N+2 — one tick of slip was observed in the
     * wild, plus one tick of headroom. Swing-only clicks never write this
     * suppression, so consecutive fallback-routed clicks stay unaffected.
     */
    private static final int SWING_FALLBACK_SUPPRESSION_TICKS = 3;

    private final Consumer<Runnable> nextTick;
    private final Map<UUID, MeleeClaim> meleeClaims = new HashMap<>();
    private final Map<UUID, Object> observedRightClicks = new HashMap<>();
    private final Map<UUID, Object> swingFallbackSuppressions = new HashMap<>();

    OneTickInteractionLedger(Consumer<Runnable> nextTick) {
        this.nextTick = Objects.requireNonNull(nextTick, "nextTick");
    }

    boolean claimMelee(UUID playerId, UUID targetId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(targetId, "targetId");
        MeleeClaim claim = new MeleeClaim(targetId);
        if (meleeClaims.putIfAbsent(playerId, claim) != null) return false;
        nextTick.accept(() -> meleeClaims.remove(playerId, claim));
        return true;
    }

    UUID meleeTarget(UUID playerId) {
        MeleeClaim claim = meleeClaims.get(Objects.requireNonNull(playerId, "playerId"));
        return claim == null ? null : claim.targetId;
    }

    void observeLeftClick(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        suppressSwingFallback(playerId);
    }

    boolean observeRightClick(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        suppressSwingFallback(playerId);
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

    boolean shouldRouteAnimation(UUID playerId, String animationType) {
        Objects.requireNonNull(playerId, "playerId");
        return "ARM_SWING".equals(animationType)
                && !meleeClaims.containsKey(playerId)
                && !swingFallbackSuppressions.containsKey(playerId);
    }

    void forget(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        meleeClaims.remove(playerId);
        observedRightClicks.remove(playerId);
        swingFallbackSuppressions.remove(playerId);
    }

    void clear() {
        meleeClaims.clear();
        observedRightClicks.clear();
        swingFallbackSuppressions.clear();
    }

    /**
     * Each observation replaces the player's token so a rapid follow-up click
     * restarts the window; the expiry only removes its own token, so an older
     * click's expiry can never end a newer click's suppression early.
     */
    private void suppressSwingFallback(UUID playerId) {
        Object token = new Object();
        swingFallbackSuppressions.put(playerId, token);
        removeAfterTicks(SWING_FALLBACK_SUPPRESSION_TICKS,
                () -> swingFallbackSuppressions.remove(playerId, token));
    }

    private void removeAfterTicks(int remainingTicks, Runnable removal) {
        if (remainingTicks <= 1) {
            nextTick.accept(removal);
        } else {
            nextTick.accept(() -> removeAfterTicks(remainingTicks - 1, removal));
        }
    }

    private static final class MeleeClaim {
        private final UUID targetId;

        private MeleeClaim(UUID targetId) {
            this.targetId = targetId;
        }
    }
}
