package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;

import java.util.Optional;

public class WalkState implements IAnimState {
    private final ModeledEntity entity;
    private final AnimationStateConfig cfg;
    private AnimationStateType requestedNext;

    public WalkState(ModeledEntity entity, AnimationStateConfig cfg) {
        this.entity = entity;
        this.cfg = cfg;
    }

    @Override
    public void enter() {
        cfg.getAnimation().resetCounter();
    }

    @Override
    public void update() {
        requestedNext = null;
        if (entity.getUnderlyingEntity() == null) {
            // No underlying entity — e.g. a PlayerDisguiseEntity, which intentionally
            // does not register the disguised player as the underlying entity (to keep
            // it out of the modeled-entity-with-underlying registry and PDC tagging).
            // The disguise drives its own animation state via DisguiseAnimationController,
            // so this generic state machine just needs to idle silently. IdleState
            // already null-guards the same way (see IdleState.java:25). Without this,
            // every tick of WalkState crashes with NPE during /fmm disguise.
            requestedNext = AnimationStateType.IDLE;
            return;
        }
        if (!entity.getUnderlyingEntity().isOnGround()) {
            requestedNext = AnimationStateType.JUMP;
        } else if (entity.getUnderlyingEntity().getVelocity().length() <= .08) {
            requestedNext = AnimationStateType.IDLE;
        }
    }

    @Override
    public void exit() {
    }

    @Override
    public AnimationStateType getType() {
        return AnimationStateType.WALK;
    }

    @Override
    public Optional<AnimationStateType> nextState() {
        return Optional.ofNullable(requestedNext);
    }

    @Override
    public Animation getAnimation() {
        return cfg.getAnimation();
    }

    @Override
    public boolean isLoop() {
        return cfg.isLoop();
    }
}