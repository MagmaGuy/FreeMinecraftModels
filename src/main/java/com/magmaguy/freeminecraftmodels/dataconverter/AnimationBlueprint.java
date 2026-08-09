package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.InterpolationType;
import com.magmaguy.freeminecraftmodels.utils.LoopType;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.MathToolkit;
import lombok.Getter;

import java.util.*;

public class AnimationBlueprint {
    @Getter
    private final HashMap<BoneBlueprint, List<Keyframe>> boneKeyframes = new HashMap<>();
    @Getter
    private final HashMap<BoneBlueprint, AnimationFrame[]> animationFrames = new HashMap<>();
    // IK animation frames keyed by controller (null object) name
    @Getter
    private final HashMap<String, List<Keyframe>> ikKeyframes = new HashMap<>();
    @Getter
    private final HashMap<String, IKAnimationFrame[]> ikAnimationFrames = new HashMap<>();
    private final int blockBenchVersion;
    @Getter
    private LoopType loopType;
    @Getter
    private String animationName;
    private SkeletonBlueprint skeletonBlueprint;
    @Getter
    private int duration;
    private String modelName;
    private boolean warnedMalformedTimeline;

    public AnimationBlueprint(Object data, String modelName, SkeletonBlueprint skeletonBlueprint, int blockBenchVersion) {
        this.blockBenchVersion = blockBenchVersion;
        this.modelName = modelName;
        Map<String, Object> animationData;
        try {
            animationData = (Map<String, Object>) data;
        } catch (Exception e) {
            Logger.warn("Failed to get animation data! Model format is not as expected, this version of BlockBench is not compatible with FreeMinecraftModels!");
            e.printStackTrace();
            return;
        }

        this.skeletonBlueprint = skeletonBlueprint;
        initializeGlobalValues(animationData);
        if (duration <= 0) {
            warnMalformedTimeline("animation duration is " + duration + " tick(s); the animation was skipped");
            return;
        }

        if (animationData.get("animators") == null) return;
        //In BBModel files, each bone holds the data for their transformations, so data is stored from the bone's perspective
        for (Map.Entry<String, Object> pair : ((Map<String, Object>) animationData.get("animators")).entrySet()) {
            try {
                initializeBones((Map<String, Object>) pair.getValue(), modelName, animationName);
            } catch (RuntimeException exception) {
                warnMalformedTimeline("animator " + pair.getKey() + " could not be read: "
                        + exception.getClass().getSimpleName());
            }
        }

        //Process the keyframes
        try {
            interpolateKeyframes();
            interpolateIKKeyframes();
        } catch (Exception e) {
            Logger.warn("Failed to interpolate animations for model " + modelName + "! Animation name: " + animationName);
            e.printStackTrace();
        }
    }

    private void initializeGlobalValues(Map<String, Object> animationData) {
        //Parse global data for animation
        animationName = (String) animationData.get("name");
        loopType = LoopType.valueOf(((String) animationData.get("loop")).toUpperCase());
        duration = (int) (20 * (Double) animationData.get("length"));
    }

    private void initializeBones(Map<String, Object> animationData, String modelName, String animationName) {
        String name = (String) animationData.get("name");
        String type = (String) animationData.get("type");

        // Check if this is a null_object (IK controller) animation
        if ("null_object".equals(type)) {
            initializeIKAnimation(animationData, name, modelName, animationName);
            return;
        }

        // Blockbench effect tracks are not skeleton bones and have no FMM runtime animation.
        if (type != null && !"bone".equals(type)) return;

        // Standard bone animation
        BoneBlueprint boneBlueprint = skeletonBlueprint.getBoneMap().get(name);
        //hitboxes do not get animated!
        if (name.equalsIgnoreCase("hitbox")) return;
        if (boneBlueprint == null) {
            Logger.warn("Failed to get bone " + name + " from model " + modelName + "!");
            return;
        }
        if (animationData.get("keyframes") != null) {
        List<Keyframe> keyframes = new ArrayList<>();
        for (Object keyframeData : (List) animationData.get("keyframes")) {
            keyframes.add(new Keyframe(keyframeData, modelName, animationName));
        }
        keyframes.sort(Comparator.comparingInt(Keyframe::getTimeInTicks));

        // Add boundary keyframes if needed and there are at least 2 keyframes for interpolation
        if (keyframes.size() >= 2) {
            addBoundaryKeyframes(keyframes);
        }

            boneKeyframes.put(boneBlueprint, keyframes);
        }
    }

