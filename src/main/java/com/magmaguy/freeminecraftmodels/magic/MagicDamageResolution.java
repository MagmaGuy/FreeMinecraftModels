package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackBalance;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackRequest;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackResolver;
import com.magmaguy.freeminecraftmodels.api.magic.MagicDamageApplication;
import com.magmaguy.freeminecraftmodels.api.magic.MagicResolutionOutcome;

import java.util.concurrent.atomic.AtomicBoolean;

/** Enforces exactly one damage application regardless of resolver behavior. */
public final class MagicDamageResolution {

    public MagicResolutionOutcome resolve(
            MagicAttackRequest request,
            MagicAttackResolver resolver,
            MagicDamageApplication downstream) {
        return resolveInternal(request.balance(),
                resolver == null ? null : sink -> resolver.resolve(request, sink),
                downstream);
    }

    MagicResolutionOutcome resolve(
            MagicAttackBalance balance,
            BalanceResolver resolver,
            MagicDamageApplication downstream) {
        return resolveInternal(balance, resolver, downstream);
    }

    private MagicResolutionOutcome resolveInternal(
            MagicAttackBalance balance,
            BalanceResolver resolver,
            MagicDamageApplication downstream) {
        AtomicBoolean resolved = new AtomicBoolean();
        AtomicBoolean applied = new AtomicBoolean();
        AtomicBoolean suppressed = new AtomicBoolean();
        MagicDamageApplication oneShot = damage -> {
            if (!Double.isFinite(damage) || !resolved.compareAndSet(false, true)) return;
            if (damage <= 0D) {
                suppressed.set(true);
            } else {
                downstream.apply(damage);
                applied.set(true);
            }
        };
        try {
            if (resolver == null) {
                oneShot.apply(balance.standaloneDamage());
                return applied.get() ? MagicResolutionOutcome.APPLIED : MagicResolutionOutcome.FAILED;
            }
            resolver.resolve(oneShot);
            if (applied.get()) return MagicResolutionOutcome.APPLIED;
            if (suppressed.get()) return MagicResolutionOutcome.NO_DAMAGE;
            oneShot.apply(balance.standaloneDamage());
            return applied.get()
                    ? MagicResolutionOutcome.STANDALONE_FALLBACK
                    : MagicResolutionOutcome.FAILED;
        } catch (Throwable failure) {
            if (applied.get()) return MagicResolutionOutcome.APPLIED;
            if (suppressed.get()) return MagicResolutionOutcome.NO_DAMAGE;
            if (resolved.get()) return MagicResolutionOutcome.FAILED;
            try {
                oneShot.apply(balance.standaloneDamage());
                return applied.get()
                        ? MagicResolutionOutcome.STANDALONE_FALLBACK
                        : MagicResolutionOutcome.FAILED;
            } catch (Throwable fallbackFailure) {
                return MagicResolutionOutcome.FAILED;
            }
        }
    }

    @FunctionalInterface
    interface BalanceResolver {
        void resolve(MagicDamageApplication application);
    }
}
