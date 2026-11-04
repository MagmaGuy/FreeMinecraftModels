package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.IKChain;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.ExpressionTrack;
import com.magmaguy.freeminecraftmodels.dataconverter.IKAnimationFrame;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

/**
 * Plays a model's animations in two layers and mixes them into one pose per tick.
 * <ul>
 * <li>Base layer: a locomotion machine (spawn, idle, long_idle, walk, ride, swim, death) driven by
 * {@link #TRANSITIONS}, or a custom animation played with {@code blend=false}, which takes the whole pose over while
 * the machine keeps tracking idle and walk underneath. A model with a {@code long_idle} animation plays it once after
 * standing idle for a random {@code longIdleMinSeconds} to {@code longIdleMaxSeconds}; a steered mount with a ridden
 * animation plays that in place of walk, and a swimming mob with a swim animation plays that in place of idle and
 * walk.</li>
 * <li>Action layer: attacks, {@code damaged}, and custom animations played with {@code blend=true}. An action
 * replaces the bones it keys when its Blockbench Override is set and adds to them otherwise.</li>
 * </ul>
 * Idle, long_idle, walk, ride and swim cross-fade into each other and death fades in from whatever pose the model holds;
 * every other switch snaps. Custom animations outrank built-in ones, and only death interrupts them.
 * <p>
 * Requests can come from any thread; they are queued and applied on the model clock, which owns all state here.
 */
public class AnimationManager {
    private static final Set<String> ATTACKS = Set.of("attack", "attack_melee", "attack_ranged");
    private static final String DAMAGED = "damaged";

    private enum Base {SPAWN, IDLE, LONG_IDLE, WALK, RIDE, SWIM, DEATH}

    /** One row of the locomotion machine: a state change, whether it reads the mob's movement, when, and its fade. */
    private record Transition(Base from, Base to, boolean needsEntity,
                              Predicate<AnimationManager> when, IntSupplier fadeTicks) {
    }

    // The first row that applies wins, so each ridden row comes before the swimming one, and each swimming row before
    // the walking or resting one it replaces. Swimming reads no entity: the model reports it (see setSwimming).
    private static final List<Transition> TRANSITIONS = List.of(
            new Transition(Base.SPAWN, Base.WALK, false, m -> m.base.finished() && m.moving, () -> 0),
            new Transition(Base.SPAWN, Base.IDLE, false, m -> m.base.finished(), () -> 0),
            new Transition(Base.IDLE, Base.RIDE, true, m -> m.moving && m.steered, AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.SWIM, false, AnimationManager::swims, AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.WALK, true, m -> m.moving && !m.rides(), AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.LONG_IDLE, false, AnimationManager::longIdleDue,
                    AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.RIDE, true, m -> m.moving && m.steered,
                    AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.SWIM, false, AnimationManager::swims, AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.WALK, true, m -> m.moving && !m.rides(),
                    AnimationManager::locomotionFade),
            // Anything else that starts cuts it short: an attack, a rider.
            new Transition(Base.LONG_IDLE, Base.IDLE, false, m -> m.base.finished() || m.action != null || m.steered,
                    AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.RIDE, true, m -> m.moving && m.steered, AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.SWIM, false, AnimationManager::swims, AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.IDLE, true, m -> !m.moving, AnimationManager::locomotionFade),
            new Transition(Base.RIDE, Base.IDLE, true, m -> !m.moving, AnimationManager::locomotionFade),
            new Transition(Base.RIDE, Base.WALK, true, m -> !m.rides(), AnimationManager::locomotionFade),
            new Transition(Base.SWIM, Base.RIDE, true, m -> m.moving && m.steered, AnimationManager::locomotionFade),
            // Out of the water it walks on, or stands.
            new Transition(Base.SWIM, Base.WALK, true, m -> !m.swims() && m.moving && !m.rides(),
                    AnimationManager::locomotionFade),
            new Transition(Base.SWIM, Base.IDLE, false, m -> !m.swims(), AnimationManager::locomotionFade));

