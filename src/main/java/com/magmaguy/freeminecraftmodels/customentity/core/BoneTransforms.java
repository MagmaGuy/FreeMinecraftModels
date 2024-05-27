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
import org.joml.Vector3f;

public class BoneTransforms {

    private final Bone parent;
    private final Bone bone;
    private final TransformationMatrix internalMatrix = new TransformationMatrix();
    private TransformationMatrix externalMatrix = new TransformationMatrix();
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
            TransformationMatrix.multiplyMatrices(parent.getBoneTransforms().externalMatrix, internalMatrix, externalMatrix, getPivotPoint(), bone.getBoneBlueprint());
        } else {
            externalMatrix = internalMatrix;
        }
    }

    public void updateLocalTransform() {
        internalMatrix.resetToIdentity();
//        if (parent == null) internalMatrix.mirrorZ();
//        if (parent == null) internalMatrix.rotateY((float) Math.PI);
        translateModelCenter();
        rotateDefaultBoneRotation();
        rotateAnimation();
        translateAnimation();
        // Finally, adjust for the model's center (which might include adjustments relative to the parent bone)
//        rotateByEntityYaw();
    }

    //Shift to model center
    private void translateModelCenter() {
        internalMatrix.translate(bone.getBoneBlueprint().getModelCenter());

        //The bone is relative to its parent, so remove the offset of the parent
        if (parent != null) {
            Vector3f modelCenter = parent.getBoneBlueprint().getModelCenter();
            modelCenter.mul(-1);
            internalMatrix.translate(modelCenter);
        }
    }

//    private void shiftPivotPoint() {
//        Vector3f pivotPoint = bone.getBoneBlueprint().getBlueprintModelPivot();
//        internalMatrix.translate(new Vector3f(-pivotPoint.get(0), -pivotPoint.get(1), -pivotPoint.get(2)));
//    }
//
//    private void shiftPivotPointBack() {
//        Vector3f pivotPoint = bone.getBoneBlueprint().getBlueprintModelPivot();
//        internalMatrix.translate(pivotPoint);
//    }

    private void translateAnimation() {
        Vector3f testVector = new Vector3f();
        testVector.x = bone.getAnimationTranslation().get(0);
        testVector.y = bone.getAnimationTranslation().get(1);
        testVector.z = bone.getAnimationTranslation().get(2);
        internalMatrix.translateLocal(testVector);
    }

    private void rotateAnimation() {
        Vector3f test = new Vector3f(
                bone.getAnimationRotation().get(0),
                bone.getAnimationRotation().get(1),
                bone.getAnimationRotation().get(2));

        // Rotating by π (180 degrees) around the Y axis to align with the game's reference system
//        test.rotateY((float) Math.PI);

        // Applying the rotation
        internalMatrix.animationRotation(test.x, test.y, test.z, getPivotPoint());
    }


    private void rotateDefaultBoneRotation() {
//        shiftPivotPoint();
        internalMatrix.rotateDefaultPosition(
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(0),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(1),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(2),
                getPivotPoint());
//        shiftPivotPointBack();
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
            internalMatrix.rotateY((float) -Math.toRadians(bone.getSkeleton().getCurrentLocation().getYaw() + 180));
        }
    }

    protected Location getArmorStandTargetLocation() {
        float[] translatedGlobalMatrix = externalMatrix.getTranslation();
        Location armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                translatedGlobalMatrix[0],
                translatedGlobalMatrix[1],
                translatedGlobalMatrix[2])
                .add(bone.getSkeleton().getCurrentLocation());
        armorStandLocation.setYaw(180);
        armorStandLocation.toVector().toVector3f().sub(new Vector3f(0f, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0f));
        return armorStandLocation;
    }

    protected Location getDisplayEntityTargetLocation() {
        float[] translatedGlobalMatrix = externalMatrix.getTranslation();
        Location armorStandLocation;
        if (!VersionChecker.serverVersionOlderThan(20, 0)) {
            armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                    translatedGlobalMatrix[0],
                    translatedGlobalMatrix[1],
                    translatedGlobalMatrix[2])
                    .add(bone.getSkeleton().getCurrentLocation());
//            armorStandLocation.setYaw(180);
        } else
            armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                    translatedGlobalMatrix[0],
                    translatedGlobalMatrix[1],
                    translatedGlobalMatrix[2])
                    .add(bone.getSkeleton().getCurrentLocation());
        return armorStandLocation;
    }

    protected EulerAngle getDisplayEntityRotation() {
        Vector3f newRotation = externalMatrix.getExperimentalRotation();
        if (VersionChecker.serverVersionOlderThan(20, 0))
            return new EulerAngle(newRotation.get(0), newRotation.get(1), newRotation.get(2));
        else {
            return new EulerAngle(newRotation.get(0), newRotation.get(1), newRotation.get(2));
        }
    }

    protected EulerAngle getArmorStandEntityRotation() {
        float[] rotation = externalMatrix.getRotation();
        return new EulerAngle(-rotation[0], -rotation[1], rotation[2]);
    }

    public Vector3f getPivotPoint() {
        Vector3f pivot = bone.getBoneBlueprint().getBlueprintModelPivot();
//        if (parent != null) pivot.add(parent.getBoneTransforms().externalMatrix.getTranslationVector());
        return pivot;
    }

}
