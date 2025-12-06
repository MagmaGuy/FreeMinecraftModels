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
    private final int blockBenchVersion;
    @Getter
    private LoopType loopType;
    @Getter
    private String animationName;
    private SkeletonBlueprint skeletonBlueprint;
    @Getter
    private int duration;

    public AnimationBlueprint(Object data, String modelName, SkeletonBlueprint skeletonBlueprint, int blockBenchVersion) {
        this.blockBenchVersion = blockBenchVersion;
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

        if (animationData.get("animators") == null) return;
        //In BBModel files, each bone holds the data for their transformations, so data is stored from the bone's perspective
        ((Map<String, Object>) animationData.get("animators")).entrySet().forEach(pair -> initializeBones((Map<String, Object>) pair.getValue(), modelName, animationName));

        //Process the keyframes
        try {
            interpolateKeyframes();
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
        String boneName = (String) animationData.get("name");
        BoneBlueprint boneBlueprint = skeletonBlueprint.getBoneMap().get(boneName);
        //hitboxes do not get animated!
        if (boneName.equalsIgnoreCase("hitbox")) return;
        if (boneBlueprint == null) {
            Logger.warn("Failed to get bone " + boneName + " from model " + modelName + "!");
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
        return new Keyframe(original.getTransformationType(), original.getTimeInTicks(), original.getInterpolationType(), original.getDataX(), original.getDataY(), original.getDataZ());
    }

    private void interpolateKeyframes() {
        boneKeyframes.forEach(this::interpolateBoneKeyframes);
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
            //It is possible for frames to go beyond the animation's duration, so we need to clamp that
            if (previousFrame.getTimeInTicks() >= duration) return;
            int durationBetweenKeyframes = Math.min(animationFrame.getTimeInTicks(), duration) - previousFrame.getTimeInTicks();

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                float t = j / (float) durationBetweenKeyframes;

                if (blockBenchVersion < 5) {
                    animationFramesArray[currentFrame].xRotation = interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t);
                    animationFramesArray[currentFrame].yRotation = interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t);
                    animationFramesArray[currentFrame].zRotation = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t);
                } else {
                    animationFramesArray[currentFrame].xRotation = -interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t);
                    animationFramesArray[currentFrame].yRotation = -interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t);
                    animationFramesArray[currentFrame].zRotation = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t);
                }
            }
            previousFrame = animationFrame;
            if (animationFrame.getTimeInTicks() > lastFrame.getTimeInTicks()) lastFrame = animationFrame;
            if (animationFrame.getTimeInTicks() < firstFrame.getTimeInTicks()) firstFrame = animationFrame;
        }
        if (lastFrame != null && lastFrame.getTimeInTicks() < duration - 1) {
            int durationBetweenKeyframes = duration - lastFrame.getTimeInTicks();
            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                if (blockBenchVersion < 5) {
                    animationFramesArray[currentFrame].xRotation = lastFrame.getDataX();
                    animationFramesArray[currentFrame].yRotation = lastFrame.getDataY();
                    animationFramesArray[currentFrame].zRotation = lastFrame.getDataZ();
                } else {
                    animationFramesArray[currentFrame].xRotation = -lastFrame.getDataX();
                    animationFramesArray[currentFrame].yRotation = -lastFrame.getDataY();
                    animationFramesArray[currentFrame].zRotation = lastFrame.getDataZ();
                }
            }
        }
        if (firstFrame != null && firstFrame.getTimeInTicks() > 0) {
            int durationBetweenKeyframes = firstFrame.getTimeInTicks();
            durationBetweenKeyframes = Math.min(durationBetweenKeyframes, duration - 1);
            for (int j = 0; j < durationBetweenKeyframes; j++) {
                if (blockBenchVersion < 5) {
                    animationFramesArray[j].xRotation = firstFrame.getDataX();
                    animationFramesArray[j].yRotation = firstFrame.getDataY();
                    animationFramesArray[j].zRotation = firstFrame.getDataZ();
                } else {
                    animationFramesArray[j].xRotation = -firstFrame.getDataX();
                    animationFramesArray[j].yRotation = -firstFrame.getDataY();
                    animationFramesArray[j].zRotation = firstFrame.getDataZ();
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
            int durationBetweenKeyframes = animationFrame.getTimeInTicks() - previousFrame.getTimeInTicks();

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                float t = j / (float) durationBetweenKeyframes;
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
            int durationBetweenKeyframes = duration - lastFrame.getTimeInTicks();
            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
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
            int durationBetweenKeyframes = firstFrame.getTimeInTicks();
            durationBetweenKeyframes = Math.min(durationBetweenKeyframes, duration - 1);
            for (int j = 0; j < durationBetweenKeyframes; j++) {
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
            int durationBetweenKeyframes = animationFrame.getTimeInTicks() - previousFrame.getTimeInTicks();

            // Use the interpolation type from the current keyframe
            InterpolationType interpType = animationFrame.getInterpolationType();

            for (int j = 0; j < durationBetweenKeyframes; j++) {
                int currentFrame = j + previousFrame.getTimeInTicks();
                float t = j / (float) durationBetweenKeyframes;

                animationFramesArray[currentFrame].scaleX = interpolateWithType(interpType, previousFrame.getDataX(), animationFrame.getDataX(), t);
                animationFramesArray[currentFrame].scaleY = interpolateWithType(interpType, previousFrame.getDataY(), animationFrame.getDataY(), t);
                animationFramesArray[currentFrame].scaleZ = interpolateWithType(interpType, previousFrame.getDataZ(), animationFrame.getDataZ(), t);
            }
            previousFrame = animationFrame;
        }
    }
}
