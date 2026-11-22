package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackCast;
import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Runs addon attack behaviors and owns their casts until they end. The runtime stays the damage
 * owner: a cast handle only forwards to it.
 */
final class MagicBehaviorCasts {

    /** What a cast handle needs from the runtime. */
    interface Host {
        boolean targetEligible(MagicCast cast, LivingEntity target);

        boolean applyDamage(MagicCast cast, LivingEntity target, double powerFactor);

        List<LivingEntity> acquireTargets(MagicCast cast, double range, double aimAssistDegrees, int limit);
    }

    /** Task scheduling in the behavior owner's name. */
    @FunctionalInterface
    interface Scheduler {
        /** @param periodTicks 0 for a single run; @return cancels the scheduled task */
        Runnable schedule(Plugin owner, long delayTicks, long periodTicks, Runnable task);
    }

    static final Scheduler BUKKIT = (owner, delayTicks, periodTicks, task) -> {
        var scheduler = owner.getServer().getScheduler();
        var scheduled = periodTicks > 0
                ? scheduler.runTaskTimer(owner, task, delayTicks, periodTicks)
                : scheduler.runTaskLater(owner, task, delayTicks);
        return scheduled::cancel;
    };

    private final Host host;
    private final Scheduler scheduler;
    private final Consumer<String> warnings;
    private final Set<ActiveCast> active = new LinkedHashSet<>();
    private final Set<NamespacedKey> failureWarned = new HashSet<>();

    MagicBehaviorCasts(Host host, Scheduler scheduler, Consumer<String> warnings) {
        this.host = Objects.requireNonNull(host, "host");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.warnings = Objects.requireNonNull(warnings, "warnings");
    }

    /** @return whether the behavior cast the attack, which is what starts the reload */
    boolean launch(MagicAttackBehaviorRegistry.Registration registration, MagicCast cast, LivingEntity clickedTarget) {
        ActiveCast handle = new ActiveCast(registration, cast, clickedTarget);
        active.add(handle);
        handle.running++;
        boolean launched;
        try {
            launched = registration.behavior().cast(handle);
        } catch (RuntimeException | LinkageError failure) {
            handle.fail(failure);
            // A behavior that broke after landing a hit has still spent the attack.
            return handle.impacted;
        } finally {
            handle.running--;
        }
        if (!launched && !handle.impacted) {
            handle.end();
            return false;
        }
        handle.endIfIdle();
        return true;
    }

    void endAll() {
        endIf(cast -> true);
    }

    void endFor(Player caster) {
        UUID casterId = caster.getUniqueId();
        endIf(cast -> cast.cast.owner().getUniqueId().equals(casterId));
    }

    void endFor(MagicAttackBehaviorRegistry.Registration registration) {
        endIf(cast -> cast.registration == registration);
    }

    int activeCount() {
        return active.size();
    }

    private void endIf(Predicate<ActiveCast> filter) {
        for (ActiveCast cast : new ArrayList<>(active)) if (filter.test(cast)) cast.end();
    }

    private static void requireServerThread() {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Magic casts require the server thread");
    }

    private final class ActiveCast implements MagicAttackCast {
        private final MagicAttackBehaviorRegistry.Registration registration;
        private final MagicCast cast;
        private final LivingEntity clickedTarget;
        private final Set<ScheduledTask> tasks = new LinkedHashSet<>();
        private final List<Runnable> cleanups = new ArrayList<>();
        /** Behavior code currently on the stack; the cast never ends itself as idle underneath it. */
        private int running;
        private boolean impacted;
        private boolean ended;

        private ActiveCast(MagicAttackBehaviorRegistry.Registration registration, MagicCast cast, LivingEntity clickedTarget) {
            this.registration = registration;
            this.cast = cast;
            this.clickedTarget = clickedTarget;
        }

