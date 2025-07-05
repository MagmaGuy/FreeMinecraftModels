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