    /**
     * Initializes IK animation for a null object controller.
     * Only position keyframes are relevant for IK (they become the IK goal offset).
     */
    private void initializeIKAnimation(Map<String, Object> animationData, String controllerName, String modelName, String animationName) {
        if (animationData.get("keyframes") == null) {
            return;
        }

        List<Keyframe> keyframes = new ArrayList<>();
        for (Object keyframeData : (List) animationData.get("keyframes")) {
            Keyframe keyframe = new Keyframe(keyframeData, modelName, animationName);
            // Only use position keyframes for IK (they define the goal offset)
            if (keyframe.getTransformationType() == com.magmaguy.freeminecraftmodels.utils.TransformationType.POSITION) {
                keyframes.add(keyframe);
            }
        }

        if (keyframes.isEmpty()) {
            return;
        }

        keyframes.sort(Comparator.comparingInt(Keyframe::getTimeInTicks));

        // Add boundary keyframes if needed
        if (keyframes.size() >= 2) {
            addBoundaryKeyframes(keyframes);
        }

        ikKeyframes.put(controllerName, keyframes);
    }

    /**
     * Adds keyframes at tick 0 and the last tick if they don't exist.
     * This ensures smooth interpolation at the boundaries.
     */
    private void addBoundaryKeyframes(List<Keyframe> keyframes) {
        // Check if we need a keyframe at tick 0
        if (keyframes.get(0).getTimeInTicks() > 0) {
            // Clone the first keyframe but set its time to 0
            Keyframe firstKeyframe = keyframes.get(0);
            Keyframe startKeyframe = cloneKeyframeAtTime(firstKeyframe, 0);
            keyframes.add(0, startKeyframe);
        }

        // Check if we need a keyframe at the last tick
        Keyframe lastKeyframe = keyframes.get(keyframes.size() - 1);
        if (lastKeyframe.getTimeInTicks() < duration - 1) {
            // Clone the last keyframe but set its time to duration - 1
            Keyframe endKeyframe = cloneKeyframeAtTime(lastKeyframe, duration - 1);
            keyframes.add(endKeyframe);
        }
    }

    /**
     * Creates a copy of a keyframe at a specific time.
     * This assumes your Keyframe class has appropriate constructors or setters.
     */
    private Keyframe cloneKeyframeAtTime(Keyframe original, int newTime) {
        return new Keyframe(original.getTransformationType(), newTime, original.getInterpolationType(), original.getDataX(), original.getDataY(), original.getDataZ());
    }

    private void interpolateKeyframes() {
        boneKeyframes.forEach((bone, keyframes) -> {
            try {
                interpolateBoneKeyframes(bone, keyframes);
            } catch (RuntimeException exception) {
                warnMalformedTimeline("bone " + bone.getOriginalBoneName() + " could not be interpolated: "
                        + exception.getClass().getSimpleName());
            }
        });
    }

    private void interpolateBoneKeyframes(BoneBlueprint boneBlueprint, List<Keyframe> keyframes) {
        List<Keyframe> rotationKeyframes = new ArrayList<>();
        List<Keyframe> positionKeyframes = new ArrayList<>();
        List<Keyframe> scaleKeyframes = new ArrayList<>();
        for (Keyframe keyframe : keyframes) {
            switch (keyframe.getTransformationType()) {
                case ROTATION -> rotationKeyframes.add(keyframe);
                case POSITION -> positionKeyframes.add(keyframe);
                case SCALE -> scaleKeyframes.add(keyframe);
            }
        }

        AnimationFrame[] animationFramesArray = new AnimationFrame[duration];
        for (int i = 0; i < animationFramesArray.length; i++)
            animationFramesArray[i] = new AnimationFrame();

        //Interpolation time
        interpolateRotations(animationFramesArray, rotationKeyframes);
        interpolateTranslations(animationFramesArray, positionKeyframes);
        interpolateScales(animationFramesArray, scaleKeyframes);

        this.animationFrames.put(boneBlueprint, animationFramesArray);
    }

