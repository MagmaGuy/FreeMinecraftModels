package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.IKChain;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.IKAnimationFrame;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Plays a model's animations in two layers and mixes them into one pose per tick.
 * <ul>
 * <li>Base layer: a locomotion machine (spawn, idle, walk, death) driven by {@link #TRANSITIONS}, or a custom
 * animation played with {@code blend=false}, which takes the whole pose over while the machine keeps tracking
 * idle and walk underneath.</li>
 * <li>Action layer: attacks, {@code damaged}, and custom animations played with {@code blend=true}. An action
 * replaces the bones it keys when its Blockbench Override is set and adds to them otherwise.</li>
 * </ul>
 * Idle and walk cross-fade into each other and death fades in from whatever pose the model holds; every other
 * switch snaps. Custom animations outrank built-in ones, and only death interrupts them.
 * <p>
 * Requests can come from any thread; they are queued and applied on the model clock, which owns all state here.
 */
public class AnimationManager {
    private static final Set<String> ATTACKS = Set.of("attack", "attack_melee", "attack_ranged");
    private static final String DAMAGED = "damaged";

    private enum Base {SPAWN, IDLE, WALK, DEATH}

    /** One row of the locomotion machine: a state change, whether it reads the mob's movement, when, and its fade. */
    private record Transition(Base from, Base to, boolean needsEntity,
                              Predicate<AnimationManager> when, IntSupplier fadeTicks) {
    }

    private static final List<Transition> TRANSITIONS = List.of(
            new Transition(Base.SPAWN, Base.WALK, false, m -> m.base.finished() && m.moving, () -> 0),
            new Transition(Base.SPAWN, Base.IDLE, false, m -> m.base.finished(), () -> 0),
            new Transition(Base.IDLE, Base.WALK, true, m -> m.moving, () -> DefaultConfig.idleWalkBlendTicks),
            new Transition(Base.WALK, Base.IDLE, true, m -> !m.moving, () -> DefaultConfig.idleWalkBlendTicks));

    private final ModeledEntity modeledEntity;
    private final Animations animations;
    private final Bone[] bones;
    private final Animation idle, walk, spawn, death;
    private final ConcurrentLinkedQueue<Runnable> requests = new ConcurrentLinkedQueue<>();
    // Set by the request itself so later requests are refused at once: death is terminal.
    private volatile boolean dead = false;

    private Base locomotion;
    // What the base layer shows: the locomotion animation, or a takeover. Null when the state has no animation.
    private Playback base;
    private boolean takeover = false;
    private Playback action;
    private boolean actionCustom = false;
    private boolean moving = false;

    // Cross-fade into the base: from an outgoing animation that keeps playing, or from a frozen pose.
    private Playback fadeFrom;
    private boolean fadeFromFrozen = false;
    private int fadeTicks = 0;
    private int fadeElapsed = 0;

    private final Pose basePose;
    private final Pose fromPose;
    private final Pose frozenPose;
    private final Pose finalPose;
    private boolean restWritten = false;

    public AnimationManager(ModeledEntity modeledEntity, AnimationsBlueprint bp) {
        this.modeledEntity = modeledEntity;
        this.bones = modeledEntity.getSkeleton().getBones().toArray(new Bone[0]);
        this.animations = new Animations(bp, bones, modeledEntity.getSkeleton());
        idle = animations.getAnimations().get("idle");
        walk = animations.getAnimations().get("walk");
        spawn = animations.getAnimations().get("spawn");
        death = animations.getAnimations().get("death");
        basePose = new Pose(bones.length);
        fromPose = new Pose(bones.length);
        frozenPose = new Pose(bones.length);
        finalPose = new Pose(bones.length);

        locomotion = spawn != null ? Base.SPAWN : Base.IDLE;
        base = locomotionPlayback(locomotion);
    }

    /**
     * Plays a built-in state (idle, walk, spawn, death), an attack, or any animation by name.
     *
     * @param name  the animation name
     * @param blend for custom animations: true layers it over the base, false takes the whole pose over
     * @param loop  for custom animations: whether it loops
     * @return true if the animation exists and was accepted
     */
    public boolean play(String name, boolean blend, boolean loop) {
        if (dead || name == null) return false;
        String key = name.toLowerCase(Locale.ROOT);
        switch (key) {
            case "death" -> {
                if (death == null) return false;
                dead = true;
                requests.add(this::startDeath);
                return true;
            }
            case "idle", "walk", "spawn" -> {
                Base target = key.equals("idle") ? Base.IDLE : key.equals("walk") ? Base.WALK : Base.SPAWN;
                if (animationFor(target) == null) break;
                requests.add(() -> requestLocomotion(target));
                return true;
            }
            default -> {
            }
        }
        Animation animation = animations.getAnimations().get(name);
        if (animation == null) animation = animations.getAnimations().get(key);
        if (animation == null) return false;
        Animation requested = animation;
        if (ATTACKS.contains(key)) requests.add(() -> requestAttack(requested));
        else if (key.equals(DAMAGED)) requests.add(() -> requestCustom(requested, true, loop));
        else requests.add(() -> requestCustom(requested, blend, loop));
        return true;
    }

    /** Ends every custom animation and attack and snaps the model to idle. Death keeps playing. */
    public void stop() {
        if (dead) return;
        requests.add(() -> {
            if (locomotion == Base.DEATH) return;
            endAction();
            takeover = false;
            locomotion = Base.IDLE;
            switchBase(locomotionPlayback(Base.IDLE), 0);
        });
    }

    public boolean hasAnimation(String animationName) {
        return animations.getAnimations().containsKey(animationName);
    }

    public void tick() {
        Runnable request;
        while ((request = requests.poll()) != null) request.run();
        observe();
        finishPlaybacks();
        runTransitions();
        render();
    }

    private void observe() {
        Entity entity = modeledEntity.getUnderlyingEntity();
        if (entity == null) {
            moving = false;
            return;
        }
        // Horizontal motion only, so gravity alone never starts the walk.
        Vector velocity = entity.getVelocity();
        moving = velocity.getX() != 0 || velocity.getZ() != 0;
    }

    private void finishPlaybacks() {
        // A finished one-shot holds its last frame while the base shows nothing, so a prop's door stays open.
        if (action != null && action.finished() && base != null) endAction();
        if (takeover && base.finished()) {
            Playback next = locomotionPlayback(locomotion);
            if (next != null) {
                takeover = false;
                switchBase(next, 0);
            }
        }
        if (locomotion == Base.DEATH && base != null && base.finished()) modeledEntity.removeWithMinimizedAnimation();
    }

    private void runTransitions() {
        if (locomotion == Base.DEATH) return;
        boolean hasEntity = modeledEntity.getUnderlyingEntity() != null;
        for (Transition transition : TRANSITIONS) {
            if (transition.from() != locomotion) continue;
            if (transition.needsEntity() && !hasEntity) continue;
            if (locomotion == Base.SPAWN && (takeover || base == null)) continue;
            if (animationFor(transition.to()) == null) continue;
            if (!transition.when().test(this)) continue;
            setLocomotion(transition.to(), transition.fadeTicks().getAsInt());
            return;
        }
    }

    private void requestLocomotion(Base target) {
        if (locomotion == Base.DEATH) return;
        if (takeover) {
            // A one-shot custom finishes first and then shows this state. A loop would never end, so an explicit
            // request replaces it.
            if (!base.loop) {
                locomotion = target;
                return;
            }
            takeover = false;
            locomotion = target;
            switchBase(locomotionPlayback(target), 0);
            return;
        }
        if (target == locomotion && base != null) return;
        int fade = isIdleOrWalk(locomotion) && isIdleOrWalk(target) ? DefaultConfig.idleWalkBlendTicks : 0;
        setLocomotion(target, fade);
    }

    private void requestAttack(Animation animation) {
        if (locomotion == Base.DEATH) return;
        // Custom animations outrank the built-in attack.
        if (takeover || (action != null && actionCustom)) return;
        startAction(animation, false, false);
    }

    private void requestCustom(Animation animation, boolean layered, boolean loop) {
        if (locomotion == Base.DEATH) return;
        if (layered) {
            startAction(animation, loop, true);
            return;
        }
        endAction();
        // The machine keeps tracking idle and walk underneath; a spawn in progress is over.
        if (locomotion == Base.SPAWN) locomotion = moving && walk != null ? Base.WALK : Base.IDLE;
        switchBase(new Playback(animation, loop), 0);
        takeover = true;
    }

    private void startDeath() {
        if (locomotion == Base.DEATH) return;
        // Fade from exactly what viewers saw last tick, the action layer included.
        frozenPose.copyFrom(finalPose);
        endAction();
        takeover = false;
        locomotion = Base.DEATH;
        if (base != null) modeledEntity.getParticleComponent().onAnimationStopped(base.animation);
        base = new Playback(death, false);
        fadeFrom = null;
        fadeFromFrozen = DefaultConfig.deathBlendTicks > 0;
        fadeTicks = Math.max(0, DefaultConfig.deathBlendTicks);
        fadeElapsed = 0;
    }

    private void startAction(Animation animation, boolean loop, boolean custom) {
        endAction();
        action = new Playback(animation, loop);
        actionCustom = custom;
    }

    private void endAction() {
        if (action == null) return;
        modeledEntity.getParticleComponent().onAnimationStopped(action.animation);
        action = null;
        actionCustom = false;
    }

    private void setLocomotion(Base target, int fade) {
        locomotion = target;
        if (!takeover) switchBase(locomotionPlayback(target), fade);
    }

    private void switchBase(Playback next, int fade) {
        Playback previous = base;
        if (previous != null) modeledEntity.getParticleComponent().onAnimationStopped(previous.animation);
        if (fade > 0) {
            if (fadeActive() || previous == null) {
                // An interrupted fade, or nothing playing: fade from the pose viewers saw last tick.
                frozenPose.copyFrom(basePose);
                fadeFrom = null;
                fadeFromFrozen = true;
            } else {
                fadeFrom = previous;
                fadeFromFrozen = false;
            }
            fadeTicks = fade;
            fadeElapsed = 0;
        } else {
            clearFade();
        }
        base = next;
    }

    private boolean fadeActive() {
        return fadeTicks > 0 && (fadeFromFrozen || fadeFrom != null);
    }

    private void clearFade() {
        fadeFrom = null;
        fadeFromFrozen = false;
        fadeTicks = 0;
        fadeElapsed = 0;
    }

    private void render() {
        boolean playing = base != null || action != null || fadeActive();
        if (!playing && restWritten) {
            modeledEntity.updateBedrockAnimations(null, null);
            return;
        }

        if (base != null) basePose.sample(base);
        else basePose.setRest();
        if (fadeActive()) {
            fadeElapsed++;
            float t = Math.min(1f, fadeElapsed / (float) fadeTicks);
            float weight = t * t * (3 - 2 * t);
            if (fadeFromFrozen) basePose.blendFrom(frozenPose, weight);
            else {
                fromPose.sample(fadeFrom);
                basePose.blendFrom(fromPose, weight);
            }
        }
        finalPose.copyFrom(basePose);
        if (action != null) finalPose.applyAction(action);
        finalPose.writeTo(bones);
        restWritten = !playing;

        if (base != null && base.fresh()) modeledEntity.getParticleComponent().onAnimationFrame(base.animation, base.frame());
        if (action != null && action.fresh())
            modeledEntity.getParticleComponent().onAnimationFrame(action.animation, action.frame());
        solveInverseKinematics();
        modeledEntity.updateBedrockAnimations(base != null ? base.name() : null, action != null ? action.name() : null);

        if (base != null) base.advance();
        if (action != null) action.advance();
        if (fadeFrom != null) fadeFrom.advance();
        if (fadeActive() && fadeElapsed >= fadeTicks) clearFade();
    }

    /** Each chain follows the topmost animation that drives it: the action, then the base, then a fading one. */
    private void solveInverseKinematics() {
        if (!modeledEntity.getSkeleton().hasIKChains()) return;
        for (IKChain chain : modeledEntity.getSkeleton().getIkChains()) {
            IKAnimationFrame goal = ikGoal(action, chain);
            if (goal == null) goal = ikGoal(base, chain);
            if (goal == null) goal = ikGoal(fadeFrom, chain);
            if (goal == null) {
                chain.clearIKRotations();
                continue;
            }
            chain.setGoalOffset(goal.goalX, goal.goalY, goal.goalZ);
            chain.solve();
        }
    }

    private static IKAnimationFrame ikGoal(Playback playback, IKChain chain) {
        if (playback == null) return null;
        IKAnimationFrame[] frames = playback.animation.getIkAnimationFrames().get(chain);
        int frame = playback.frame();
        if (frames == null || frame < 0 || frame >= frames.length) return null;
        return frames[frame];
    }

    private Animation animationFor(Base state) {
        return switch (state) {
            case SPAWN -> spawn;
            case IDLE -> idle;
            case WALK -> walk;
            case DEATH -> death;
        };
    }

    private Playback locomotionPlayback(Base state) {
        Animation animation = animationFor(state);
        if (animation == null) return null;
        return new Playback(animation, state == Base.IDLE || state == Base.WALK);
    }

    private static boolean isIdleOrWalk(Base state) {
        return state == Base.IDLE || state == Base.WALK;
    }
}
