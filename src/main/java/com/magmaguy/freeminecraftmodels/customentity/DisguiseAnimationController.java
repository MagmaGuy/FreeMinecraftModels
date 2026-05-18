package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-disguise state machine driving reserved animation names. Priority
 * (highest wins each tick): attack > jump > sneak > walk > idle.
 *
 * <p>Event-driven triggers (arm swing, sneak toggle) fire on the main
 * thread and set volatile flags that the async tick consumes. All other
 * inputs (location, ground state) are read directly from the player each
 * tick via async-safe Bukkit calls.
 */
public class DisguiseAnimationController {
    // Reserved animation names — contract with modelers.
    private static final String ANIM_IDLE = "idle";
    private static final String ANIM_WALK = "walk";
    private static final String ANIM_ATTACK = "attack";
    private static final String ANIM_SNEAK = "sneak";
    private static final String ANIM_JUMP = "jump";

    // Movement threshold in blocks-squared. Avoids treating float drift
    // / micro-jitter as walking. ~0.01 blocks => 0.0001 squared.
    private static final double MOVEMENT_THRESHOLD_SQ = 0.0001;

    private final PlayerDisguiseEntity disguise;
    private final Player player;

    // Edge-triggered one-shot animations: set on main thread, consumed
    // on next async tick. AtomicBoolean for visibility + cheap CAS.
    private final AtomicBoolean attackPending = new AtomicBoolean(false);

    // Tick-bookkeeping state.
    private Vector lastTickPosition = null;
    private boolean wasOnGround = true;
    private String currentLoopAnimation = null;

    // One-shot animation tracking: when set, we suppress lower-priority
    // loops until the one-shot's animation has had time to play.
    // We use a tick countdown rather than callback hooks because the
    // animation system here doesn't expose completion callbacks.
    private int oneShotTicksRemaining = 0;
    // Default duration (in server ticks) we assume a one-shot takes if
    // we can't measure it. 20 ticks = 1 second — reasonable upper bound
    // for an attack swing or jump anim. Tune if needed.
    private static final int DEFAULT_ONE_SHOT_DURATION_TICKS = 20;

    public DisguiseAnimationController(PlayerDisguiseEntity disguise) {
        this.disguise = disguise;
        this.player = disguise.getDisguisedPlayer();
        warnIfMissingIdle();
    }

    /** Called from main thread by PlayerAnimationEvent. */
    public void onArmSwing() {
        attackPending.set(true);
    }

    /**
     * Called once per async tick from {@link PlayerDisguiseEntity#tick}.
     */
    public void tick() {
        if (oneShotTicksRemaining > 0) {
            oneShotTicksRemaining--;
            // Still bookkeeping movement/ground state so the next loop
            // pick after the one-shot finishes has fresh data.
            updateMovementBookkeeping();
            return;
        }

        // Priority: attack > jump > sneak > walk > idle.
        if (attackPending.getAndSet(false) && disguise.hasAnimation(ANIM_ATTACK)) {
            playOneShot(ANIM_ATTACK);
            return;
        }

        boolean onGround = player.isOnGround();
        boolean jumped = wasOnGround && !onGround && player.getVelocity().getY() > 0;
        if (jumped && disguise.hasAnimation(ANIM_JUMP)) {
            playOneShot(ANIM_JUMP);
            updateMovementBookkeeping();
            return;
        }

        if (player.isSneaking() && disguise.hasAnimation(ANIM_SNEAK)) {
            ensureLoop(ANIM_SNEAK);
            updateMovementBookkeeping();
            return;
        }

        if (isMoving() && disguise.hasAnimation(ANIM_WALK)) {
            ensureLoop(ANIM_WALK);
            updateMovementBookkeeping();
            return;
        }

        ensureLoop(ANIM_IDLE);
        updateMovementBookkeeping();
    }

    private boolean isMoving() {
        if (lastTickPosition == null) return false;
        Vector cur = player.getLocation().toVector();
        return cur.distanceSquared(lastTickPosition) > MOVEMENT_THRESHOLD_SQ;
    }

    private void updateMovementBookkeeping() {
        lastTickPosition = player.getLocation().toVector();
        wasOnGround = player.isOnGround();
    }

    private void playOneShot(String name) {
        // blend=false (interrupts loop), loop=false (one-shot).
        disguise.playAnimation(name, false, false);
        oneShotTicksRemaining = DEFAULT_ONE_SHOT_DURATION_TICKS;
        currentLoopAnimation = null; // re-evaluate loop after the one-shot.
    }

    private void ensureLoop(String name) {
        if (name.equals(currentLoopAnimation)) return; // already playing.
        if (!disguise.hasAnimation(name)) {
            if (!ANIM_IDLE.equals(name)) {
                // Missing walk/sneak — fall back to idle silently per design.
                ensureLoop(ANIM_IDLE);
            }
            return;
        }
        disguise.playAnimation(name, false, true);
        currentLoopAnimation = name;
    }

    private void warnIfMissingIdle() {
        if (!disguise.hasAnimation(ANIM_IDLE)) {
            Logger.warn("Disguise model '" + disguise.getEntityID()
                    + "' has no '" + ANIM_IDLE
                    + "' animation — disguised players using this model will freeze on the last animation frame whenever no other action is active.");
        }
    }
}
