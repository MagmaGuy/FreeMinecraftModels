package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.particles.molang.MolangContext;
import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.freeminecraftmodels.utils.Splines;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.magmaguy.freeminecraftmodels.dataconverter.Keyframe.POST;
import static com.magmaguy.freeminecraftmodels.dataconverter.Keyframe.PRE;

final class AnimationTimeline {
    private static final double TICKS_PER_SECOND = 20D;
    // Blockbench shows a keyframe as is when the playhead is within 1/1200 s of it.
    private static final double ON_KEYFRAME_TICKS = TICKS_PER_SECOND / 1200D;
    // Halvings of a Bezier segment's curve parameter; 40 put the error far below a float's precision.
    private static final int BEZIER_SOLVER_STEPS = 40;

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
     * The track's value on one axis at a tick, computed the way Blockbench 5.2 previews it: the first keyframe
     * before it starts, the last after it ends, and between two keyframes a curve chosen from both of them.
     * <ul>
     *     <li>A step keyframe holds its value until the next keyframe.</li>
     *     <li>A linear keyframe followed by a linear or step one blends in a straight line.</li>
     *     <li>When either keyframe is smooth (catmullrom), a Catmull-Rom curve passes through the keyframes on
     *     both sides; a looping animation continues that curve across the loop.</li>
     *     <li>Otherwise one of them is Bezier, and the curve follows the keyframes' Bezier handles.</li>
     * </ul>
     * The context is only read by expression keyframes, so a track of plain numbers may pass null.
     *
     * @param keyframes the normalized keyframes of one channel
     * @param looping   whether the animation loops in Blockbench
     */
    static float sample(List<Keyframe> keyframes, int tick, int axis, MolangContext context, boolean looping) {
        if (keyframes.isEmpty()) throw new IllegalArgumentException("Cannot sample an empty animation track");

        int afterIndex = firstAtOrAfter(keyframes, tick);
        Keyframe before = afterIndex > 0 ? keyframes.get(afterIndex - 1) : null;
        Keyframe after = afterIndex < keyframes.size() ? keyframes.get(afterIndex) : null;
        if (before != null && tick - before.getExactTimeInTicks() < ON_KEYFRAME_TICKS)
            return (float) before.value(axis, PRE, context);
        if (after != null && after.getExactTimeInTicks() - tick < ON_KEYFRAME_TICKS)
            return (float) after.value(axis, PRE, context);
        if (before == null) return (float) after.value(axis, PRE, context);
        if (after == null || before.getInterpolationType() == InterpolationType.STEP)
            return (float) before.value(axis, POST, context);

        InterpolationType leaving = before.getInterpolationType(), arriving = after.getInterpolationType();
        if (leaving == InterpolationType.LINEAR
                && (arriving == InterpolationType.LINEAR || arriving == InterpolationType.STEP)) {
            double progress = (tick - before.getExactTimeInTicks())
                    / (after.getExactTimeInTicks() - before.getExactTimeInTicks());
            double start = before.value(axis, POST, context);
            return (float) (start + (after.value(axis, PRE, context) - start) * progress);
        }
        if (leaving == InterpolationType.CATMULLROM || arriving == InterpolationType.CATMULLROM)
            return (float) catmullRom(keyframes, afterIndex, tick, axis, context, looping);
        return (float) bezier(before, after, tick, axis, context);
    }

    /** The index of the first keyframe at or after the tick, or the track's size when every keyframe is before it. */
    private static int firstAtOrAfter(List<Keyframe> keyframes, int tick) {
        int low = 0;
        int high = keyframes.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (keyframes.get(middle).getExactTimeInTicks() < tick) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    /**
     * Blockbench's smooth segment: a uniform Catmull-Rom curve through the values of the keyframe before the
     * segment, its two keyframes and the one after it. A missing neighbour repeats the segment's own end, a
     * looping track borrows the keyframes next to its far end, and a keyframe with separate pre and post values
     * cuts the curve off on its side.
     */
    private static double catmullRom(List<Keyframe> keyframes, int afterIndex, int tick, int axis,
                                     MolangContext context, boolean looping) {
        int count = keyframes.size();
        Keyframe before = keyframes.get(afterIndex - 1), after = keyframes.get(afterIndex);
        Keyframe lead = afterIndex >= 2 ? keyframes.get(afterIndex - 2) : null;
        Keyframe trail = afterIndex + 1 < count ? keyframes.get(afterIndex + 1) : null;
        if (looping && count >= 3) {
            // The last keyframe of a loop stands for the first, so the curve wraps through their neighbours.
            if (lead == null) lead = keyframes.get(count - 2);
            if (trail == null) trail = keyframes.get(1);
        }
        if (before.getPointCount() > 1) lead = null;
        if (after.getPointCount() > 1) trail = null;

        // Read in Blockbench's order. The neighbours only shape this segment, so they keep no variable assignment.
        double leadValue = lead == null ? Double.NaN : lead.valueWithoutAssignments(axis, POST, context);
        double start = before.value(axis, POST, context);
        double end = after.value(axis, PRE, context);
        double trailValue = trail == null ? Double.NaN : trail.valueWithoutAssignments(axis, PRE, context);
        double progress = (tick - before.getExactTimeInTicks())
                / (after.getExactTimeInTicks() - before.getExactTimeInTicks());
        return Splines.catmullRom(progress, lead == null ? start : leadValue, start, end,
                trail == null ? end : trailValue);
    }

    /**
     * Blockbench's Bezier segment: a cubic curve over time and value from the leaving keyframe's right handle to
     * the arriving keyframe's left handle. Each handle's time stays inside the segment, so time only grows along
     * the curve and halving the curve parameter finds the point at this tick.
     */
    private static double bezier(Keyframe before, Keyframe after, int tick, int axis, MolangContext context) {
        double start = before.value(axis, POST, context);
        double end = after.value(axis, PRE, context);
        double startTime = before.getExactTimeInTicks(), endTime = after.getExactTimeInTicks();
        double gap = endTime - startTime;
        double startHandleTime = startTime + Math.max(0, Math.min(gap, before.getBezierRightTime(axis)));
        double endHandleTime = endTime + Math.max(-gap, Math.min(0, after.getBezierLeftTime(axis)));
        double low = 0, high = 1;
        for (int step = 0; step < BEZIER_SOLVER_STEPS; step++) {
            double middle = (low + high) / 2;
            if (Splines.cubicBezier(startTime, startHandleTime, endHandleTime, endTime, middle) < tick) low = middle;
            else high = middle;
        }
        return Splines.cubicBezier(start, start + before.getBezierRightValue(axis),
                end + after.getBezierLeftValue(axis), end, (low + high) / 2);
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
}
