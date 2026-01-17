package com.magmaguy.freeminecraftmodels.dataconverter;

import lombok.Getter;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a complete IK chain definition from a Blockbench model.
 * An IK chain consists of:
 * - A series of bones from root (ik_source) to tip
 * - A target position (from a locator or bone)
 * - A controller (null object) that animates the chain
 *
 * The chain is built by walking UP from the target to the source bone,
 * then reversing to get root-to-tip order (as Blockbench does).
 */
public class IKChainBlueprint {
    @Getter
    private final List<BoneBlueprint> chainBones;
    @Getter
    private final NullObjectBlueprint controller;
    @Getter
    private final LocatorBlueprint targetLocator;
    @Getter
    private final BoneBlueprint targetBone;
    @Getter
    private final float[] boneLengths;
    @Getter
    private final Vector3f endEffectorPosition;

    /**
     * Creates an IK chain with a locator as the target.
     *
     * @param chainBones    Ordered list of bones from root to tip
     * @param targetLocator The locator defining the end effector position
     * @param controller    The null object controlling this chain
     */
    public IKChainBlueprint(List<BoneBlueprint> chainBones, LocatorBlueprint targetLocator, NullObjectBlueprint controller) {
        this.chainBones = Collections.unmodifiableList(new ArrayList<>(chainBones));
        this.targetLocator = targetLocator;
        this.targetBone = null;
        this.controller = controller;
        // Set endEffectorPosition BEFORE calculateBoneLengths() since it's used there
        this.endEffectorPosition = targetLocator.getModelSpacePosition();
        this.boneLengths = calculateBoneLengths();
    }

    /**
     * Creates an IK chain with a bone as the target.
     *
     * @param chainBones Ordered list of bones from root to tip
     * @param targetBone The bone defining the end effector position (uses its pivot)
     * @param controller The null object controlling this chain
     */
    public IKChainBlueprint(List<BoneBlueprint> chainBones, BoneBlueprint targetBone, NullObjectBlueprint controller) {
        this.chainBones = Collections.unmodifiableList(new ArrayList<>(chainBones));
        this.targetLocator = null;
        this.targetBone = targetBone;
        this.controller = controller;
        // Set endEffectorPosition BEFORE calculateBoneLengths() since it's used there
        // Use the target bone's pivot as end effector position, fallback to origin if null
        Vector3f pivot = targetBone.getBlueprintModelPivot();
        this.endEffectorPosition = pivot != null ? pivot : new Vector3f(0, 0, 0);
        this.boneLengths = calculateBoneLengths();
    }

    // Minimum bone length threshold - bones shorter than this are considered "zero length"
    private static final float MIN_BONE_LENGTH = 0.001f;

    /**
     * Calculates the length of each bone in the chain.
     * Bone length is the distance from this bone's pivot to the next bone's pivot.
     * If bones share the same pivot (common in Blockbench IK setups), the total
     * chain length is distributed evenly across all bones.
     *
     * @return Array of bone lengths
     */
    private float[] calculateBoneLengths() {
        if (chainBones.isEmpty()) {
            return new float[0];
        }

        float[] lengths = new float[chainBones.size()];

        // First, try to calculate lengths from pivot-to-pivot distances
        for (int i = 0; i < chainBones.size(); i++) {
            Vector3f currentPivot = chainBones.get(i).getBlueprintModelPivot();
            if (currentPivot == null) {
                currentPivot = new Vector3f(0, 0, 0);
            }

            Vector3f nextPivot;
            if (i < chainBones.size() - 1) {
                nextPivot = chainBones.get(i + 1).getBlueprintModelPivot();
                if (nextPivot == null) {
                    nextPivot = new Vector3f(0, 0, 0);
                }
            } else {
                nextPivot = endEffectorPosition;
                if (nextPivot == null) {
                    nextPivot = new Vector3f(0, 0, 0);
                }
            }

            lengths[i] = currentPivot.distance(nextPivot);
        }

        // Check if any bones have essentially zero length (shared pivot points)
        int zeroLengthCount = 0;
        for (float length : lengths) {
            if (length < MIN_BONE_LENGTH) {
                zeroLengthCount++;
            }
        }

        // If any bones have zero length, we need to redistribute lengths
        // The total chain length should equal the distance from root to end effector
        if (zeroLengthCount > 0) {
            // Calculate total chain length from root pivot to end effector
            Vector3f rootPivot = chainBones.get(0).getBlueprintModelPivot();
            if (rootPivot == null) {
                rootPivot = new Vector3f(0, 0, 0);
            }
            Vector3f endPos = endEffectorPosition != null ? endEffectorPosition : new Vector3f(0, 0, 0);
            float totalLength = rootPivot.distance(endPos);

            if (totalLength > MIN_BONE_LENGTH) {
                // Distribute the total length evenly across ALL bones
                // This ensures the chain can exactly reach the end effector in rest pose
                float evenLength = totalLength / chainBones.size();
                for (int i = 0; i < lengths.length; i++) {
                    lengths[i] = evenLength;
                }
            }
        }

        return lengths;
    }

    /**
     * Gets the total length of the chain (sum of all bone lengths).
     *
     * @return Total chain length
     */
    public float getTotalLength() {
        float total = 0;
        for (float length : boneLengths) {
            total += length;
        }
        return total;
    }

    /**
     * Gets the root bone of the chain (first bone, ik_source).
     *
     * @return The root bone
     */
    public BoneBlueprint getRootBone() {
        return chainBones.isEmpty() ? null : chainBones.get(0);
    }

    /**
     * Gets the tip bone of the chain (last bone before end effector).
     *
     * @return The tip bone
     */
    public BoneBlueprint getTipBone() {
        return chainBones.isEmpty() ? null : chainBones.get(chainBones.size() - 1);
    }

    /**
     * Gets the number of bones in the chain.
     *
     * @return Number of bones
     */
    public int getBoneCount() {
        return chainBones.size();
    }

    /**
     * Checks if the chain uses a locator as target (vs a bone).
     *
     * @return true if target is a locator
     */
    public boolean hasLocatorTarget() {
        return targetLocator != null;
    }
}
