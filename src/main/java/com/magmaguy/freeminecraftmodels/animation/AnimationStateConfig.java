package com.magmaguy.freeminecraftmodels.animation;

import javax.annotation.Nullable;

public class AnimationStateConfig {
    private final Animation animation;
    private final boolean loop;

    public AnimationStateConfig(Animation animation, boolean loop) {
        this.animation = animation;
        this.loop = loop;
    }

    @Nullable
    public Animation getAnimation() {
        return animation;
    }

    public boolean isLoop() {
        return loop;
    }
}