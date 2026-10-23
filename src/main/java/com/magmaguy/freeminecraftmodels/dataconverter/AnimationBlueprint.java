package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.LoopType;
import com.magmaguy.freeminecraftmodels.utils.TransformationType;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;

import java.util.*;

public class AnimationBlueprint {
    @Getter
    private final HashMap<BoneBlueprint, List<Keyframe>> boneKeyframes = new HashMap<>();
    @Getter
    private final HashMap<BoneBlueprint, AnimationFrame[]> animationFrames = new HashMap<>();
    // Channels with a MoLang keyframe, sampled per entity at runtime. Their bone still gets baked frames holding
    // the rest values of those channels, so it counts as keyed: an override animation resets it like any other.
    @Getter
    private final HashMap<BoneBlueprint, List<ExpressionTrack>> expressionTracks = new HashMap<>();
    // IK animation frames keyed by controller (null object) name
    @Getter
    private final HashMap<String, List<Keyframe>> ikKeyframes = new HashMap<>();
    @Getter
    private final HashMap<String, IKAnimationFrame[]> ikAnimationFrames = new HashMap<>();
    @Getter
    private final HashMap<String, ExpressionTrack> ikExpressionTracks = new HashMap<>();
    // Particle keyframes from the Blockbench Effects track, sorted by tick
    @Getter
    private final List<ParticleKeyframe> particleKeyframes = new ArrayList<>();
    private final int blockBenchVersion;
    @Getter
    private LoopType loopType;
    @Getter
    private String animationName;
    private SkeletonBlueprint skeletonBlueprint;
    @Getter
    private int duration;
    // Blockbench's Override: true replaces the pose of the bones this animation keys, false adds on top (Bedrock rule).
    @Getter
    private boolean override;
    // Blockbench's Blend Weight, numbers only: it scales how strongly this animation applies.
    @Getter
    private double blendWeight = 1;
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
        try { initializeGlobalValues(animationData); }
        catch (IllegalArgumentException malformedDuration) {
            warnMalformedTimeline(malformedDuration.getMessage());
            return;
        }
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
        override = Boolean.TRUE.equals(animationData.get("override"));
        blendWeight = parseBlendWeight(animationData.get("blend_weight"));
        List<String> ignored = new ArrayList<>();
        for (String field : new String[]{"start_delay", "loop_delay", "anim_time_update"})
            if (!text(animationData.get(field)).isBlank()) ignored.add(field);
        if (!ignored.isEmpty())
            Logger.warn("Model " + modelName + ", animation " + animationName + " sets " + String.join(", ", ignored)
                    + ". FreeMinecraftModels does not support these and plays the animation without them.");
    }

    private double parseBlendWeight(Object value) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) return number.doubleValue();
        String raw = text(value).trim();
        if (raw.isEmpty()) return 1;
        try {
            double parsed = Double.parseDouble(raw);
            if (Double.isFinite(parsed)) return parsed;
        } catch (NumberFormatException ignored) {
            // A Molang expression; reported below.
        }
        Logger.warn("Model " + modelName + ", animation " + animationName + " has the blend weight '" + raw
                + "'. FreeMinecraftModels only reads plain numbers and uses 1.");
        return 1;
    }

    private void initializeBones(Map<String, Object> animationData, String modelName, String animationName) {
        String name = (String) animationData.get("name");
        String type = (String) animationData.get("type");

        // Check if this is a null_object (IK controller) animation
        if ("null_object".equals(type)) {
            initializeIKAnimation(animationData, name, modelName, animationName);
            return;
        }

        if ("effect".equals(type)) {
            initializeParticleKeyframes(animationData);
            return;
        }

        // Other non-bone animators (sound, timeline scripts) have no FMM runtime animation.
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
            if (!keyframes.isEmpty()) boneKeyframes.put(boneBlueprint, keyframes);
        }
    }

    /**
     * Reads particle keyframes from a Blockbench Effects track. Sound and timeline keyframes on
     * the same track are not played by FMM.
     */
    private void initializeParticleKeyframes(Map<String, Object> animationData) {
        if (!(animationData.get("keyframes") instanceof List<?> keyframes)) return;
        for (Object rawKeyframe : keyframes) {
            if (!(rawKeyframe instanceof Map<?, ?> keyframe) || !"particle".equals(keyframe.get("channel"))) continue;
            if (!(keyframe.get("time") instanceof Number time) || !(keyframe.get("data_points") instanceof List<?> points))
                continue;
            int tick = (int) Math.max(0, Math.min(duration - 1, Math.floor(20 * time.doubleValue())));
            for (Object rawPoint : points) {
                if (!(rawPoint instanceof Map<?, ?> point)) continue;
                String effect = text(point.get("effect"));
                if (effect.isBlank()) continue;
                String file = text(point.get("file")).replace('\\', '/');
                file = file.substring(file.lastIndexOf('/') + 1);
                particleKeyframes.add(new ParticleKeyframe(tick, effect, text(point.get("locator")), file,
                        text(point.get("script"))));
            }
        }
        particleKeyframes.sort(Comparator.comparingInt(ParticleKeyframe::tick));
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
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
        bake(boneBlueprint, animationFramesArray, TransformationType.ROTATION,
                AnimationTimeline.normalize(rotationKeyframes));
        bake(boneBlueprint, animationFramesArray, TransformationType.POSITION,
                AnimationTimeline.normalize(positionKeyframes));
        bake(boneBlueprint, animationFramesArray, TransformationType.SCALE,
                AnimationTimeline.normalize(scaleKeyframes));

        this.animationFrames.put(boneBlueprint, animationFramesArray);
    }

    /** Bakes a channel of plain numbers into the frames; a channel with a MoLang keyframe stays a runtime track. */
    private void bake(BoneBlueprint bone, AnimationFrame[] frames, TransformationType channel, List<Keyframe> keyframes) {
        if (keyframes.isEmpty()) return;
        if (keyframes.stream().anyMatch(Keyframe::isDynamic)) {
            expressionTracks.computeIfAbsent(bone, keyed -> new ArrayList<>())
                    .add(new ExpressionTrack(channel, keyframes, blockBenchVersion));
            return;
        }
        for (int tick = 0; tick < frames.length; tick++) {
            AnimationFrame frame = frames[tick];
            float x = poseValue(keyframes, channel, 0, tick);
            float y = poseValue(keyframes, channel, 1, tick);
            float z = poseValue(keyframes, channel, 2, tick);
            switch (channel) {
                case ROTATION -> {
                    frame.xRotation = x;
                    frame.yRotation = y;
                    frame.zRotation = z;
                }
                case POSITION -> {
                    frame.xPosition = x;
                    frame.yPosition = y;
                    frame.zPosition = z;
                }
                case SCALE -> {
                    frame.scaleX = x;
                    frame.scaleY = y;
                    frame.scaleZ = z;
                }
            }
        }
    }

    private float poseValue(List<Keyframe> keyframes, TransformationType channel, int axis, int tick) {
        return AnimationTimeline.toPoseUnits(channel, axis,
                AnimationTimeline.sample(keyframes, tick, axis, null), blockBenchVersion);
    }

    private void warnMalformedTimeline(String detail) {
        if (warnedMalformedTimeline) return;
        warnedMalformedTimeline = true;
        Logger.warn("Malformed animation timeline for model " + modelName + ", animation "
                + animationName + ": " + detail + ". Other model animations will continue converting.");
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
     * Interpolates position keyframes for an IK controller into per-frame goal offsets, or keeps a controller
     * keyed with MoLang as a runtime track.
     */
    private void interpolateIKControllerKeyframes(String controllerName, List<Keyframe> keyframes) {
        if (keyframes.isEmpty()) return;
        if (keyframes.stream().anyMatch(Keyframe::isDynamic)) {
            ikExpressionTracks.put(controllerName,
                    new ExpressionTrack(TransformationType.POSITION, keyframes, blockBenchVersion));
            return;
        }

        IKAnimationFrame[] frames = new IKAnimationFrame[duration];
        for (int tick = 0; tick < frames.length; tick++) {
            frames[tick] = new IKAnimationFrame(
                    poseValue(keyframes, TransformationType.POSITION, 0, tick),
                    poseValue(keyframes, TransformationType.POSITION, 1, tick),
                    poseValue(keyframes, TransformationType.POSITION, 2, tick));
        }

        ikAnimationFrames.put(controllerName, frames);
    }

    /**
     * Checks if this animation has any IK animations.
     *
     * @return true if this animation has IK controllers
     */
    public boolean hasIKAnimations() {
        return !ikAnimationFrames.isEmpty() || !ikExpressionTracks.isEmpty();
    }
}
