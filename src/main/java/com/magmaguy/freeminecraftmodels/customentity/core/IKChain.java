package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.IKChainBlueprint;
import lombok.Getter;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime instance of an IK chain attached to a skeleton.
 * Handles solving IK and applying rotations to bones.
 */
public class IKChain {
    @Getter
    private final IKChainBlueprint blueprint;
    @Getter
    private final List<Bone> chainBones;
    @Getter
    private final Skeleton skeleton;
    private final float[] boneLengths;
    private final Vector3f[] jointPositions;
    private final Vector3f[] restDirections;
    private final Vector3f restGoalPosition;

    // Current IK goal offset (from animation)
    private Vector3f currentGoalOffset = new Vector3f(0, 0, 0);

    /**
     * Creates an IK chain instance from a blueprint.
     *
     * @param blueprint The IK chain blueprint
     * @param skeleton  The skeleton this chain belongs to
     */
    public IKChain(IKChainBlueprint blueprint, Skeleton skeleton) {
        this.blueprint = blueprint;
        this.skeleton = skeleton;
        this.boneLengths = blueprint.getBoneLengths();

        // Find the corresponding runtime Bone instances
        this.chainBones = new ArrayList<>();
        for (BoneBlueprint boneBlueprint : blueprint.getChainBones()) {
            // Use getBoneName() since that's the full namespaced key used in Skeleton.boneMap
            Bone bone = skeleton.getBoneMap().get(boneBlueprint.getBoneName());
            if (bone != null) {
                chainBones.add(bone);
            }
        }

        // Initialize joint positions array (bones + 1 for end effector)
        this.jointPositions = new Vector3f[chainBones.size() + 1];
        for (int i = 0; i < jointPositions.length; i++) {
            jointPositions[i] = new Vector3f();
        }

        // Calculate rest directions for each bone (direction to next joint in rest pose)
        this.restDirections = new Vector3f[chainBones.size()];
        calculateRestDirections();

        // Store the rest goal position (end effector position in rest pose)
        this.restGoalPosition = new Vector3f(blueprint.getEndEffectorPosition());
    }

    /**
     * Calculates the rest direction for each bone.
     * When bones share pivot points (common in Blockbench), all bones point
     * in the same direction from root toward end effector in rest pose.
     */
    private void calculateRestDirections() {
        // Get root position and end effector position
        Vector3f rootPivot = chainBones.isEmpty() ? new Vector3f(0, 0, 0) :
                chainBones.get(0).getBoneBlueprint().getBlueprintModelPivot();
        if (rootPivot == null) {
            rootPivot = new Vector3f(0, 0, 0);
        }

        Vector3f endPos = blueprint.getEndEffectorPosition();
        if (endPos == null) {
            endPos = new Vector3f(0, 0, 0);
        }

        // Calculate the rest direction as the direction from root to end effector
        Vector3f restDirection = new Vector3f(endPos).sub(rootPivot);
        float length = restDirection.length();
        if (length > 0.0001f) {
            restDirection.normalize();
        } else {
            // Default to forward direction
            restDirection.set(0, 0, 1);
        }

        // All bones in rest pose point in the same direction (toward end effector)
        for (int i = 0; i < chainBones.size(); i++) {
            restDirections[i] = new Vector3f(restDirection);
        }
    }

    /**
     * Sets the IK goal offset from animation.
     * This offset is relative to the null object's rest position.
     *
     * @param offset The goal offset from animation (in model space)
     */
    public void setGoalOffset(Vector3f offset) {
        this.currentGoalOffset.set(offset);
    }

    /**
     * Sets the IK goal offset from animation values.
     *
     * @param x X offset
     * @param y Y offset
     * @param z Z offset
     */
    public void setGoalOffset(float x, float y, float z) {
        this.currentGoalOffset.set(x, y, z);
    }

    // Counter for logging - only log every N calls to avoid spam
    private int solveCallCount = 0;

    /**
     * Solves the IK chain and applies rotations to bones.
     * Should be called each frame after animation values are set.
     */
    public void solve() {
        solveCallCount++;
        boolean shouldLog = (solveCallCount % 20 == 1); // Log every 20 frames (once per second)

        if (chainBones.isEmpty()) {
            if (shouldLog) {
                com.magmaguy.magmacore.util.Logger.warn("[IK Debug] solve() called but chainBones is empty!");
            }
            return;
        }

        // Initialize joint positions from current bone positions
        initializeJointPositions();

        // Calculate the target position (rest goal + animated offset)
        Vector3f target = new Vector3f(restGoalPosition).add(currentGoalOffset);

        if (shouldLog) {
            com.magmaguy.magmacore.util.Logger.info("[IK Debug] IKChain '" + getControllerName() +
                    "' solving: restGoal=(" + restGoalPosition.x + "," + restGoalPosition.y + "," + restGoalPosition.z +
                    "), offset=(" + currentGoalOffset.x + "," + currentGoalOffset.y + "," + currentGoalOffset.z +
                    "), target=(" + target.x + "," + target.y + "," + target.z + ")");
            com.magmaguy.magmacore.util.Logger.info("[IK Debug] Bone lengths: " + java.util.Arrays.toString(boneLengths));
        }

        // Solve FABRIK
        boolean converged = IKSolver.solveFABRIK(jointPositions, target, boneLengths);

        if (shouldLog) {
            com.magmaguy.magmacore.util.Logger.info("[IK Debug] FABRIK converged: " + converged);
            com.magmaguy.magmacore.util.Logger.info("[IK Debug] After FABRIK - joint positions:");
            for (int i = 0; i < jointPositions.length; i++) {
                com.magmaguy.magmacore.util.Logger.info("[IK Debug]   Joint " + i + ": (" +
                        jointPositions[i].x + "," + jointPositions[i].y + "," + jointPositions[i].z + ")");
            }
        }

        // Apply solved rotations to bones
        applyRotationsToBones(shouldLog);

        // Visualize the IK target position
        visualizeTarget(target);
    }

