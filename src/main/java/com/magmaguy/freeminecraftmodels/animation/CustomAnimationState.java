package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;

import java.util.Optional;

public class CustomAnimationState implements IAnimState {
    private final ModeledEntity entity;
    private final Animation animation;
    private final boolean loop;
    private final AnimationStateType returnTo;
    private boolean finished;

    public CustomAnimationState(ModeledEntity entity,
                                Animation animation,
                                boolean loop,
                                AnimationStateType returnTo) {
        this.entity = entity;
        this.animation = animation;
        this.loop = loop;
        this.returnTo = returnTo;
    }

    @Override
    public void enter() {
        animation.resetCounter();
        finished = false;
    }

    @Override
    public void update() {
        if (!loop && animation.getCounter() >= animation.getAnimationBlueprint().getDuration()) {
            finished = true;
        }
    }

    @Override
    public void exit() {
    }

    @Override
    public AnimationStateType getType() {
        return AnimationStateType.CUSTOM;
    }

    @Override
    public Optional<AnimationStateType> nextState() {
        return finished ? Optional.of(returnTo) : Optional.empty();
    }

    @Override
    public Animation getAnimation() {
        return animation;
    }

    @Override
    public boolean isLoop() {
        return loop;
    }
}