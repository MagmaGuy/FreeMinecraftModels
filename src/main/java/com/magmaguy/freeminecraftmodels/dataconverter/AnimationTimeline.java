package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.magmacore.util.MathToolkit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

final class AnimationTimeline {
    private static final double TICKS_PER_SECOND = 20D;

    private AnimationTimeline() {
    }

    static int durationInTicks(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0D) return 0;
        double ticks = Math.ceil(seconds * TICKS_PER_SECOND);
        return ticks >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) ticks;
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

    static float sample(List<Keyframe> keyframes, int tick, ToDoubleFunction<Keyframe> value) {
        if (keyframes.isEmpty()) throw new IllegalArgumentException("Cannot sample an empty animation track");

        Keyframe first = keyframes.get(0);
        if (tick <= first.getExactTimeInTicks()) return (float) value.applyAsDouble(first);

        int low = 0;
        int high = keyframes.size() - 1;
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (keyframes.get(middle).getExactTimeInTicks() <= tick) low = middle;
            else high = middle - 1;
        }

        Keyframe previous = keyframes.get(low);
        if (low == keyframes.size() - 1) return (float) value.applyAsDouble(previous);

        Keyframe next = keyframes.get(low + 1);
        double range = next.getExactTimeInTicks() - previous.getExactTimeInTicks();
        float progress = (float) ((tick - previous.getExactTimeInTicks()) / range);
        return interpolate(next.getInterpolationType(),
                (float) value.applyAsDouble(previous),
                (float) value.applyAsDouble(next),
                progress);
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