    /**
     * Spawns particles at the IK target position and joint positions for debugging.
     * Target is shown with red particles, joints with blue particles.
     *
     * @param target The IK target position in model space
     */
    private void visualizeTarget(Vector3f target) {
        Location skeletonLocation = skeleton.getCurrentLocation();
        if (skeletonLocation == null || skeletonLocation.getWorld() == null) {
            return;
        }

        // Get the skeleton's yaw for rotation
        float yaw = (float) Math.toRadians(skeletonLocation.getYaw() + 180);

        // Convert target from model space to world space
        // Model space Y is up, but we need to rotate around Y axis for entity facing
        double worldX = target.x * Math.cos(yaw) - target.z * Math.sin(yaw);
        double worldZ = target.x * Math.sin(yaw) + target.z * Math.cos(yaw);
        double worldY = target.y;

        Location targetWorldLocation = skeletonLocation.clone().add(worldX, worldY, worldZ);

        // Spawn red dust particle at target position
        Particle.DustOptions redDust = new Particle.DustOptions(Color.RED, 1.0f);
        skeletonLocation.getWorld().spawnParticle(Particle.DUST, targetWorldLocation, 1, 0, 0, 0, 0, redDust);

        // Also visualize joint positions with blue particles
        Particle.DustOptions blueDust = new Particle.DustOptions(Color.BLUE, 0.5f);
        for (Vector3f jointPos : jointPositions) {
            double jointWorldX = jointPos.x * Math.cos(yaw) - jointPos.z * Math.sin(yaw);
            double jointWorldZ = jointPos.x * Math.sin(yaw) + jointPos.z * Math.cos(yaw);
            double jointWorldY = jointPos.y;

            Location jointWorldLocation = skeletonLocation.clone().add(jointWorldX, jointWorldY, jointWorldZ);
            skeletonLocation.getWorld().spawnParticle(Particle.DUST, jointWorldLocation, 1, 0, 0, 0, 0, blueDust);
        }
    }

    /**
     * Initializes joint positions for FABRIK solving.
     * If bones share pivot points (common in Blockbench), calculates positions
     * by stepping from root toward end effector using bone lengths.
     */
    private void initializeJointPositions() {
        boolean shouldLog = (solveCallCount % 100 == 1); // Log less frequently

        // Get root position and end effector position
        Vector3f rootPivot = chainBones.get(0).getBoneBlueprint().getBlueprintModelPivot();
        if (rootPivot == null) {
            rootPivot = new Vector3f(0, 0, 0);
        }

        Vector3f endPos = blueprint.getEndEffectorPosition();
        if (endPos == null) {
            endPos = new Vector3f(0, 0, 0);
        }

        // Calculate direction from root to end effector
        Vector3f direction = new Vector3f(endPos).sub(rootPivot);
        float totalDistance = direction.length();

        if (totalDistance > 0.0001f) {
            direction.normalize();
        } else {
            // Fallback to forward direction if root and end effector are at same position
            direction.set(0, 0, 1);
        }

        // Initialize joint positions by stepping along the direction using bone lengths
        jointPositions[0].set(rootPivot);

        float accumulatedLength = 0;
        for (int i = 0; i < chainBones.size(); i++) {
            accumulatedLength += boneLengths[i];
            // Position the next joint along the direction from root
            jointPositions[i + 1].set(rootPivot)
                    .add(direction.x * accumulatedLength,
                         direction.y * accumulatedLength,
                         direction.z * accumulatedLength);

            if (shouldLog) {
                com.magmaguy.magmacore.util.Logger.info("[IK Debug] Joint " + (i + 1) + " initialized at (" +
                        jointPositions[i + 1].x + "," + jointPositions[i + 1].y + "," + jointPositions[i + 1].z +
                        ") boneLength=" + boneLengths[i]);
            }
        }

        if (shouldLog) {
            com.magmaguy.magmacore.util.Logger.info("[IK Debug] Root joint 0: (" +
                    jointPositions[0].x + "," + jointPositions[0].y + "," + jointPositions[0].z + ")");
            com.magmaguy.magmacore.util.Logger.info("[IK Debug] Target end effector: (" +
                    endPos.x + "," + endPos.y + "," + endPos.z + ")");
        }
    }

