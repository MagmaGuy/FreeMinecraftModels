package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;
import com.magmaguy.magmacore.util.MathToolkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class AnimationTimeline {
    private static final double TICKS_PER_SECOND = 20D;

    private AnimationTimeline() {
    }

    static int durationInTicks(double seconds) {
        if (!Double.isFinite(seconds)) throw new IllegalArgumentException("Animation length must be finite");
        if (seconds <= 0D) return 0;
        double ticks = Math.ceil(seconds * TICKS_PER_SECOND);
        if (!Double.isFinite(ticks) || ticks >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("Animation length exceeds the supported integer frame range");
        return (int) ticks;
    }

    static List<Keyframe> normalize(List<Keyframe> source) {
        List<Keyframe> sorted = new ArrayList<>(source);
        sorted.sort(Comparator.comparingDouble(Keyframe::getExactTimeInTicks));

        List<Keyframe> normalized = new ArrayList<>(sorted.size());
        for (Keyframe keyframe : sorted) {
            int lastIndex = normalized.size() - 1;
            if (lastIndex >= 0 && Double.compare(
                    normalized.get(lastIndex).getExactTimeInTicks(),
                    keyframe.getExactTimeInTicks()) == 0) {
                normalized.set(lastIndex, keyframe);
            } else {
                normalized.add(keyframe);
            }
        }
        return normalized;
    }

    /**
     * The track's value on one axis at a tick: the first keyframe before it starts, the last after it ends, and
     * between two keyframes both evaluated now and blended by the next one's interpolation. The context is only
     * read by expression keyframes, so a track of plain numbers may pass null.
     */
    static float sample(List<Keyframe> keyframes, int tick, int axis, MolangContext context) {
        if (keyframes.isEmpty()) throw new IllegalArgumentException("Cannot sample an empty animation track");

        Keyframe first = keyframes.get(0);
        if (tick <= first.getExactTimeInTicks()) return (float) first.value(axis, context);

        int low = 0;
        int high = keyframes.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (keyframes.get(middle).getExactTimeInTicks() <= tick) low = middle;
            else high = middle - 1;
        }

        Keyframe previous = keyframes.get(low);
        if (low == keyframes.size() - 1) return (float) previous.value(axis, context);

        Keyframe next = keyframes.get(low + 1);
        double range = next.getExactTimeInTicks() - previous.getExactTimeInTicks();
        float progress = (float) ((tick - previous.getExactTimeInTicks()) / range);
        return interpolate(next.getInterpolationType(),
                (float) previous.value(axis, context),
                (float) next.value(axis, context),
                progress);
    }

    /**
     * Converts a Blockbench value into what the pose mixer reads: degrees to radians, pixels to blocks, scale as
     * is. Format 5 files mirror x and y rotation and x position relative to FMM's internal convention.
     */
    static float toPoseUnits(TransformationType channel, int axis, float value, int blockBenchVersion) {
        boolean mirrored = blockBenchVersion >= 5;
        return switch (channel) {
            case ROTATION -> (float) Math.toRadians(mirrored && axis < 2 ? -value : value);
            case POSITION -> (mirrored && axis == 0 ? -value : value) / 16F;
            case SCALE -> value;
        };
    }

    private static float interpolate(InterpolationType type, float start, float end, float progress) {
        return switch (type) {
            case LINEAR -> MathToolkit.lerp(start, end, progress);
            case CATMULLROM -> MathToolkit.smoothLerp(start, end, progress);
            case BEZIER -> MathToolkit.bezierLerp(start, end, progress, 0.42F, 0.58F);
            case STEP -> MathToolkit.stepLerp(start, end, progress);
        };
    }
}
