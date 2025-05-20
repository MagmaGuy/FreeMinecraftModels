package com.magmaguy.freeminecraftmodels.animation;

import java.util.Optional;

public interface IAnimState {
    void enter();

    void update();

    void exit();

    AnimationStateType getType();

    Optional<AnimationStateType> nextState();

    Animation getAnimation();

    boolean isLoop();
}
