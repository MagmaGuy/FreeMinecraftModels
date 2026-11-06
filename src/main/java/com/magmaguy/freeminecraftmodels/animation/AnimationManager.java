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
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Plays a model's animations in two layers and mixes them into one pose per tick.
 * <ul>
 * <li>Base layer: a locomotion machine (spawn, idle, idle.long, walk, ride, ridden idle, swim, idle swim, death)
 * driven by {@link #TRANSITIONS}, or a custom animation played with {@code blend=false}, which takes the whole pose
 * over while the machine keeps tracking idle and walk underneath. A model with an {@code idle.long} animation plays it
 * once after standing idle for a random {@code longIdleMinSeconds} to {@code longIdleMaxSeconds}. A mob a player
 * steers plays {@code ride} in place of walk and {@code idle.ride} in place of idle; a swimming mob plays {@code swim}
 * in place of walk while it moves and {@code idle.swim} in place of idle while it holds still. Each plays when the
 * model has it, and the general one plays when it does not; a plugin can name others (see the setters).</li>
 * <li>Action layer: attacks ({@code attack} and any {@code attack.} name), {@code damaged}, and custom animations
 * played with {@code blend=true}. An action replaces the bones it keys when its Blockbench Override is set and adds to
 * them otherwise.</li>
 * </ul>
 * Idle, idle.long, walk, ride, ridden idle, swim and idle swim cross-fade into each other and death fades in from
 * whatever pose the model holds; every other switch snaps. Custom animations outrank built-in ones, and only death
 * interrupts them.
 * <p>
 * Names are dotted from the general to the specific: idle.long is a kind of idle, attack.melee a kind of attack. A
 * model without the specific animation plays the general one: attack for attack.melee, idle for idle.swim. The
 * underscore names models were made with before ({@code long_idle}, {@code attack_melee}, {@code attack_ranged}) still
 * stand for them, both ways round.
 * <p>
 * Requests can come from any thread; they are queued and applied on the model clock, which owns all state here.
 */
public class AnimationManager {
    // The names models and plugins used before the dotted ones, by the name each now stands for, and back.
    private static final Map<String, String> OLD_NAMES = Map.of(
            "long_idle", "idle.long", "attack_melee", "attack.melee", "attack_ranged", "attack.ranged");
    private static final Map<String, String> NEW_NAMES = OLD_NAMES.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    private static final String ATTACK = "attack";
    private static final String DAMAGED = "damaged";

    private enum Base {SPAWN, IDLE, LONG_IDLE, WALK, RIDE, RIDE_IDLE, SWIM, SWIM_IDLE, DEATH}

    /** One row of the locomotion machine: a state change, whether it reads the mob's movement, when, and its fade. */
    private record Transition(Base from, Base to, boolean needsEntity,
                              Predicate<AnimationManager> when, IntSupplier fadeTicks) {
    }

    /**
     * What a model showed when another took over from it: its state, its base and action by name and ticks played, the
     * ticks left of its fade, the base pose its viewers saw last (null when its bones differ), and what it had observed.
     */
    private record Handover(Base locomotion, boolean takeover, String baseName, boolean baseLoop, int baseTicks,
                            String actionName, boolean actionLoop, int actionTicks, boolean actionCustom, int fadeLeft,
                            Pose pose, boolean moving, boolean steered, double lastX, double lastZ, boolean swimming,
                            int lifeTicks, double distanceMoved, int idleTicks, int longIdleWait) {
    }

    // Ticks a model that took over from another holds the state it picked up before the machine reads its new mob's
    // movement. A rider's client starts moving the new mob a tick or two after it is seated, and until then it stands
    // still. A rider getting on or off ends the hold at once.
    private static final int HANDOVER_HOLD_TICKS = 5;

    // The first row that applies wins, so each ridden row comes before the swimming ones and each swimming row before
    // the walking or resting one it replaces. A swimmer swims while it moves, or walks when the model has no swim;
    // holding still it plays its idle swim, or idles when the model has none. Swimming reads no entity: the model
    // reports it (see setSwimming). A model without an entity never moves, so it never swims along.
    private static final List<Transition> TRANSITIONS = List.of(
            new Transition(Base.SPAWN, Base.WALK, false, m -> m.base.finished() && m.moving, () -> 0),
            new Transition(Base.SPAWN, Base.IDLE, false, m -> m.base.finished(), () -> 0),
            new Transition(Base.IDLE, Base.RIDE, true, m -> m.moving && m.steered, AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.RIDE_IDLE, true, AnimationManager::ridesInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.SWIM_IDLE, false, AnimationManager::swimsInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.SWIM, false, AnimationManager::swimsAlong, AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.WALK, true, m -> m.moving && !m.rides(), AnimationManager::locomotionFade),
            new Transition(Base.IDLE, Base.LONG_IDLE, false, AnimationManager::longIdleDue,
                    AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.RIDE, true, m -> m.moving && m.steered,
                    AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.RIDE_IDLE, true, AnimationManager::ridesInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.SWIM_IDLE, false, AnimationManager::swimsInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.SWIM, false, AnimationManager::swimsAlong,
                    AnimationManager::locomotionFade),
            new Transition(Base.LONG_IDLE, Base.WALK, true, m -> m.moving && !m.rides(),
                    AnimationManager::locomotionFade),
            // Anything else that starts cuts it short: an attack, a rider.
            new Transition(Base.LONG_IDLE, Base.IDLE, false, m -> m.base.finished() || m.action != null || m.steered,
                    AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.RIDE, true, m -> m.moving && m.steered, AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.RIDE_IDLE, true, AnimationManager::ridesInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.SWIM_IDLE, false, AnimationManager::swimsInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.SWIM, false, AnimationManager::swimsAlong, AnimationManager::locomotionFade),
            new Transition(Base.WALK, Base.IDLE, true, m -> !m.moving, AnimationManager::locomotionFade),
            new Transition(Base.RIDE, Base.RIDE_IDLE, true, AnimationManager::ridesInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.RIDE, Base.IDLE, true, m -> !m.moving, AnimationManager::locomotionFade),
            new Transition(Base.RIDE, Base.WALK, true, m -> !m.rides(), AnimationManager::locomotionFade),
            new Transition(Base.SWIM, Base.RIDE, true, m -> m.moving && m.steered, AnimationManager::locomotionFade),
            new Transition(Base.SWIM, Base.RIDE_IDLE, true, AnimationManager::ridesInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.SWIM, Base.SWIM_IDLE, false, AnimationManager::swimsInPlace,
                    AnimationManager::locomotionFade),
            // Out of the water it walks on, or stands; in it, without an idle swim, it idles once it holds still.
            new Transition(Base.SWIM, Base.WALK, true, m -> m.moving && !m.swimsAlong() && !m.rides(),
                    AnimationManager::locomotionFade),
            new Transition(Base.SWIM, Base.IDLE, false, m -> !m.swimsAlong(), AnimationManager::locomotionFade),
            new Transition(Base.SWIM_IDLE, Base.RIDE, true, m -> m.moving && m.steered,
                    AnimationManager::locomotionFade),
            new Transition(Base.SWIM_IDLE, Base.RIDE_IDLE, true, AnimationManager::ridesInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.SWIM_IDLE, Base.SWIM, false, AnimationManager::swimsAlong,
                    AnimationManager::locomotionFade),
            // Moving without a swim animation, in water or out of it, it walks.
            new Transition(Base.SWIM_IDLE, Base.WALK, true, m -> m.moving && !m.swimsAlong() && !m.rides(),
                    AnimationManager::locomotionFade),
            new Transition(Base.SWIM_IDLE, Base.IDLE, false, m -> !m.swimsInPlace(), AnimationManager::locomotionFade),
            // The rider sets off, or gets off.
            new Transition(Base.RIDE_IDLE, Base.RIDE, true, m -> m.moving && m.steered,
                    AnimationManager::locomotionFade),
            new Transition(Base.RIDE_IDLE, Base.SWIM_IDLE, false, AnimationManager::swimsInPlace,
                    AnimationManager::locomotionFade),
            new Transition(Base.RIDE_IDLE, Base.SWIM, false, AnimationManager::swimsAlong,
                    AnimationManager::locomotionFade),
            new Transition(Base.RIDE_IDLE, Base.WALK, true, m -> m.moving && !m.rides(),
                    AnimationManager::locomotionFade),
            new Transition(Base.RIDE_IDLE, Base.IDLE, true, m -> !m.steered, AnimationManager::locomotionFade));

    private final ModeledEntity modeledEntity;
    private final Animations animations;
    private final Bone[] bones;
    private final Animation idle, walk, spawn, death, longIdle;
    // What a steered mount plays while it moves, in place of walk; null walks. Set on the clock thread.
    private Animation ride;
    // What a steered mount plays while it stands, in place of idle; null idles. Set on the clock thread.
    private Animation rideIdle;
    // What a swimming mob plays while it moves, in place of walk; null walks. Set on the clock thread.
    private Animation swim;
    // What a swimming mob plays while it holds still, in place of idle; null idles. Set on the clock thread.
    private Animation idleSwim;
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
    // Ticks the base has shown idle with nothing else playing, and how many the current wait for idle.long lasts.
    private int idleTicks = 0;
    private int longIdleWait = -1;
    // Where a steered mount was last seen; its client moves it with absolute positions, so it has no velocity here.
    private double lastX = Double.NaN, lastZ = Double.NaN;
    // Ticks left in which the machine keeps the state this model picked up from another (see continueFrom), as long as
    // a player steers its mob exactly when one steered the other's.
    private int holdTicks = 0;
    private boolean heldSteered = false;

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
        idle = named("idle");
        walk = named("walk");
        spawn = named("spawn");
        death = named("death");
        longIdle = named("idle.long");
        ride = named("ride");
        rideIdle = named("idle.ride");
        swim = named("swim");
        idleSwim = named("idle.swim");
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
     * Plays a built-in state (idle, walk, spawn, death), an attack, or any animation by name. A model without the
     * animation plays the general one its name belongs to, the name up to its last dot: attack for attack.melee.
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
        Animation animation = named(name);
        if (animation == null) {
            String general = general(key);
            return general != null && play(general, blend, loop);
        }
        Animation requested = animation;
        if (isAttack(key)) requests.add(() -> requestAttack(requested));
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

    /**
     * Whether {@link #play} finds an animation by this name: in any case, by the other spelling of a renamed one, or by
     * the general name it belongs to (attack for attack.melee).
     */
    public boolean hasAnimation(String animationName) {
        for (String name = animationName; name != null; name = general(name))
            if (named(name) != null) return true;
        return false;
    }

    /** The general name this one belongs to, up to its last dot (attack for attack.melee); null without a dot. */
    private static String general(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : null;
    }

    /**
     * The animation a steered mount plays while it moves, in place of walk: a ridden pet's slide, say. The model's
     * {@code ride} until set. Null, or a name the model lacks, walks.
     */
    public void setRiddenAnimation(String name) {
        requests.add(() -> {
            ride = named(name);
            if (locomotion == Base.RIDE && !rides()) setLocomotion(moving ? Base.WALK : Base.IDLE, locomotionFade());
        });
    }

    /**
     * The animation a steered mount plays while it stands still under its rider, in place of idle: a ridden pet lying
     * on its belly, as it slides, so the rider's fixed seat stays on its back. The model's {@code idle.ride} until set.
     * Null, or a name the model lacks, idles.
     */
    public void setRiddenIdleAnimation(String name) {
        requests.add(() -> {
            rideIdle = named(name);
            if (locomotion == Base.RIDE_IDLE && rideIdle == null) setLocomotion(Base.IDLE, locomotionFade());
        });
    }

    /**
     * The animation a swimming mob plays while it moves, in place of walk: a penguin's swim, say. The model's
     * {@code swim} until set. Null, or a name the model lacks, walks in water too.
     */
    public void setSwimAnimation(String name) {
        requests.add(() -> {
            swim = named(name);
            if (locomotion == Base.SWIM && swim == null)
                setLocomotion(moving ? Base.WALK : Base.IDLE, locomotionFade());
        });
    }

    /**
     * The animation a swimming mob plays while it holds still in water, in place of idle: a penguin treading water,
     * say. The model's {@code idle.swim} until set. Null, or a name the model lacks, idles.
     */
    public void setIdleSwimAnimation(String name) {
        requests.add(() -> {
            idleSwim = named(name);
            if (locomotion == Base.SWIM_IDLE && idleSwim == null) setLocomotion(Base.IDLE, locomotionFade());
        });
    }

    /**
     * Picks up where another model left off, for a model that takes its place on another mob: a pet moving onto its
     * diving body. Its first frame shows the state the other was in (riding, swimming, walking, a custom animation or
     * an attack), at the same tick when it is the same animation; when the new mob plays another animation for that
     * state, such as a nautilus body's swim in place of a land body's ride, it starts that one cross-faded from the pose
     * the other model last showed. The other model must not be removed yet. Call after this model's own setters (ridden
     * and swim animations), which apply first.
     */
    public void continueFrom(AnimationManager previous) {
        if (previous == null || previous == this) return;
        Handover handover;
        // The model clock ticks the other model under its skeleton's lock.
        synchronized (previous.modeledEntity.getSkeleton()) {
            handover = previous.handover(bones.length);
        }
        if (handover != null) requests.add(() -> adopt(handover));
    }

    /** What this model shows now, for a model of {@code bones} bones taking over; null once it is dying. */
    private Handover handover(int bones) {
        if (dead || locomotion == Base.DEATH) return null;
        Pose pose = null;
        if (bones == this.bones.length) {
            refreshPose();
            pose = new Pose(bones);
            pose.copyFrom(basePose);
        }
        return new Handover(locomotion, takeover, base != null ? base.name() : null, base != null && base.loop,
                base != null ? base.ticks() : 0, action != null ? action.name() : null, action != null && action.loop,
                action != null ? action.ticks() : 0, actionCustom, fadeActive() ? fadeTicks - fadeElapsed : 0, pose,
                moving, steered, lastX, lastZ, swimming, lifeTicks, distanceMoved, idleTicks, longIdleWait);
    }

    /** Takes the other model's place: its state, playbacks and observations, held while the new mob settles. */
    private void adopt(Handover handover) {
        if (dead || locomotion == Base.DEATH) return;
        moving = handover.moving();
        steered = handover.steered();
        lastX = handover.lastX();
        lastZ = handover.lastZ();
        swimming = handover.swimming();
        lifeTicks = handover.lifeTicks();
        distanceMoved = handover.distanceMoved();
        idleTicks = handover.idleTicks();
        longIdleWait = handover.longIdleWait();
        endAction();
        Animation custom = handover.takeover() ? named(handover.baseName()) : null;
        boolean known = animationFor(handover.locomotion()) != null;
        locomotion = known ? handover.locomotion() : Base.IDLE;
        takeover = custom != null;
        Playback next = takeover ? new Playback(custom, handover.baseLoop(), handover.baseTicks())
                : locomotionPlayback(locomotion);
        boolean same = next != null && next.name().equals(handover.baseName());
        if (same && !takeover) next = new Playback(next.animation, next.loop, handover.baseTicks());
        if (base != null) modeledEntity.getParticleComponent().onAnimationStopped(base.animation);
        base = next;
        clearFade();
        int fade = same ? handover.fadeLeft() : locomotionFade();
        if (fade > 0 && handover.pose() != null) {
            frozenPose.copyFrom(handover.pose());
            fadeFromFrozen = true;
            fadeTicks = fade;
        }
        Animation acting = handover.actionName() != null ? named(handover.actionName()) : null;
        if (acting != null) {
            action = new Playback(acting, handover.actionLoop(), handover.actionTicks());
            actionCustom = handover.actionCustom();
        }
        // A state this model has no animation for is left to the machine at once.
        holdTicks = known || takeover ? HANDOVER_HOLD_TICKS : 0;
        heldSteered = steered;
    }

    /**
     * The model's animation by this name, by its lower case, or by the other spelling of a renamed one (a model's
     * long_idle for idle.long, and the other way round); null for a null name or one the model lacks.
     */
    private Animation named(String name) {
        if (name == null) return null;
        Map<String, Animation> all = animations.getAnimations();
        Animation animation = all.get(name);
        if (animation != null) return animation;
        String key = name.toLowerCase(Locale.ROOT);
        animation = all.get(key);
        if (animation != null) return animation;
        String other = OLD_NAMES.getOrDefault(key, NEW_NAMES.get(key));
        return other == null ? null : all.get(other);
    }

    /** Whether this lower-case name is an attack: attack, any attack. name, or an older spelling of one. */
    private static boolean isAttack(String key) {
        String name = OLD_NAMES.getOrDefault(key, key);
        return name.equals(ATTACK) || name.startsWith(ATTACK + ".");
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
            if (holdTicks > 0 && steered == heldSteered) {
                holdTicks--;
                return;
            }
            holdTicks = 0;
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

    /** A wait for idle.long in ticks, between the configured seconds; -1 when idle.long is turned off. */
    private int rollLongIdleWait() {
        int max = DefaultConfig.longIdleMaxSeconds;
        if (max <= 0) return -1;
        int min = Math.max(0, Math.min(DefaultConfig.longIdleMinSeconds, max));
        return Math.max(1, 20 * min + random.nextInt(20 * (max - min) + 1));
    }

    private boolean longIdleDue() {
        return longIdleWait > 0 && idleTicks >= longIdleWait;
    }

    /** Whether the mob swims and nobody steers it: a steered mount plays its ridden animations in water too. */
    private boolean swims() {
        return swimming && !steered;
    }

    /** Whether the mob swims and holds still, as the walk-or-idle choice on land reads it: no horizontal motion. */
    private boolean swimsInPlace() {
        return swims() && !moving;
    }

    /**
     * Whether the mob swims and travels, as the walk-or-idle choice on land reads it (horizontal motion), and the model
     * has a swim animation for it.
     */
    private boolean swimsAlong() {
        return swims() && moving && swim != null;
    }

    /** Whether a player steers the mob and the model has a ridden animation for it. */
    private boolean rides() {
        return steered && ride != null;
    }

    /** Whether a player steers the mob and it stands still under them. */
    private boolean ridesInPlace() {
        return steered && !moving;
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
            case RIDE_IDLE -> rideIdle;
            case SWIM -> swim;
            case SWIM_IDLE -> idleSwim;
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
                || state == Base.RIDE_IDLE || state == Base.SWIM || state == Base.SWIM_IDLE;
    }
}
