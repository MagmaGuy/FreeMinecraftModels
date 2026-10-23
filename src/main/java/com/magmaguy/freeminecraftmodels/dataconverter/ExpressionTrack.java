package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;

import java.util.List;

/**
 * An animation channel with at least one MoLang keyframe. Its values depend on the entity playing it, so instead
 * of baked frames each entity samples it per tick in its own context, with the clamping and interpolation the
 * baked channels get at load. One track is shared by every entity of the model and never changes.
 *
 * @param keyframes the channel's keyframes, normalized: sorted by time without duplicates
 */
public record ExpressionTrack(TransformationType channel, List<Keyframe> keyframes, int blockBenchVersion) {
    public ExpressionTrack {
        keyframes = List.copyOf(keyframes);
    }

    /** The channel's value on an axis (0 = x, 1 = y, 2 = z) at a tick, in the pose mixer's units. */
    public float sample(int axis, int tick, MolangContext context) {
        return AnimationTimeline.toPoseUnits(channel, axis,
                AnimationTimeline.sample(keyframes, tick, axis, context), blockBenchVersion);
    }
}
