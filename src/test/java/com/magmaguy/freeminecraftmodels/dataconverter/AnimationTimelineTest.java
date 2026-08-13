package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnimationTimelineTest {
    @Test
    void samplesDenseBlockbenchFramesWithoutCollapsingTheirTimeline() {
        List<Keyframe> track = AnimationTimeline.normalize(List.of(
                frame(0D, 0F),
                frame(20D / 24D, 10F),
                frame(40D / 24D, 20F)));

        assertEquals(12F, AnimationTimeline.sample(track, 1, Keyframe::getDataX), 0.00001F);
    }

    @Test
    void exactDuplicateTimeUsesLastDeclaredPose() {
        List<Keyframe> track = AnimationTimeline.normalize(List.of(
                frame(0D, 0F),
                frame(1D, 10F),
                frame(1D, 15F),
                frame(2D, 20F)));

        assertEquals(3, track.size());
        assertEquals(15F, AnimationTimeline.sample(track, 1, Keyframe::getDataX));
    }

    @Test
    void terminalKeyframeContributesToLastRuntimeSample() {
        List<Keyframe> track = AnimationTimeline.normalize(List.of(
                frame(0D, 0F),
                frame(20D, 20F)));

        assertEquals(19F, AnimationTimeline.sample(track, 19, Keyframe::getDataX));
    }

    @Test
    void positiveSubTickAnimationStillGetsOneRuntimeFrame() {
        assertEquals(1, AnimationTimeline.durationInTicks(0.01D));
        assertEquals(20, AnimationTimeline.durationInTicks(1D));
        assertEquals(0, AnimationTimeline.durationInTicks(0D));
    }

    private static Keyframe frame(double timeInTicks, float value) {
        return new Keyframe(TransformationType.POSITION, timeInTicks,
                InterpolationType.LINEAR, value, 0F, 0F);
    }
}