        @Override public UUID attackId() { return cast.attackId(); }
        @Override public NamespacedKey behaviorId() { return registration.id(); }
        @Override public Player caster() { return cast.owner(); }
        @Override public ItemStack weapon() { return cast.weapon(); }
        @Override public String weaponId() { return cast.definition().itemId(); }
        @Override public MagicWeaponKind weaponKind() { return cast.definition().kind(); }
        @Override public MagicAttackKind attackKind() { return cast.attackKind(); }
        @Override public LivingEntity clickedTarget() { return clickedTarget; }
        @Override public double basePower() { return cast.definition().basePower(cast.attackKind()); }
        @Override public int reloadTicks() { return cast.definition().reloadTicks(cast.attackKind()); }
        @Override public double range() { return cast.definition().traits().range(); }
        @Override public double projectileSpeed() { return cast.definition().traits().projectileSpeed(); }
        @Override public int travelTicks() { return cast.definition().traits().travelTicks(); }
        @Override public double impactRadius() { return cast.definition().traits().impactRadius(); }
        @Override public double aimAssistDegrees() { return cast.definition().traits().aimAssistDegrees(); }
        @Override public int missileCount() { return cast.definition().traits().missileCount(); }
        @Override public int ignitionTicks() { return cast.definition().traits().ignitionTicks(); }
        @Override public boolean isActive() { return !ended; }

        @Override
        public boolean isTargetEligible(LivingEntity target) {
            requireServerThread();
            return !ended && target != null && host.targetEligible(cast, target);
        }

        @Override
        public List<LivingEntity> acquireTargets(double range, double aimAssistDegrees, int limit) {
            requireServerThread();
            if (ended || !Double.isFinite(range) || range <= 0D
                    || !Double.isFinite(aimAssistDegrees) || aimAssistDegrees < 0D) return List.of();
            return List.copyOf(host.acquireTargets(cast, range, Math.min(180D, aimAssistDegrees), Math.max(1, limit)));
        }

        @Override
        public boolean impact(LivingEntity target, double powerFactor) {
            requireServerThread();
            if (ended || target == null || !Double.isFinite(powerFactor) || powerFactor <= 0D) return false;
            if (!host.targetEligible(cast, target)) return false;
            boolean applied = host.applyDamage(cast, target, powerFactor);
            impacted |= applied;
            return applied;
        }

        @Override
        public Task runLater(long delayTicks, Runnable task) {
            return schedule(delayTicks, 0L, task);
        }

        @Override
        public Task runTimer(long delayTicks, long periodTicks, Runnable task) {
            if (periodTicks < 1L) throw new IllegalArgumentException("periodTicks must be at least 1");
            return schedule(delayTicks, periodTicks, task);
        }

        private Task schedule(long delayTicks, long periodTicks, Runnable task) {
            requireServerThread();
            Objects.requireNonNull(task, "task");
            if (delayTicks < 0L) throw new IllegalArgumentException("delayTicks must not be negative");
            ScheduledTask scheduled = new ScheduledTask();
            if (ended) {
                scheduled.done = true;
                return scheduled;
            }
            scheduled.canceller = scheduler.schedule(registration.owner(), delayTicks, periodTicks, () -> {
                if (scheduled.done || ended) return;
                running++;
                try {
                    task.run();
                } catch (RuntimeException | LinkageError failure) {
                    fail(failure);
                    return;
                } finally {
                    running--;
                }
                if (periodTicks == 0L) scheduled.finish();
                endIfIdle();
            });
            tasks.add(scheduled);
            return scheduled;
        }

        @Override
        public void onEnd(Runnable cleanup) {
            requireServerThread();
            Objects.requireNonNull(cleanup, "cleanup");
            if (ended) runCleanup(cleanup);
            else cleanups.add(cleanup);
        }

        @Override
        public void end() {
            if (ended) return;
            ended = true;
            active.remove(this);
            for (ScheduledTask task : new ArrayList<>(tasks)) task.cancel();
            tasks.clear();
            List<Runnable> pending = new ArrayList<>(cleanups);
            cleanups.clear();
            pending.forEach(this::runCleanup);
        }

        private void endIfIdle() {
            if (!ended && running == 0 && tasks.isEmpty()) end();
        }

        private void runCleanup(Runnable cleanup) {
            try {
                cleanup.run();
            } catch (RuntimeException | LinkageError failure) {
                warn(failure);
            }
        }

        private void fail(Throwable failure) {
            warn(failure);
            end();
        }

        private void warn(Throwable failure) {
            if (!failureWarned.add(registration.id())) return;
            warnings.accept("Attack behavior " + registration.id() + " of " + registration.owner().getName()
                    + " failed and its cast was ended: " + failure);
        }

        private final class ScheduledTask implements Task {
            private Runnable canceller;
            private boolean done;

            @Override
            public void cancel() {
                if (done) return;
                finish();
                if (canceller != null) canceller.run();
                endIfIdle();
            }

            @Override
            public boolean isCancelled() {
                return done || ended;
            }

            private void finish() {
                done = true;
                tasks.remove(this);
            }
        }
    }
}
