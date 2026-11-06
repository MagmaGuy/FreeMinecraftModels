package com.magmaguy.freeminecraftmodels.animation;

/**
 * One playing animation with its own clock, so several animations can play at once.
 */
final class Playback {
    final Animation animation;
    final boolean loop;
    private int tick = 0;

    Playback(Animation animation, boolean loop) {
        this(animation, loop, 0);
    }

    /** Starts {@code ticks} into the animation, as one picked up from another model's playback. */
    Playback(Animation animation, boolean loop, int ticks) {
        this.animation = animation;
        this.loop = loop;
        this.tick = Math.max(0, ticks);
    }

    /** The ticks played so far, which a model taking over from this one continues from. */
    int ticks() {
        return tick;
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
