package com.magmaguy.freeminecraftmodels.animation;

import java.util.Optional;

public class SpawnState implements IAnimState {
    private final AnimationStateConfig cfg;
    private boolean finished;

    public SpawnState(AnimationStateConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public void enter() {
        cfg.getAnimation().resetCounter();
        finished = false;
    }

    @Override
    public void update() {
        if (cfg.getAnimation().getCounter() >= cfg.getAnimation().getAnimationBlueprint().getDuration()) {
            finished = true;
        }
    }

    @Override
    public void exit() {
    }

    @Override
    public AnimationStateType getType() {
        return AnimationStateType.SPAWN;
    }

    @Override
    public Optional<AnimationStateType> nextState() {
        return finished ? Optional.of(AnimationStateType.IDLE) : Optional.empty();
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
