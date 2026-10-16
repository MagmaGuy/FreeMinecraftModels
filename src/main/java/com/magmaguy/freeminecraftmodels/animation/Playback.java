package com.magmaguy.freeminecraftmodels.animation;

/**
 * One playing animation with its own clock, so several animations can play at once.
 */
final class Playback {
    final Animation animation;
    final boolean loop;
    private int tick = 0;

    Playback(Animation animation, boolean loop) {
        this.animation = animation;
        this.loop = loop;
    }

    private int duration() {
        return animation.getAnimationBlueprint().getDuration();
    }

    /** The frame shown this tick, or -1 for an animation without frames. A finished one-shot holds its last frame. */
    int frame() {
        int duration = duration();
        if (duration <= 0) return -1;
        return loop ? tick % duration : Math.min(tick, duration - 1);
    }

    /** True once a one-shot has shown its last frame. */
    boolean finished() {
        return !loop && tick >= duration();
    }

    /** Whether this tick shows a frame for the first time, so its particle keyframes fire. */
    boolean fresh() {
        return frame() >= 0 && !finished();
    }

    void advance() {
        int duration = duration();
        tick = loop && duration > 0 ? (tick + 1) % duration : tick + 1;
    }

    String name() {
        return animation.getAnimationBlueprint().getAnimationName();
    }
}
