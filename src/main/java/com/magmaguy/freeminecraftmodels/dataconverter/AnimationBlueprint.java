package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.LoopType;
import com.magmaguy.magmacore.util.Logger;
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
            //Intentional design: a zero-length animation is a valid authoring choice meaning "no
            //animation" (e.g. a static single-pose idle on furniture models), so it is skipped
            //silently rather than warned about as malformed.
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
        duration = AnimationTimeline.durationInTicks((Double) animationData.get("length"));
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
            // Position, rotation and scale may all have a keyframe at the same time.
            // Deduplicate only after separating those independent tracks below.
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

        ikKeyframes.put(controllerName, AnimationTimeline.normalize(keyframes));
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
        interpolateRotations(animationFramesArray, AnimationTimeline.normalize(rotationKeyframes));
        interpolateTranslations(animationFramesArray, AnimationTimeline.normalize(positionKeyframes));
        interpolateScales(animationFramesArray, AnimationTimeline.normalize(scaleKeyframes));

        this.animationFrames.put(boneBlueprint, animationFramesArray);
    }

    private void warnMalformedTimeline(String detail) {
        if (warnedMalformedTimeline) return;
        warnedMalformedTimeline = true;
        Logger.warn("Malformed animation timeline for model " + modelName + ", animation "
                + animationName + ": " + detail + ". Other model animations will continue converting.");
    }

    private void interpolateRotations(AnimationFrame[] animationFramesArray, List<Keyframe> rotationKeyframes) {
        if (rotationKeyframes.isEmpty()) return;
        for (int tick = 0; tick < animationFramesArray.length; tick++) {
            float x = AnimationTimeline.sample(rotationKeyframes, tick, Keyframe::getDataX);
            float y = AnimationTimeline.sample(rotationKeyframes, tick, Keyframe::getDataY);
            float z = AnimationTimeline.sample(rotationKeyframes, tick, Keyframe::getDataZ);
            animationFramesArray[tick].xRotation = (float) Math.toRadians(blockBenchVersion < 5 ? x : -x);
            animationFramesArray[tick].yRotation = (float) Math.toRadians(blockBenchVersion < 5 ? y : -y);
            animationFramesArray[tick].zRotation = (float) Math.toRadians(z);
        }
    }

    private void interpolateTranslations(AnimationFrame[] animationFramesArray, List<Keyframe> positionKeyframes) {
        if (positionKeyframes.isEmpty()) return;
        for (int tick = 0; tick < animationFramesArray.length; tick++) {
            float x = AnimationTimeline.sample(positionKeyframes, tick, Keyframe::getDataX) / 16F;
            animationFramesArray[tick].xPosition = blockBenchVersion < 5 ? x : -x;
            animationFramesArray[tick].yPosition = AnimationTimeline.sample(positionKeyframes, tick, Keyframe::getDataY) / 16F;
            animationFramesArray[tick].zPosition = AnimationTimeline.sample(positionKeyframes, tick, Keyframe::getDataZ) / 16F;
        }
    }

    private void interpolateScales(AnimationFrame[] animationFramesArray, List<Keyframe> scaleKeyframes) {
        if (scaleKeyframes.isEmpty()) return;
        for (int tick = 0; tick < animationFramesArray.length; tick++) {
            animationFramesArray[tick].scaleX = AnimationTimeline.sample(scaleKeyframes, tick, Keyframe::getDataX);
            animationFramesArray[tick].scaleY = AnimationTimeline.sample(scaleKeyframes, tick, Keyframe::getDataY);
            animationFramesArray[tick].scaleZ = AnimationTimeline.sample(scaleKeyframes, tick, Keyframe::getDataZ);
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
        for (int tick = 0; tick < frames.length; tick++) {
            float x = AnimationTimeline.sample(keyframes, tick, Keyframe::getDataX) / 16F;
            frames[tick] = new IKAnimationFrame(
                    blockBenchVersion < 5 ? x : -x,
                    AnimationTimeline.sample(keyframes, tick, Keyframe::getDataY) / 16F,
                    AnimationTimeline.sample(keyframes, tick, Keyframe::getDataZ) / 16F);
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
