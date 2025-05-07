package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.utils.TransformationMatrix;
import com.magmaguy.freeminecraftmodels.utils.VersionChecker;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

public class BoneTransforms {

    private final Bone parent;
    @Getter
    private final Bone bone;
    private final TransformationMatrix localMatrix = new TransformationMatrix();
    @Getter
    private final TransformationMatrix globalMatrix = new TransformationMatrix();
    @Getter
    private PacketModelEntity packetArmorStandEntity = null;
    @Getter
    private PacketModelEntity packetDisplayEntity = null;

    public BoneTransforms(Bone bone, Bone parent) {
        this.bone = bone;
        this.parent = parent;
    }

    public void transform() {
        updateLocalMatrix();
        updateGlobalMatrix();
    }

    /**
     * Updates the local transformation matrix for this bone.
     * The key to compound rotations working correctly is consistent coordinate systems
     * and rotation order throughout the pipeline.
     */
    public void updateLocalMatrix() {
        localMatrix.resetToIdentityMatrix();

        Vector3f blueprintModelPivot = bone.getBoneBlueprint().getBlueprintModelPivot();

        // 1. Translate to pivot point
        localMatrix.translateLocal(blueprintModelPivot);

        // 2. Apply rotations in XYZ order
        // First apply the original bone rotation (from the model)
        Vector3f originalRotation = bone.getBoneBlueprint().getBlueprintOriginalBoneRotation();
        localMatrix.rotateLocal(
                originalRotation.get(0),
                originalRotation.get(1),
                originalRotation.get(2)
        );

        // Then apply animation rotation
        // NOTE: The sign flips are necessary due to coordinate system differences
        // between your model format and Minecraft's coordinate system
        Vector3f animRot = bone.getAnimationRotation();
        localMatrix.rotateLocal(
                -animRot.x,  // Negate X rotation
                -animRot.y,  // Negate Y rotation
                animRot.z    // Keep Z rotation as is
        );

        // 3. Scale
        localMatrix.scale(getDisplayEntityScale() / 2.5f);

        // 4. Translate back from pivot point
        localMatrix.translateLocal(blueprintModelPivot.mul(-1));

        // 5. Translate to model center
        localMatrix.translateLocal(bone.getBoneBlueprint().getModelCenter());

        // 6. Apply animation translation
        // Again, note the coordinate system adjustments
        localMatrix.translateLocal(
                -bone.getAnimationTranslation().get(0),  // Negate X translation
                bone.getAnimationTranslation().get(1),   // Keep Y translation as is
                bone.getAnimationTranslation().get(2)    // Keep Z translation as is
        );

        // 7. Handle parent model center offset
        if (parent != null) {
            Vector3f correctedModelCenter = new Vector3f(parent.getBoneBlueprint().getModelCenter()).mul(-1);
            localMatrix.translateLocal(correctedModelCenter);
        }
    }

    /**
     * Updates the global transformation matrix by combining the parent's global matrix
     * with this bone's local matrix.
     */
    public void updateGlobalMatrix() {
        if (parent == null) {
            // For root bone, start with a clean global matrix
            globalMatrix.resetToIdentityMatrix();

            Location currentLocation = bone.getSkeleton().getCurrentLocation();

            // First apply position
            localMatrix.translateLocal(
                    (float) currentLocation.getX(),
                    (float) currentLocation.getY(),
                    (float) currentLocation.getZ()
            );

            // Combine with local matrix
            TransformationMatrix.multiplyMatrices(globalMatrix, localMatrix, globalMatrix);
        } else {
            // This is where the parent's global matrix is applied to the local matrix
            // This multiplication is what propagates transformations down the bone hierarchy
            TransformationMatrix.multiplyMatrices(parent.getBoneTransforms().globalMatrix, localMatrix, globalMatrix);
        }
    }

    public void generateDisplay() {
        if (bone.getBoneBlueprint().isDisplayModel()) {
            initializeDisplayEntityBone();
            initializeArmorStandBone();
        }
    }

