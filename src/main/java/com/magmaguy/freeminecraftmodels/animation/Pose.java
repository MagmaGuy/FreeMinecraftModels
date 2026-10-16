package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationFrame;

import java.util.Arrays;

/**
 * Animation rotation (radians), translation and scale for every bone of one model, in the manager's bone order.
 * Buffers are reused every tick, so mixing allocates nothing.
 */
final class Pose {
    private final float[] rotation;
    private final float[] translation;
    private final float[] scale;

    Pose(int bones) {
        rotation = new float[bones * 3];
        translation = new float[bones * 3];
        scale = new float[bones * 3];
        setRest();
    }

    void setRest() {
        Arrays.fill(rotation, 0);
        Arrays.fill(translation, 0);
        Arrays.fill(scale, 1);
    }

    void copyFrom(Pose other) {
        System.arraycopy(other.rotation, 0, rotation, 0, rotation.length);
        System.arraycopy(other.translation, 0, translation, 0, translation.length);
        System.arraycopy(other.scale, 0, scale, 0, scale.length);
    }

    /** The animation's current frame scaled by its blend weight. A bone it does not key rests. */
    void sample(Playback playback) {
        int frame = playback.frame();
        AnimationFrame[][] tracks = playback.animation.getTracks();
        float weight = (float) playback.animation.getAnimationBlueprint().getBlendWeight();
        for (int bone = 0; bone < tracks.length; bone++) {
            AnimationFrame keyed = keyed(tracks[bone], frame);
            int i = bone * 3;
            if (keyed == null) {
                rest(i);
                continue;
            }
            rotation[i] = keyed.xRotation * weight;
            rotation[i + 1] = keyed.yRotation * weight;
            rotation[i + 2] = keyed.zRotation * weight;
            translation[i] = keyed.xPosition * weight;
            translation[i + 1] = keyed.yPosition * weight;
            translation[i + 2] = keyed.zPosition * weight;
            scale[i] = weighted(keyed.scaleX, weight);
            scale[i + 1] = weighted(keyed.scaleY, weight);
            scale[i + 2] = weighted(keyed.scaleZ, weight);
        }
    }

    /** Fades from another pose: weight 0 is all of {@code from}, 1 is all of this pose. */
    void blendFrom(Pose from, float weight) {
        lerp(from.rotation, rotation, weight);
        lerp(from.translation, translation, weight);
        lerp(from.scale, scale, weight);
    }

    /**
     * Layers an action over this pose on the bones it keys, by the Bedrock rule: an override animation replaces
     * those bones' pose, any other adds its rotation and translation and multiplies its scale.
     */
    void applyAction(Playback action) {
        int frame = action.frame();
        AnimationFrame[][] tracks = action.animation.getTracks();
        float weight = (float) action.animation.getAnimationBlueprint().getBlendWeight();
        boolean override = action.animation.getAnimationBlueprint().isOverride();
        for (int bone = 0; bone < tracks.length; bone++) {
            AnimationFrame keyed = keyed(tracks[bone], frame);
            if (keyed == null) continue;
            int i = bone * 3;
            if (override) rest(i);
            rotation[i] += keyed.xRotation * weight;
            rotation[i + 1] += keyed.yRotation * weight;
            rotation[i + 2] += keyed.zRotation * weight;
            translation[i] += keyed.xPosition * weight;
            translation[i + 1] += keyed.yPosition * weight;
            translation[i + 2] += keyed.zPosition * weight;
            scale[i] *= weighted(keyed.scaleX, weight);
            scale[i + 1] *= weighted(keyed.scaleY, weight);
            scale[i + 2] *= weighted(keyed.scaleZ, weight);
        }
    }

    void writeTo(Bone[] bones) {
        for (int bone = 0; bone < bones.length; bone++) {
            int i = bone * 3;
            bones[bone].updateAnimationRotation(rotation[i], rotation[i + 1], rotation[i + 2]);
            bones[bone].updateAnimationTranslation(translation[i], translation[i + 1], translation[i + 2]);
            bones[bone].updateAnimationScale(scale[i], scale[i + 1], scale[i + 2]);
        }
    }

    private static AnimationFrame keyed(AnimationFrame[] track, int frame) {
        if (track == null || frame < 0 || frame >= track.length) return null;
        return track[frame];
    }

    private void rest(int i) {
        for (int axis = 0; axis < 3; axis++) {
            rotation[i + axis] = 0;
            translation[i + axis] = 0;
            scale[i + axis] = 1;
        }
    }

    private static float weighted(Float value, float weight) {
        float raw = value != null ? value : 1f;
        return 1 + (raw - 1) * weight;
    }

    private static void lerp(float[] from, float[] to, float weight) {
        for (int i = 0; i < to.length; i++) to[i] = from[i] + (to[i] - from[i]) * weight;
    }
}