    /**
     * Helper method to call the appropriate interpolation based on type
     */
    private float interpolateWithType(InterpolationType type, float start, float end, float t) {
        switch (type) {
            case LINEAR -> {
                return MathToolkit.lerp(start, end, t);
            }
            case CATMULLROM -> {
                return MathToolkit.smoothLerp(start, end, t);
            }
            case BEZIER -> {
                // You can adjust these control points or make them configurable
                return MathToolkit.bezierLerp(start, end, t, 0.42f, 0.58f); // ease-in-out preset
            }
            case STEP -> {
                return MathToolkit.stepLerp(start, end, t);
            }
            default -> {
                return MathToolkit.lerp(start, end, t); // fallback to linear
            }
        }
    }

    private FrameRange boundedFrameRange(int sourceStart, int sourceEnd, int frameCount, String track) {
        if (sourceEnd <= sourceStart) {
            warnMalformedTimeline(track + " has a non-increasing keyframe range "
                    + sourceStart + ".." + sourceEnd + "; the range was skipped");
            return null;
        }
        if (frameCount <= 0 || sourceEnd <= 0 || sourceStart >= frameCount) {
            warnMalformedTimeline(track + " range " + sourceStart + ".." + sourceEnd
                    + " is outside 0.." + Math.max(0, frameCount - 1) + "; the range was skipped");
            return null;
        }

        int boundedStart = Math.max(0, sourceStart);
        int boundedEnd = Math.min(frameCount, sourceEnd);
        if (boundedStart != sourceStart || boundedEnd != sourceEnd) {
            warnMalformedTimeline(track + " range " + sourceStart + ".." + sourceEnd
                    + " exceeded 0.." + (frameCount - 1) + " and was bounded");
        }
        return new FrameRange(boundedStart, boundedEnd, sourceStart, sourceEnd - sourceStart);
    }

    private void warnMalformedTimeline(String detail) {
        if (warnedMalformedTimeline) return;
        warnedMalformedTimeline = true;
        Logger.warn("Malformed animation timeline for model " + modelName + ", animation "
                + animationName + ": " + detail + ". Other model animations will continue converting.");
    }

    private record FrameRange(int startInclusive, int endExclusive, int sourceStart, int sourceLength) {
    }

