package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;

import java.util.Optional;

public class DeathState implements IAnimState {
    private final ModeledEntity modeledEntity;
    private final AnimationStateConfig cfg;

    public DeathState(ModeledEntity entity, AnimationStateConfig cfg) {
        this.modeledEntity = entity;
        this.cfg = cfg;
    }

    @Override
    public void enter() {
        cfg.getAnimation().resetCounter();
    }

    @Override
    public void update() {
        // no-op: we’ll check for “done” in nextState()
    }

    @Override
    public void exit() {
    }

    @Override
    public AnimationStateType getType() {
        return AnimationStateType.DEATH;
    }

    @Override
    public Optional<AnimationStateType> nextState() {
        Animation anim = cfg.getAnimation();
        long counter = anim.getCounter();
        int dur = anim.getAnimationBlueprint().getDuration();

        if (counter >= dur) {
            // only remove after the last frame has been drawn (counter was incremented
            // at end of renderCurrentFrame)
            modeledEntity.removeWithMinimizedAnimation();
        }
        return Optional.empty();
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