    /**
     * Applies the solved rotations to the bones.
     * Calculates the rotation needed to point each bone toward the next joint.
     * Handles both hierarchical bones (where children inherit parent rotations)
     * and sibling/flat bones (where each bone is independent).
     */
    private void applyRotationsToBones(boolean shouldLog) {
        // Track the cumulative world rotation as we go down the chain
        org.joml.Quaternionf cumulativeWorldRotation = new org.joml.Quaternionf(); // Identity

        for (int i = 0; i < chainBones.size(); i++) {
            Bone bone = chainBones.get(i);

            // Calculate the new direction from solved positions
            Vector3f newDir = new Vector3f(jointPositions[i + 1]).sub(jointPositions[i]);
            float length = newDir.length();

            if (shouldLog) {
                com.magmaguy.magmacore.util.Logger.info("[IK Debug] Bone " + i + " '" + bone.getBoneBlueprint().getBoneName() +
                        "' jointPos[" + i + "]=(" + jointPositions[i].x + "," + jointPositions[i].y + "," + jointPositions[i].z +
                        ") jointPos[" + (i+1) + "]=(" + jointPositions[i+1].x + "," + jointPositions[i+1].y + "," + jointPositions[i+1].z +
                        ") length=" + length);
            }

            if (length > 0.0001f) {
                newDir.normalize();
            } else {
                if (shouldLog) {
                    com.magmaguy.magmacore.util.Logger.info("[IK Debug] Skipping bone " + i + " - positions too close");
                }
                continue; // Skip if positions are too close
            }

            // Calculate the WORLD rotation needed for this bone (from rest to solved direction)
            org.joml.Quaternionf worldRotation = new org.joml.Quaternionf();
            worldRotation.rotationTo(restDirections[i], newDir);

            // Check if this bone actually inherits rotation from the previous bone in the chain
            // If bones are siblings (not parent-child), they don't inherit rotations
            boolean inheritsFromPrevious = false;
            if (i > 0) {
                Bone previousBone = chainBones.get(i - 1);
                // Check if the current bone's parent in the blueprint is the previous bone
                BoneBlueprint currentParent = bone.getBoneBlueprint().getParent();
                if (currentParent != null && currentParent == previousBone.getBoneBlueprint()) {
                    inheritsFromPrevious = true;
                }
            }

            Vector3f rotation;
            if (inheritsFromPrevious) {
                // Calculate LOCAL rotation by removing the parent's cumulative rotation
                // localRotation = inverse(cumulativeWorldRotation) * worldRotation
                org.joml.Quaternionf localRotation = new org.joml.Quaternionf();
                cumulativeWorldRotation.invert(localRotation); // localRotation = inverse of cumulative
                localRotation.mul(worldRotation); // localRotation = inverse(cumulative) * world
                rotation = IKSolver.quaternionToEuler(localRotation);

                if (shouldLog) {
                    com.magmaguy.magmacore.util.Logger.info("[IK Debug] Bone '" + bone.getBoneBlueprint().getBoneName() +
                            "' is CHILD of previous bone, using local rotation");
                }
            } else {
                // Bone doesn't inherit from previous, use full world rotation
                rotation = IKSolver.quaternionToEuler(worldRotation);

                if (shouldLog) {
                    com.magmaguy.magmacore.util.Logger.info("[IK Debug] Bone '" + bone.getBoneBlueprint().getBoneName() +
                            "' is NOT child of previous bone, using world rotation");
                }
            }

            // Invert X rotation to match Minecraft's coordinate system
            rotation.x = -rotation.x;

            if (shouldLog) {
                com.magmaguy.magmacore.util.Logger.info("[IK Debug] Bone '" + bone.getBoneBlueprint().getBoneName() +
                        "' restDir=(" + restDirections[i].x + "," + restDirections[i].y + "," + restDirections[i].z +
                        ") newDir=(" + newDir.x + "," + newDir.y + "," + newDir.z +
                        ") -> rotation=(" + rotation.x + "," + rotation.y + "," + rotation.z + ")");
            }

            // Apply the IK rotation to the bone
            bone.setIKRotation(rotation);

            // Update cumulative rotation for the next bone
            cumulativeWorldRotation.set(worldRotation);
        }
    }

    /**
     * Clears IK rotations from all bones in the chain.
     * Call this when IK should no longer be applied (e.g., animation ended).
     */
    public void clearIKRotations() {
        for (Bone bone : chainBones) {
            bone.clearIKRotation();
        }
        currentGoalOffset.set(0, 0, 0);
    }

    /**
     * Gets the number of bones in this chain.
     *
     * @return Number of bones
     */
    public int getBoneCount() {
        return chainBones.size();
    }

    /**
     * Gets the controller name (null object name) for this chain.
     *
     * @return Controller name
     */
    public String getControllerName() {
        return blueprint.getController().getName();
    }
}