    private void interpolateRotations(AnimationFrame[] animationFramesArray, List<Keyframe> rotationKeyframes) {
        Keyframe firstFrame = null;
        Keyframe previousFrame = null;
        Keyframe lastFrame = null;
        for (int i = 0; i < rotationKeyframes.size(); i++) {
            Keyframe animationFrame = rotationKeyframes.get(i);
            if (i == 0) {
                firstFrame = animationFrame;
                previousFrame = animationFrame;
                lastFrame = animationFrame;
                continue;
            }
            FrameRange range = boundedFrameRange(previousFrame.getTimeInTicks(),
                    animationFrame.getTimeInTicks(), animationFramesArray.length, "rotation");

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            if (range != null) for (int currentFrame = range.startInclusive();
                                    currentFrame < range.endExclusive(); currentFrame++) {
                int sourceOffset = currentFrame - range.sourceStart();
                float t = range.sourceLength() > 1
                        ? sourceOffset / (float) (range.sourceLength() - 1) : 0f;

                // Pre-convert degrees to radians once at load time so the per-tick
                // animation path can use the value directly without per-frame Math.toRadians().
                if (blockBenchVersion < 5) {
                    animationFramesArray[currentFrame].xRotation = (float) Math.toRadians(interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t));
                    animationFramesArray[currentFrame].yRotation = (float) Math.toRadians(interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t));
                    animationFramesArray[currentFrame].zRotation = (float) Math.toRadians(interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t));
                } else {
                    animationFramesArray[currentFrame].xRotation = (float) Math.toRadians(-interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t));
                    animationFramesArray[currentFrame].yRotation = (float) Math.toRadians(-interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t));
                    animationFramesArray[currentFrame].zRotation = (float) Math.toRadians(interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t));
                }
            }
            previousFrame = animationFrame;
            if (animationFrame.getTimeInTicks() > lastFrame.getTimeInTicks()) lastFrame = animationFrame;
            if (animationFrame.getTimeInTicks() < firstFrame.getTimeInTicks()) firstFrame = animationFrame;
        }
        if (lastFrame != null && lastFrame.getTimeInTicks() < duration - 1) {
            FrameRange range = boundedFrameRange(lastFrame.getTimeInTicks(), duration,
                    animationFramesArray.length, "rotation tail");
            if (range != null) for (int currentFrame = range.startInclusive();
                                    currentFrame < range.endExclusive(); currentFrame++) {
                if (blockBenchVersion < 5) {
                    animationFramesArray[currentFrame].xRotation = (float) Math.toRadians(lastFrame.getDataX());
                    animationFramesArray[currentFrame].yRotation = (float) Math.toRadians(lastFrame.getDataY());
                    animationFramesArray[currentFrame].zRotation = (float) Math.toRadians(lastFrame.getDataZ());
                } else {
                    animationFramesArray[currentFrame].xRotation = (float) Math.toRadians(-lastFrame.getDataX());
                    animationFramesArray[currentFrame].yRotation = (float) Math.toRadians(-lastFrame.getDataY());
                    animationFramesArray[currentFrame].zRotation = (float) Math.toRadians(lastFrame.getDataZ());
                }
            }
        }
        if (firstFrame != null && firstFrame.getTimeInTicks() > 0) {
            FrameRange range = boundedFrameRange(0, firstFrame.getTimeInTicks(),
                    animationFramesArray.length, "rotation prefix");
            if (range != null) for (int j = range.startInclusive(); j < range.endExclusive(); j++) {
                if (blockBenchVersion < 5) {
                    animationFramesArray[j].xRotation = (float) Math.toRadians(firstFrame.getDataX());
                    animationFramesArray[j].yRotation = (float) Math.toRadians(firstFrame.getDataY());
                    animationFramesArray[j].zRotation = (float) Math.toRadians(firstFrame.getDataZ());
                } else {
                    animationFramesArray[j].xRotation = (float) Math.toRadians(-firstFrame.getDataX());
                    animationFramesArray[j].yRotation = (float) Math.toRadians(-firstFrame.getDataY());
                    animationFramesArray[j].zRotation = (float) Math.toRadians(firstFrame.getDataZ());
                }
            }
        }
    }

    private void interpolateTranslations(AnimationFrame[] animationFramesArray, List<Keyframe> positionKeyframes) {
        Keyframe firstFrame = null;
        Keyframe previousFrame = null;
        Keyframe lastFrame = null;
        for (int i = 0; i < positionKeyframes.size(); i++) {
            Keyframe animationFrame = positionKeyframes.get(i);
            if (i == 0) {
                firstFrame = animationFrame;
                previousFrame = animationFrame;
                lastFrame = animationFrame;
                continue;
            }
            FrameRange range = boundedFrameRange(previousFrame.getTimeInTicks(),
                    animationFrame.getTimeInTicks(), animationFramesArray.length, "translation");

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            if (range != null) for (int currentFrame = range.startInclusive();
                                    currentFrame < range.endExclusive(); currentFrame++) {
                int sourceOffset = currentFrame - range.sourceStart();
                float t = range.sourceLength() > 1
                        ? sourceOffset / (float) (range.sourceLength() - 1) : 0f;
                if (blockBenchVersion < 5) {
                    animationFramesArray[currentFrame].xPosition = interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t) / 16f;
                    animationFramesArray[currentFrame].yPosition = interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t) / 16f;
                    animationFramesArray[currentFrame].zPosition = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t) / 16f;
                } else {
                    animationFramesArray[currentFrame].xPosition = -interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t) / 16f;
                    animationFramesArray[currentFrame].yPosition = interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t) / 16f;
                    animationFramesArray[currentFrame].zPosition = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t) / 16f;
                }
            }
            previousFrame = animationFrame;
            if (animationFrame.getTimeInTicks() > lastFrame.getTimeInTicks()) lastFrame = animationFrame;
            if (animationFrame.getTimeInTicks() < firstFrame.getTimeInTicks()) firstFrame = animationFrame;
        }
        if (lastFrame != null && lastFrame.getTimeInTicks() < duration - 1) {
            FrameRange range = boundedFrameRange(lastFrame.getTimeInTicks(), duration,
                    animationFramesArray.length, "translation tail");
            if (range != null) for (int currentFrame = range.startInclusive();
                                    currentFrame < range.endExclusive(); currentFrame++) {
                if (blockBenchVersion < 5) {
                animationFramesArray[currentFrame].xPosition = lastFrame.getDataX() / 16f;
                animationFramesArray[currentFrame].yPosition = lastFrame.getDataY() / 16f;
                animationFramesArray[currentFrame].zPosition = lastFrame.getDataZ() / 16f;
                } else {
                    animationFramesArray[currentFrame].xPosition = -lastFrame.getDataX() / 16f;
                    animationFramesArray[currentFrame].yPosition = lastFrame.getDataY() / 16f;
                    animationFramesArray[currentFrame].zPosition = lastFrame.getDataZ() / 16f;
                }
            }
        }
        if (firstFrame != null && firstFrame.getTimeInTicks() > 0) {
            FrameRange range = boundedFrameRange(0, firstFrame.getTimeInTicks(),
                    animationFramesArray.length, "translation prefix");
            if (range != null) for (int j = range.startInclusive(); j < range.endExclusive(); j++) {
                if (blockBenchVersion < 5) {

                animationFramesArray[j].xPosition = firstFrame.getDataX() / 16f;
                animationFramesArray[j].yPosition = firstFrame.getDataY() / 16f;
                    animationFramesArray[j].zPosition = firstFrame.getDataZ() / 16f;
                } else {
                    animationFramesArray[j].xPosition = -firstFrame.getDataX() / 16f;
                    animationFramesArray[j].yPosition = firstFrame.getDataY() / 16f;
                    animationFramesArray[j].zPosition = firstFrame.getDataZ() / 16f;
                }
            }
        }
    }

    private void interpolateScales(AnimationFrame[] animationFramesArray, List<Keyframe> scaleKeyframes) {
        Keyframe previousFrame = null;
        for (int i = 0; i < scaleKeyframes.size(); i++) {
            Keyframe animationFrame = scaleKeyframes.get(i);
            if (i == 0) {
                previousFrame = animationFrame;
                continue;
            }
            FrameRange range = boundedFrameRange(previousFrame.getTimeInTicks(),
                    animationFrame.getTimeInTicks(), animationFramesArray.length, "scale");

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            if (range != null) for (int currentFrame = range.startInclusive();
                                    currentFrame < range.endExclusive(); currentFrame++) {
                int sourceOffset = currentFrame - range.sourceStart();
                float t = range.sourceLength() > 1
                        ? sourceOffset / (float) (range.sourceLength() - 1) : 0f;

                animationFramesArray[currentFrame].scaleX = interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t);
                animationFramesArray[currentFrame].scaleY = interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t);
                animationFramesArray[currentFrame].scaleZ = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t);
            }
            previousFrame = animationFrame;
        }
    }

    /**
     * Interpolates IK keyframes for all IK controllers.
     */
    private void interpolateIKKeyframes() {
        ikKeyframes.forEach((controller, keyframes) -> {
            try {
                interpolateIKControllerKeyframes(controller, keyframes);
            } catch (RuntimeException exception) {
                warnMalformedTimeline("IK controller " + controller + " could not be interpolated: "
                        + exception.getClass().getSimpleName());
            }
        });
    }

    /**
     * Interpolates position keyframes for an IK controller into per-frame goal offsets.
     */
    private void interpolateIKControllerKeyframes(String controllerName, List<Keyframe> keyframes) {
        if (keyframes.isEmpty()) return;

        IKAnimationFrame[] frames = new IKAnimationFrame[duration];
        for (int i = 0; i < frames.length; i++) {
            frames[i] = new IKAnimationFrame();
        }

        Keyframe firstFrame = null;
        Keyframe previousFrame = null;
        Keyframe lastFrame = null;

        for (int i = 0; i < keyframes.size(); i++) {
            Keyframe keyframe = keyframes.get(i);
            if (i == 0) {
                firstFrame = keyframe;
                previousFrame = keyframe;
                lastFrame = keyframe;
                continue;
            }

            FrameRange range = boundedFrameRange(previousFrame.getTimeInTicks(),
                    keyframe.getTimeInTicks(), frames.length, "IK " + controllerName);
            InterpolationType interpType = keyframe.getInterpolationType();

            if (range != null) for (int currentFrame = range.startInclusive();
                                    currentFrame < range.endExclusive(); currentFrame++) {
                int sourceOffset = currentFrame - range.sourceStart();
                float t = range.sourceLength() > 1
                        ? sourceOffset / (float) (range.sourceLength() - 1) : 0f;

                // Interpolate position (IK goal offset)
                // Apply coordinate system conversion similar to bone positions
                if (blockBenchVersion < 5) {
                    frames[currentFrame].goalX = interpolateWithType(interpType, previousFrame.getDataX(), keyframe.getDataX(), t) / 16f;
                    frames[currentFrame].goalY = interpolateWithType(interpType, previousFrame.getDataY(), keyframe.getDataY(), t) / 16f;
                    frames[currentFrame].goalZ = interpolateWithType(interpType, previousFrame.getDataZ(), keyframe.getDataZ(), t) / 16f;
                } else {
                    frames[currentFrame].goalX = -interpolateWithType(interpType, previousFrame.getDataX(), keyframe.getDataX(), t) / 16f;
                    frames[currentFrame].goalY = interpolateWithType(interpType, previousFrame.getDataY(), keyframe.getDataY(), t) / 16f;
                    frames[currentFrame].goalZ = interpolateWithType(interpType, previousFrame.getDataZ(), keyframe.getDataZ(), t) / 16f;
                }
            }

            previousFrame = keyframe;
            if (keyframe.getTimeInTicks() > lastFrame.getTimeInTicks()) lastFrame = keyframe;
            if (keyframe.getTimeInTicks() < firstFrame.getTimeInTicks()) firstFrame = keyframe;
        }

        // Fill remaining frames with last keyframe values
        if (lastFrame != null && lastFrame.getTimeInTicks() < duration - 1) {
            FrameRange range = boundedFrameRange(lastFrame.getTimeInTicks(), duration,
                    frames.length, "IK tail " + controllerName);
            if (range != null) for (int currentFrame = range.startInclusive();
                                    currentFrame < range.endExclusive(); currentFrame++) {
                if (blockBenchVersion < 5) {
                    frames[currentFrame].goalX = lastFrame.getDataX() / 16f;
                    frames[currentFrame].goalY = lastFrame.getDataY() / 16f;
                    frames[currentFrame].goalZ = lastFrame.getDataZ() / 16f;
                } else {
                    frames[currentFrame].goalX = -lastFrame.getDataX() / 16f;
                    frames[currentFrame].goalY = lastFrame.getDataY() / 16f;
                    frames[currentFrame].goalZ = lastFrame.getDataZ() / 16f;
                }
            }
        }

        // Fill frames before first keyframe
        if (firstFrame != null && firstFrame.getTimeInTicks() > 0) {
            FrameRange range = boundedFrameRange(0, firstFrame.getTimeInTicks(),
                    frames.length, "IK prefix " + controllerName);
            if (range != null) for (int j = range.startInclusive(); j < range.endExclusive(); j++) {
                if (blockBenchVersion < 5) {
                    frames[j].goalX = firstFrame.getDataX() / 16f;
                    frames[j].goalY = firstFrame.getDataY() / 16f;
                    frames[j].goalZ = firstFrame.getDataZ() / 16f;
                } else {
                    frames[j].goalX = -firstFrame.getDataX() / 16f;
                    frames[j].goalY = firstFrame.getDataY() / 16f;
                    frames[j].goalZ = firstFrame.getDataZ() / 16f;
                }
            }
        }

        ikAnimationFrames.put(controllerName, frames);
    }

    /**
     * Checks if this animation has any IK animations.
     *
     * @return true if this animation has IK controllers
     */
    public boolean hasIKAnimations() {
        return !ikAnimationFrames.isEmpty();
    }
}
