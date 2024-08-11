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
        shiftPivotPoint();
        translateModelCenter();
        translateAnimation();
        rotateAnimation();
        rotateDefaultBoneRotation();
        shiftPivotPointBack();
        rotateByEntityYaw();
    }

    //Shift to model center
    private void translateModelCenter() {
        localMatrix.translate(bone.getBoneBlueprint().getModelCenter());

        //The bone is relative to its parent, so remove the offset of the parent
        if (parent != null) {
            Vector3f modelCenter = parent.getBoneBlueprint().getModelCenter();
            modelCenter.mul(-1);
            localMatrix.translate(modelCenter);
        }
    }

    private void shiftPivotPoint() {
        localMatrix.translate(bone.getBoneBlueprint().getBlueprintModelPivot().mul(-1));
    }

    private void translateAnimation() {
        localMatrix.translate(
                bone.getAnimationTranslation().get(0),
                bone.getAnimationTranslation().get(1),
                bone.getAnimationTranslation().get(2));
    }

    private void rotateAnimation() {
        Vector test = new Vector(bone.getAnimationRotation().get(0), bone.getAnimationRotation().get(1), -bone.getAnimationRotation().get(2));
        test.rotateAroundY(Math.PI);
        localMatrix.rotateAnimation(
                (float) test.getX(),
                (float) test.getY(),
                (float) test.getZ());
    }

    private void rotateDefaultBoneRotation() {
        localMatrix.rotate(
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(0),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(1),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(2));
    }

    private void shiftPivotPointBack() {
        //Remove the pivot point, go back to the model center
        localMatrix.translate(bone.getBoneBlueprint().getBlueprintModelPivot());
    }

    public void generateDisplay() {
        transform();
        if (bone.getBoneBlueprint().isDisplayModel()) {
            initializeDisplayEntityBone();
            initializeArmorStandBone();
        }
    }

    private void initializeDisplayEntityBone() {
        if (!DefaultConfig.useDisplayEntitiesWhenPossible) return;
        packetDisplayEntity = NMSManager.getAdapter().createPacketDisplayEntity(getDisplayEntityTargetLocation());
        packetDisplayEntity.initializeModel(getDisplayEntityTargetLocation(), bone.getBoneBlueprint().getModelID());
        packetDisplayEntity.setScale(2.5f);
        packetDisplayEntity.sendLocationAndRotationPacket(getDisplayEntityTargetLocation(), getDisplayEntityRotation());
    }

    private void initializeArmorStandBone() {
        //todo: add way to disable armor stands later via config
        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(getArmorStandTargetLocation());
        packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), bone.getBoneBlueprint().getModelID());
        packetArmorStandEntity.sendLocationAndRotationPacket(getArmorStandTargetLocation(), getArmorStandEntityRotation());
    }

    private void rotateByEntityYaw() {
        //rotate by yaw amount
        if (parent == null) {
            localMatrix.rotate(0, (float) -Math.toRadians(bone.getSkeleton().getCurrentLocation().getYaw() + 180), 0);
        }
    }

    protected Location getArmorStandTargetLocation() {
        float[] translatedGlobalMatrix = globalMatrix.applyTransformation(new float[]{0, 0, 0, 1});
        Location armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                translatedGlobalMatrix[0],
                translatedGlobalMatrix[1],
                translatedGlobalMatrix[2])
                .add(bone.getSkeleton().getCurrentLocation());
        armorStandLocation.setYaw(180);
        armorStandLocation.subtract(new Vector(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0));
        return armorStandLocation;
    }

    protected Location getDisplayEntityTargetLocation() {
        float[] translatedGlobalMatrix = globalMatrix.getTranslation();
        Location armorStandLocation;
        if (!VersionChecker.serverVersionOlderThan(20, 0)) {
            armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                    translatedGlobalMatrix[0],
                    translatedGlobalMatrix[1],
                    translatedGlobalMatrix[2])
                    .add(bone.getSkeleton().getCurrentLocation());
            armorStandLocation.setYaw(180);
        } else
            armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                    translatedGlobalMatrix[0],
                    translatedGlobalMatrix[1],
                    translatedGlobalMatrix[2])
                    .add(bone.getSkeleton().getCurrentLocation());
        return armorStandLocation;
    }

    protected EulerAngle getDisplayEntityRotation() {
        float[] rotation = globalMatrix.getRotation();
        if (VersionChecker.serverVersionOlderThan(20, 0))
            return new EulerAngle(rotation[0], rotation[1], rotation[2]);
        else {
            return new EulerAngle(-rotation[0], rotation[1], -rotation[2]);
        }
    }

    protected EulerAngle getArmorStandEntityRotation() {
        float[] rotation = globalMatrix.getRotation();
        return new EulerAngle(-rotation[0], -rotation[1], rotation[2]);
    }

}