    private final ModeledEntity modeledEntity;
    private final Animations animations;
    private final Bone[] bones;
    private final Animation idle, walk, spawn, death, longIdle;
    // What a steered mount plays while it moves, in place of walk; null walks. Set on the clock thread.
    private Animation ride;
    // What a swimming mob plays, in place of idle and walk; null keeps them. Set on the clock thread.
    private Animation swim;
    // Whether the model's mob swims, as the model last reported it.
    private boolean swimming = false;
    private final SplittableRandom random = new SplittableRandom();
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
    // Whether a player steers the mob this tick.
    private boolean steered = false;
    // Ticks the base has shown idle with nothing else playing, and how many the current wait for long_idle lasts.
    private int idleTicks = 0;
    private int longIdleWait = -1;
    // Where a steered mount was last seen; its client moves it with absolute positions, so it has no velocity here.
    private double lastX = Double.NaN, lastZ = Double.NaN;

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

    // What the last render showed: its playbacks at their frames, its fade and the queries it read. While nobody
    // watches the model the render keeps its clock, particle keyframes and Bedrock state but skips sampling the
    // pose, and the pose is sampled from these once something reads it: a new viewer, a fade from it, a getter.
    private Playback shownBase, shownAction, shownFade;
    private int shownBaseFrame, shownActionFrame, shownFadeFrame;
    private boolean shownFadeFrozen;
    private float shownFadeWeight = Float.NaN;
    private int shownLifeTicks;
    private double shownDistance;
    private boolean shownMoving;
    // Read without the clock's lock by the bone pose getters, which then take it to sample.
    private volatile boolean poseStale = false;

    // MoLang state for expression keyframes, null for a model without any. Only the clock thread touches it.
    // Queries resolve lazily from these fields; the expressions' own variables live in the context.
    private final MolangContext molang;
    private int lifeTicks = 0;
    private double distanceMoved = 0;
    private double animTime = 0;
    private final IKAnimationFrame expressionGoal = new IKAnimationFrame();

