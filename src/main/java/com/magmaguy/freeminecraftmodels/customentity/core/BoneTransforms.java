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
    private final Bone bone;
    private final TransformationMatrix localMatrix = new TransformationMatrix();
    private TransformationMatrix globalMatrix = new TransformationMatrix();
    @Getter
    private PacketModelEntity packetArmorStandEntity = null;
    @Getter
    private PacketModelEntity packetDisplayEntity = null;

    public BoneTransforms(Bone bone, Bone parent) {
        this.bone = bone;
        this.parent = parent;
    }

    public void transform() {
        updateLocalTransform();
        updateGlobalTransform();
    }

    public void updateGlobalTransform() {
        if (parent != null) {
            TransformationMatrix.multiplyMatrices(parent.getBoneTransforms().globalMatrix, localMatrix, globalMatrix);
            if (bone.getBoneBlueprint().isHead()) {
                globalMatrix.resetRotation();
                float yaw = -bone.getSkeleton().getCurrentHeadYaw() + 180;
                globalMatrix.rotateY((float) Math.toRadians(yaw));
                globalMatrix.rotateX(-(float) Math.toRadians(bone.getSkeleton().getCurrentHeadPitch()));
            }
        } else {
            globalMatrix = localMatrix;
        }
    }

    public void updateLocalTransform() {
        localMatrix.resetToIdentityMatrix();

        // 1. Translate to pivot point
        localMatrix.translate(bone.getBoneBlueprint().getBlueprintModelPivot());

        // 2. Apply rotations (default bone rotation + animation)
        Vector3f defaultRotation = bone.getBoneBlueprint().getBlueprintOriginalBoneRotation();
        localMatrix.rotateLocal(
                defaultRotation.get(0),
                defaultRotation.get(1),
                defaultRotation.get(2));

        localMatrix.rotateLocal(
                bone.getAnimationRotation().get(0),
                bone.getAnimationRotation().get(1),
                bone.getAnimationRotation().get(2));

        // 3. Translate back from pivot point
        localMatrix.translate(new Vector3f(
                -bone.getBoneBlueprint().getBlueprintModelPivot().x,
                -bone.getBoneBlueprint().getBlueprintModelPivot().y,
                -bone.getBoneBlueprint().getBlueprintModelPivot().z));

        // 4. Translate to model center
        localMatrix.translate(bone.getBoneBlueprint().getModelCenter());

        // 5. Apply animation translation
        localMatrix.translate(
                bone.getAnimationTranslation().get(0),
                bone.getAnimationTranslation().get(1),
                bone.getAnimationTranslation().get(2));

        // 6. Scale
        localMatrix.scale(bone.getAnimationScale());

        // 7. Handle parent model center offset
        if (parent != null) {
            Vector3f modelCenter = parent.getBoneBlueprint().getModelCenter();
            modelCenter.mul(-1);
            localMatrix.translate(modelCenter);
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
        //todo: add way to disable armor stands later via config
        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(getArmorStandTargetLocation());
        if (VersionChecker.serverVersionOlderThan(21, 4))
            packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), Integer.parseInt(bone.getBoneBlueprint().getModelID()));
        else
            packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), bone.getBoneBlueprint().getModelID());
        sendArmorStandUpdatePacket();
    }

    protected Location getArmorStandTargetLocation() {
        float[] translatedGlobalMatrix = globalMatrix.applyTransformation(new float[]{0, 0, 0, 1});
        Location armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                translatedGlobalMatrix[0],
                translatedGlobalMatrix[1],
                translatedGlobalMatrix[2])
                .add(bone.getSkeleton().getCurrentLocation());
        // Remove this line: armorStandLocation.setYaw(180);
        armorStandLocation.subtract(new Vector(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0));
        return armorStandLocation;
    }

    protected Location getDisplayEntityTargetLocation() {
        // Get translation from global matrix
        float[] translation = globalMatrix.getTranslation();

        // Create location in world space
        Location skeletonLocation = bone.getSkeleton().getCurrentLocation();
        Location entityLocation = new Location(
                skeletonLocation.getWorld(),
                translation[0] + skeletonLocation.getX(),
                translation[1] + skeletonLocation.getY(),
                translation[2] + skeletonLocation.getZ()
        );

        return entityLocation; // Remove the setYaw(180) call
    }

    protected EulerAngle getDisplayEntityRotation() {
        float[] rotation = globalMatrix.getRotation();

        // For all actual bone entities (non-root)
        if (parent != null) {
            // For a 180° Y rotation:
            rotation[0] = -rotation[0];  // Invert X rotation
            rotation[1] = rotation[1] + (float) Math.PI;  // Add 180° to Y
            rotation[2] = -rotation[2];  // Invert Z rotation

            // Normalize Y rotation to keep it in -π to π range
            if (rotation[1] > Math.PI) {
                rotation[1] -= 2 * Math.PI;
            } else if (rotation[1] < -Math.PI) {
                rotation[1] += 2 * Math.PI;
            }
        }

        return new EulerAngle(rotation[0], rotation[1], rotation[2]);
    }

    protected EulerAngle getArmorStandEntityRotation() {
        float[] rotation = globalMatrix.getRotation();

        // For all actual bone entities (non-root)
        if (parent != null) {
            // For a 180° Y rotation:
            rotation[0] = -rotation[0];  // Invert X rotation
            rotation[1] = rotation[1] + (float) Math.PI;  // Add 180° to Y
            rotation[2] = -rotation[2];  // Invert Z rotation

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
        return bone.getAnimationScale() * 2.5f;
    }

    public void sendUpdatePacket() {
        sendArmorStandUpdatePacket();
        sendDisplayEntityUpdatePacket();
    }

    private void sendArmorStandUpdatePacket() {
        if (packetArmorStandEntity != null) {
            packetArmorStandEntity.sendLocationAndRotationPacket(getArmorStandTargetLocation(), getArmorStandEntityRotation());
        }
    }

    private void sendDisplayEntityUpdatePacket() {
        if (packetDisplayEntity != null) {
            packetDisplayEntity.sendLocationAndRotationAndScalePacket(getDisplayEntityTargetLocation(), getDisplayEntityRotation(), getDisplayEntityScale());
        }
    }

}