    private void initializeDisplayEntityBone() {
        if (!DefaultConfig.useDisplayEntitiesWhenPossible) return;
        Location displayEntityLocation = getDisplayEntityTargetLocation();
        packetDisplayEntity = NMSManager.getAdapter().createPacketDisplayEntity(displayEntityLocation);
        if (VersionChecker.serverVersionOlderThan(21, 4))
            packetDisplayEntity.initializeModel(displayEntityLocation, Integer.parseInt(bone.getBoneBlueprint().getModelID()));
        else
            packetDisplayEntity.initializeModel(displayEntityLocation, bone.getBoneBlueprint().getModelID());
        sendDisplayEntityUpdatePacket();
    }

    private void initializeArmorStandBone() {
        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(getArmorStandTargetLocation());
        if (VersionChecker.serverVersionOlderThan(21, 4))
            packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), Integer.parseInt(bone.getBoneBlueprint().getModelID()));
        else
            packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), bone.getBoneBlueprint().getModelID());
        sendArmorStandUpdatePacket();
    }

    protected Location getArmorStandTargetLocation() {
        // Get translation directly from the matrix
        float[] translation = globalMatrix.getTranslation();

        Location armorStandLocation = new Location(
                bone.getSkeleton().getCurrentLocation().getWorld(),
                translation[0],
                translation[1],
                translation[2]
        );

        // Apply armor stand offset
        armorStandLocation.subtract(new Vector(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0));
        return armorStandLocation;
    }

    protected Location getDisplayEntityTargetLocation() {
        // Get translation from global matrix
        float[] translation = globalMatrix.getTranslation();
        Location displayEntityLocation = new Location(
                bone.getSkeleton().getCurrentLocation().getWorld(),
                translation[0],
                translation[1],
                translation[2]
        );
        return displayEntityLocation;
    }

    /**
     * Gets the rotation for display entity using XYZ order.
     * Display entities require a specific adjustment due to their default orientation.
     * <p>
     * This is a key part of ensuring compound rotations work correctly - the right
     * coordinate system transformations are applied consistently.
     */
    protected EulerAngle getBoneRotationForDisplayEntity() {
        // Get raw rotation from the global matrix in XYZ order
        float[] rotation = globalMatrix.getRotation();


        // Display entities need a specific adjustment to match Minecraft's coordinate system
        return new EulerAngle(
                -rotation[0],                  // Negate X rotation
                rotation[1] + (float) Math.PI, // Add 180° to Y rotation (flip)
                rotation[2]                   // Keep Z rotation as is
        );
    }


    /**
     * Gets the rotation for armor stand entity using XYZ order.
     * Armor stands require a different adjustment than display entities
     * due to their different default orientation.
     */
    protected EulerAngle getBoneRotationForArmorStandEntity() {
        // Get raw rotation from the global matrix in XYZ order
        float[] rotation = globalMatrix.getRotation();

        // For non-root bones, apply specific armor stand adjustments
        if (parent != null) {
            // Armor stands need different adjustments than display entities
            rotation[0] = -rotation[0];              // Negate X rotation
            rotation[1] = rotation[1] + (float) Math.PI;  // Add 180° to Y rotation (flip)
            rotation[2] = -rotation[2];              // Negate Z rotation

            // Normalize Y rotation to keep it in -π to π range
            if (rotation[1] > Math.PI) {
                rotation[1] -= 2 * Math.PI;
            } else if (rotation[1] < -Math.PI) {
                rotation[1] += 2 * Math.PI;
            }
        }

        return new EulerAngle(rotation[0], rotation[1], rotation[2]);
    }

    protected float getDisplayEntityScale() {
        return bone.getAnimationScale() == -1 ? 2.5f : bone.getAnimationScale() * 2.5f;
    }

    public void sendUpdatePacket() {
        sendArmorStandUpdatePacket();
        sendDisplayEntityUpdatePacket();
    }

    private void sendArmorStandUpdatePacket() {
        if (packetArmorStandEntity != null) {
            packetArmorStandEntity.sendLocationAndRotationPacket(getArmorStandTargetLocation(), getBoneRotationForArmorStandEntity());
        }
    }

    private void sendDisplayEntityUpdatePacket() {
        if (packetDisplayEntity != null) {
            packetDisplayEntity.sendLocationAndRotationAndScalePacket(getDisplayEntityTargetLocation(), getBoneRotationForDisplayEntity(), getDisplayEntityScale());
        }
    }
}