    public AnimationManager(ModeledEntity modeledEntity, AnimationsBlueprint bp) {
        this.modeledEntity = modeledEntity;
        this.bones = modeledEntity.getSkeleton().getBones().toArray(new Bone[0]);
        this.animations = new Animations(bp, bones, modeledEntity.getSkeleton());
        idle = animations.getAnimations().get("idle");
        walk = animations.getAnimations().get("walk");
        spawn = animations.getAnimations().get("spawn");
        death = animations.getAnimations().get("death");
        longIdle = animations.getAnimations().get("long_idle");
        basePose = new Pose(bones.length);
        fromPose = new Pose(bones.length);
        frozenPose = new Pose(bones.length);
        finalPose = new Pose(bones.length);

        boolean expressions = animations.getAnimations().values().stream().anyMatch(Animation::usesExpressions);
        molang = expressions ? new MolangContext(new SplittableRandom()) : null;
        // Queries read what the shown render saw, so a pose sampled later matches the one it stands for.
        if (molang != null) molang.setResolver((name, reading) -> switch (name) {
            case "query.anim_time" -> animTime;
            case "query.life_time" -> shownLifeTicks / 20.0;
            case "query.modified_distance_moved" -> shownDistance;
            case "query.is_moving" -> shownMoving ? 1.0 : 0.0;
            default -> null;
        });

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

    /**
     * The animation a steered mount plays while it moves, in place of walk: a ridden pet's slide, say. Null, or a name
     * the model lacks, walks as before.
     */
    public void setRiddenAnimation(String name) {
        requests.add(() -> {
            Animation next = name == null ? null : animations.getAnimations().get(name);
            if (next == null && name != null) next = animations.getAnimations().get(name.toLowerCase(Locale.ROOT));
            ride = next;
            if (locomotion == Base.RIDE && !rides()) setLocomotion(moving ? Base.WALK : Base.IDLE, locomotionFade());
        });
    }

    /**
     * The animation a swimming mob plays, in place of idle and walk: a penguin's belly slide, say. Null, or a name the
     * model lacks, keeps idle and walk in water too.
     */
    public void setSwimAnimation(String name) {
        requests.add(() -> {
            Animation next = name == null ? null : animations.getAnimations().get(name);
            if (next == null && name != null) next = animations.getAnimations().get(name.toLowerCase(Locale.ROOT));
            swim = next;
            if (locomotion == Base.SWIM && !swims()) setLocomotion(moving ? Base.WALK : Base.IDLE, locomotionFade());
        });
    }

    /** Whether the model's mob swims now. The model reports this every tick before the animations tick. */
    public void setSwimming(boolean swimming) {
        this.swimming = swimming;
    }

    /** Whether only the base's own loops play: no attack, custom animation, spawn or death. Clock thread only. */
    public boolean isOnlyLocomotionPlaying() {
        return action == null && !takeover && isLocomotion(locomotion);
    }

    public void tick() {
        Runnable request;
        while ((request = requests.poll()) != null) request.run();
        observe();
        finishPlaybacks();
        runTransitions();
        render();
        lifeTicks++;
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
        steered = modeledEntity instanceof DynamicEntity dynamicEntity && dynamicEntity.hasSteeringRider();
        if (!steered) {
            lastX = Double.NaN;
            travel(velocity.getX(), velocity.getZ());
            return;
        }
        // A player's client moves the mob it steers and the server only snaps it there, leaving its velocity at
        // zero, so a steered mount walks when it has actually moved since the last tick.
        Location at = entity.getLocation();
        double dx = at.getX() - lastX, dz = at.getZ() - lastZ;
        moving = !Double.isNaN(lastX) && dx * dx + dz * dz > 1.0E-6;
        if (moving) travel(dx, dz);
        lastX = at.getX();
        lastZ = at.getZ();
    }

    /** Accumulates query.modified_distance_moved from this tick's horizontal step, in blocks. */
    private void travel(double dx, double dz) {
        if (molang != null) distanceMoved += Math.sqrt(dx * dx + dz * dz);
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
        try {
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
        } finally {
            countIdle();
        }
    }

    /** Counts the ticks the base shows idle with nothing else playing; any other tick starts the wait over. */
    private void countIdle() {
        if (locomotion != Base.IDLE || takeover || action != null || steered || longIdle == null) {
            idleTicks = 0;
            return;
        }
        if (idleTicks++ == 0) longIdleWait = rollLongIdleWait();
    }

    /** A wait for long_idle in ticks, between the configured seconds; -1 when long_idle is turned off. */
    private int rollLongIdleWait() {
        int max = DefaultConfig.longIdleMaxSeconds;
        if (max <= 0) return -1;
        int min = Math.max(0, Math.min(DefaultConfig.longIdleMinSeconds, max));
        return Math.max(1, 20 * min + random.nextInt(20 * (max - min) + 1));
    }

    private boolean longIdleDue() {
        return longIdleWait > 0 && idleTicks >= longIdleWait;
    }

    /** Whether the mob swims, nobody steers it, and the model has a swim animation for it. */
    private boolean swims() {
        return swimming && swim != null && !steered;
    }

    /** Whether a player steers the mob and the model has a ridden animation for it. */
    private boolean rides() {
        return steered && ride != null;
    }

    private static int locomotionFade() {
        return DefaultConfig.idleWalkBlendTicks;
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
        int fade = isLocomotion(locomotion) && isLocomotion(target) ? DefaultConfig.idleWalkBlendTicks : 0;
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
        // The machine keeps tracking idle and walk underneath; a spawn or long idle in progress is over.
        if (locomotion == Base.SPAWN || locomotion == Base.LONG_IDLE)
            locomotion = moving && walk != null ? Base.WALK : Base.IDLE;
        switchBase(new Playback(animation, loop), 0);
        takeover = true;
    }

    private void startDeath() {
        if (locomotion == Base.DEATH) return;
        // Fade from exactly what viewers saw last tick, the action layer included.
        refreshPose();
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
                refreshPose();
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

        float fadeWeight = Float.NaN;
        if (fadeActive()) {
            fadeElapsed++;
            float t = Math.min(1f, fadeElapsed / (float) fadeTicks);
            fadeWeight = t * t * (3 - 2 * t);
        }
        shownBase = base;
        shownBaseFrame = base != null ? base.frame() : -1;
        shownAction = action;
        shownActionFrame = action != null ? action.frame() : -1;
        shownFade = fadeFrom;
        shownFadeFrame = fadeFrom != null ? fadeFrom.frame() : -1;
        shownFadeFrozen = fadeFromFrozen;
        shownFadeWeight = fadeWeight;
        shownLifeTicks = lifeTicks;
        shownDistance = distanceMoved;
        shownMoving = moving;
        // A variable an expression writes is read by later evaluations, so such an animation samples every tick.
        if (isWatched() || writesVariables(shownBase) || writesVariables(shownAction) || writesVariables(shownFade)) {
            poseStale = false;
            samplePose();
        } else {
            poseStale = true;
        }
        restWritten = !playing;

        if (base != null && base.fresh()) modeledEntity.getParticleComponent().onAnimationFrame(base.animation, base.frame());
        if (action != null && action.fresh())
            modeledEntity.getParticleComponent().onAnimationFrame(action.animation, action.frame());
        modeledEntity.updateBedrockAnimations(base != null ? base.name() : null, action != null ? action.name() : null);

        if (base != null) base.advance();
        if (action != null) action.advance();
        if (fadeFrom != null) fadeFrom.advance();
        if (fadeActive() && fadeElapsed >= fadeTicks) clearFade();
    }

    /** Whether anyone sees the model, whose bones only then take the pose. */
    private boolean isWatched() {
        return modeledEntity.getSkeleton().getSkeletonWatchers().hasObservers();
    }

    private static boolean writesVariables(Playback playback) {
        return playback != null && playback.animation.writesVariables();
    }

    /** Whether the last render skipped the pose, which {@link #refreshPose()} then samples. */
    public boolean isPoseStale() {
        return poseStale;
    }

    /**
     * Samples the pose the last render skipped, so the bones hold what they would have. Callers off the clock
     * thread hold the skeleton's lock, as the clock does.
     */
    public void refreshPose() {
        if (!poseStale) return;
        poseStale = false;
        samplePose();
    }

    /** Mixes the shown render's playbacks into the bones and solves their inverse kinematics. */
    private void samplePose() {
        if (shownBase != null) basePose.sample(shownBase.animation, shownBaseFrame, at(shownBaseFrame));
        else basePose.setRest();
        if (!Float.isNaN(shownFadeWeight)) {
            if (shownFadeFrozen) basePose.blendFrom(frozenPose, shownFadeWeight);
            else {
                fromPose.sample(shownFade.animation, shownFadeFrame, at(shownFadeFrame));
                basePose.blendFrom(fromPose, shownFadeWeight);
            }
        }
        finalPose.copyFrom(basePose);
        if (shownAction != null) finalPose.applyAction(shownAction.animation, shownActionFrame, at(shownActionFrame));
        finalPose.writeTo(bones);
        solveInverseKinematics();
    }

    /** Each chain follows the topmost animation that drives it: the action, then the base, then a fading one. */
    private void solveInverseKinematics() {
        if (!modeledEntity.getSkeleton().hasIKChains()) return;
        for (IKChain chain : modeledEntity.getSkeleton().getIkChains()) {
            IKAnimationFrame goal = ikGoal(shownAction, shownActionFrame, chain);
            if (goal == null) goal = ikGoal(shownBase, shownBaseFrame, chain);
            if (goal == null) goal = ikGoal(shownFade, shownFadeFrame, chain);
            if (goal == null) {
                chain.clearIKRotations();
                continue;
            }
            chain.setGoalOffset(goal.goalX, goal.goalY, goal.goalZ);
            chain.solve();
        }
    }

    private IKAnimationFrame ikGoal(Playback playback, int frame, IKChain chain) {
        if (playback == null || frame < 0) return null;
        IKAnimationFrame[] frames = playback.animation.getIkAnimationFrames().get(chain);
        if (frames != null) return frame < frames.length ? frames[frame] : null;
        ExpressionTrack track = playback.animation.getIkExpressionTracks().get(chain);
        if (track == null) return null;
        // One scratch goal serves every chain: the solver reads it before the next lookup.
        MolangContext context = at(frame);
        expressionGoal.goalX = track.sample(0, frame, context);
        expressionGoal.goalY = track.sample(1, frame, context);
        expressionGoal.goalZ = track.sample(2, frame, context);
        return expressionGoal;
    }

    /** The entity's context with query.anim_time at this frame; null for a model without expressions. */
    private MolangContext at(int frame) {
        animTime = frame / 20.0;
        return molang;
    }

    private Animation animationFor(Base state) {
        return switch (state) {
            case SPAWN -> spawn;
            case IDLE -> idle;
            case LONG_IDLE -> longIdle;
            case WALK -> walk;
            case RIDE -> ride;
            case SWIM -> swim;
            case DEATH -> death;
        };
    }

    private Playback locomotionPlayback(Base state) {
        Animation animation = animationFor(state);
        if (animation == null) return null;
        // Long idle plays once whatever Blockbench says, then the machine goes back to idle.
        return new Playback(animation, isLocomotion(state) && state != Base.LONG_IDLE);
    }

    /** The states the base cycles through on its own, which cross-fade into each other. */
    private static boolean isLocomotion(Base state) {
        return state == Base.IDLE || state == Base.LONG_IDLE || state == Base.WALK || state == Base.RIDE
                || state == Base.SWIM;
    }
}
