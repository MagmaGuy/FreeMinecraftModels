package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationFrame;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;

import java.util.EnumMap;
import java.util.Map;

public class AnimationManager {
    private final Map<AnimationStateType, IAnimState> states = new EnumMap<>(AnimationStateType.class);
    private final ModeledEntity modeledEntity;
    private final Animations animations;
    private IAnimState current;
    private IAnimState nextQueued;
    private AnimationStateType lastCommitted;

    public AnimationManager(ModeledEntity modeledEntity, AnimationsBlueprint bp) {
        this.modeledEntity = modeledEntity;
        this.animations = new Animations(bp, modeledEntity);

        // register states
        if (animations.getAnimations().get("idle") != null)
            states.put(AnimationStateType.IDLE, new IdleState(modeledEntity, new AnimationStateConfig(animations.getAnimations().get("idle"), true)));
        if (animations.getAnimations().get("walk") != null)
            states.put(AnimationStateType.WALK, new WalkState(modeledEntity, new AnimationStateConfig(animations.getAnimations().get("walk"), true)));
        if (animations.getAnimations().get("attack") != null)
            states.put(AnimationStateType.ATTACK, new AttackState(new AnimationStateConfig(animations.getAnimations().get("attack"), false)));
        if (animations.getAnimations().get("death") != null)
            states.put(AnimationStateType.DEATH, new DeathState(modeledEntity, new AnimationStateConfig(animations.getAnimations().get("death"), false)));
        if (animations.getAnimations().get("spawn") != null)
            states.put(AnimationStateType.SPAWN, new SpawnState(new AnimationStateConfig(animations.getAnimations().get("spawn"), false)));
        lastCommitted = null;

        // start with spawn or idle
        current = states.get(AnimationStateType.SPAWN) != null
                ? states.get(AnimationStateType.SPAWN)
                : states.get(AnimationStateType.IDLE);
        if (current != null) current.enter();
    }

    private void transitionTo(IAnimState target) {
        if (target == null || target == current) return;
        if (current != null) current.exit();
        if (current != null && !(current instanceof CustomAnimationState)) {
            lastCommitted = current.getType();
        }
        current = target;
        current.enter();
    }

    /**
     * Play either a built-in state (idle, walk, attack, death, spawn)
     * or a data-driven animation by name.
     *
     * @param name           name of the animation/state (case-insensitive)
     * @param blendAnimation if true, queue it behind the current; if false, interrupt immediately
     * @param loop           only applies for custom animations
     * @return true if the animation exists and was scheduled
     */
    public boolean play(String name, boolean blendAnimation, boolean loop) {
        // 1) try built-in
        AnimationStateType st = null;
        try {
            st = AnimationStateType.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException ignored) {
        }

        if (st != null && states.containsKey(st)) {
            IAnimState builtIn = states.get(st);
            if (blendAnimation) nextQueued = builtIn;
            else transitionTo(builtIn);
            return true;
        }

        // 2) fallback to custom
        Animation anim = animations.getAnimations().get(name);
        if (anim == null) return false;

        CustomAnimationState custom = new CustomAnimationState(
                modeledEntity,
                anim,
                loop,
                lastCommitted != null ? lastCommitted : AnimationStateType.IDLE
        );

        if (blendAnimation) nextQueued = custom;
        else transitionTo(custom);

        return true;
    }


    public void stop() {
        current.exit();
        transitionTo(states.get(AnimationStateType.IDLE));
    }

    public void tick() {
        if (current == null) return; //todo: this is probably not the best solution as it would block playing animations if there's no idle probably

        // 1) let the state update its own “finished” logic
        current.update();

        // 2) render one frame of whatever the current state holds
        renderCurrentFrame();

        // 3) handle transitions (including blends)
        if (nextQueued != null) {
            transitionTo(nextQueued);
            nextQueued = null;
        } else {
            current.nextState().ifPresent(stateType -> {
                IAnimState next = states.get(stateType);
                transitionTo(next);
            });
        }
    }

    private void renderCurrentFrame() {
        Animation anim = current.getAnimation();
        boolean loop = current.isLoop();
        int duration = anim.getAnimationBlueprint().getDuration();
        long counter = anim.getCounter();

        if (duration == 0) return; //todo: this is just a temp solution, I have to look into the ability to switch

        // if non-looping and we’re past the end, just don’t render further
        if (!loop && counter >= duration) {
            return;
        }

        // compute frame index: either modulo (loop) or clamp to last frame
        int frame;
        if (loop) {
            frame = (int) (counter % duration);
        } else {
            frame = (int) Math.min(counter, duration - 1);
        }

        // apply rotations/translations/scales in one pass
        anim.getAnimationFrames().forEach((part, frames) -> {
            if (frames == null || frames.length <= frame) {
                // reset to default
                part.updateAnimationRotation(0, 0, 0);
                part.updateAnimationTranslation(0, 0, 0);
                part.updateAnimationScale(1f, 1f, 1f);
            } else {
                AnimationFrame f = frames[frame];
                part.updateAnimationRotation(f.xRotation, f.yRotation, f.zRotation);
                part.updateAnimationTranslation(f.xPosition, f.yPosition, f.zPosition);
                part.updateAnimationScale(
                        f.scaleX != null ? f.scaleX : 1f,
                        f.scaleY != null ? f.scaleY : 1f,
                        f.scaleZ != null ? f.scaleZ : 1f);
            }
        });

        // advance the counter for next tick
        anim.incrementCounter();
    }

    public boolean hasAnimation(String animationName) {
        return animations.getAnimations().containsKey(animationName);
    }
}
