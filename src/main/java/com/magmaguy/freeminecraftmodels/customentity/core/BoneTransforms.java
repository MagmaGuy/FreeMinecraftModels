package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketTextEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.utils.TransformationMatrix;
import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.VersionChecker;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
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
    @Getter
    private PacketTextEntity packetTextDisplayArmorStandEntity = null;

    public BoneTransforms(Bone bone, Bone parent) {
        this.bone = bone;
        this.parent = parent;
    }

    public void setTextDisplayText(String text) {
        if (packetTextDisplayArmorStandEntity == null) return;
        packetTextDisplayArmorStandEntity.setText(text);
    }

    public void setTextDisplayVisible(boolean visible) {
        if (packetTextDisplayArmorStandEntity == null) return;
        packetTextDisplayArmorStandEntity.setTextVisible(visible);
    }

    public void transform() {
        updateLocalTransform();
        updateGlobalTransform();
    }

    public void updateGlobalTransform() {
        if (parent != null) {
            TransformationMatrix.multiplyMatrices(parent.getBoneTransforms().globalMatrix, localMatrix, globalMatrix);
            if (bone.getBoneBlueprint().isHead()) {
                // Store the inherited scale before resetting
                double[] inheritedScale = globalMatrix.getScale();

                globalMatrix.resetRotation();
                float yaw = -bone.getSkeleton().getCurrentHeadYaw() + 180;
                globalMatrix.rotateY((float) Math.toRadians(yaw));
                globalMatrix.rotateX(-(float) Math.toRadians(bone.getSkeleton().getCurrentHeadPitch()));

                // Reapply the inherited scale
                globalMatrix.scale(inheritedScale[0], inheritedScale[1], inheritedScale[2]);
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
        scaleAnimation();
        shiftPivotPointBack();
        rotateByEntityYaw();
    }

    private void scaleAnimation() {
        double currentScale = getDisplayEntityScale() / 2.5f;
        localMatrix.scale(currentScale, currentScale, currentScale);
    }

    //Shift to model center
    private void translateModelCenter() {
        localMatrix.translateLocal(bone.getBoneBlueprint().getModelCenter());

        //The bone is relative to its parent, so remove the offset of the parent
        if (parent != null) {
            Vector3f modelCenter = parent.getBoneBlueprint().getModelCenter();
            modelCenter.mul(-1);
            localMatrix.translateLocal(modelCenter);
        }
    }

    private void shiftPivotPoint() {
        localMatrix.translateLocal(bone.getBoneBlueprint().getBlueprintModelPivot().mul(-1));
    }

    private void translateAnimation() {
        localMatrix.translateLocal(
                -bone.getAnimationTranslation().get(0),
                bone.getAnimationTranslation().get(1),
                bone.getAnimationTranslation().get(2));
    }

    private void rotateAnimation() {
        Vector test = new Vector(bone.getAnimationRotation().get(0), -bone.getAnimationRotation().get(1), -bone.getAnimationRotation().get(2));
        test.rotateAroundY(Math.PI);
        localMatrix.rotateAnimation(
                (float) test.getX(),
                (float) test.getY(),
                (float) test.getZ());
    }

    private void rotateDefaultBoneRotation() {
        localMatrix.rotateLocal(
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(0),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(1),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(2));
    }

    private void shiftPivotPointBack() {
        //Remove the pivot point, go back to the model center
        localMatrix.translateLocal(bone.getBoneBlueprint().getBlueprintModelPivot());
    }

    public void generateDisplay() {
        transform();
        if (bone.getBoneBlueprint().isDisplayModel()) {
            if (bone.getBoneBlueprint().isNameTag()) {
                initializeTextDisplayBone();
                return;
            }
            initializeDisplayEntityBone();
            initializeArmorStandBone();
        }
    }

    private void initializeTextDisplayBone() {
        Location textDisplayLocation = getArmorStandTargetLocation();
        packetTextDisplayArmorStandEntity = NMSManager.getAdapter().createPacketTextArmorStandEntity(textDisplayLocation);
        packetTextDisplayArmorStandEntity.initializeText(textDisplayLocation);
        packetTextDisplayArmorStandEntity.sendLocationAndRotationPacket(textDisplayLocation, new EulerAngle(0, 0, 0));
    }

    private void initializeDisplayEntityBone() {
        if (!DefaultConfig.useDisplayEntitiesWhenPossible) return;
        Location displayEntityLocation = getDisplayEntityTargetLocation();
        packetDisplayEntity = NMSManager.getAdapter().createPacketDisplayEntity(displayEntityLocation);
        if (VersionChecker.serverVersionOlderThan(21, 4))
            packetDisplayEntity.initializeModel(displayEntityLocation, Integer.parseInt(bone.getBoneBlueprint().getModelID()));
        else {
            packetDisplayEntity.initializeModel(displayEntityLocation, bone.getBoneBlueprint().getModelID());
        }
        packetDisplayEntity.sendLocationAndRotationPacket(displayEntityLocation, getDisplayEntityRotation());
    }

    private void initializeArmorStandBone() {
        //todo: add way to disable armor stands later via config
        Location armorStandLocation = getArmorStandTargetLocation();
        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(armorStandLocation);
        if (VersionChecker.serverVersionOlderThan(21, 4))
            packetArmorStandEntity.initializeModel(armorStandLocation, Integer.parseInt(bone.getBoneBlueprint().getModelID()));
        else
            packetArmorStandEntity.initializeModel(armorStandLocation, bone.getBoneBlueprint().getModelID());

        packetArmorStandEntity.sendLocationAndRotationPacket(armorStandLocation, getArmorStandEntityRotation());
    }

    private void rotateByEntityYaw() {
        //rotate by yaw amount
        if (parent == null) {
            localMatrix.rotateLocal(0, (float) -Math.toRadians(bone.getSkeleton().getCurrentLocation().getYaw() + 180), 0);
        }
    }

    protected Location getArmorStandTargetLocation() {
        double[] translatedGlobalMatrix = globalMatrix.getTranslation();
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
        double[] translatedGlobalMatrix = globalMatrix.getTranslation();
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
        double[] rotation = globalMatrix.getRotation();
        if (VersionChecker.serverVersionOlderThan(20, 0))
            return new EulerAngle(rotation[0], rotation[1], rotation[2]);
        else {
            return new EulerAngle(-rotation[0], rotation[1], -rotation[2]);
        }
    }

    protected EulerAngle getArmorStandEntityRotation() {
        double[] rotation = globalMatrix.getRotation();
        return new EulerAngle(-rotation[0], -rotation[1], rotation[2]);
    }

    public void sendUpdatePacket(AbstractPacketBundle packetBundle) {
        if (packetArmorStandEntity != null && packetArmorStandEntity.hasViewers()) {
            if (packetBundle == null) packetBundle = packetArmorStandEntity.createPacketBundle();
            sendArmorStandUpdatePacket(packetBundle);
        }
        if (packetDisplayEntity != null && packetDisplayEntity.hasViewers()) {
            if (packetBundle == null) packetBundle = packetDisplayEntity.createPacketBundle();
            sendDisplayEntityUpdatePacket(packetBundle);
        }
        if (packetTextDisplayArmorStandEntity != null && packetTextDisplayArmorStandEntity.hasViewers()) {
            if (packetBundle == null) packetBundle = packetTextDisplayArmorStandEntity.createPacketBundle();
            sendTextDisplayUpdatePacket(packetBundle);
        }
    }

    private void sendTextDisplayUpdatePacket(AbstractPacketBundle packetBundle) {
        packetTextDisplayArmorStandEntity.generateLocationAndRotationAndScalePackets(
                packetBundle,
                getArmorStandTargetLocation(),
                new EulerAngle(0, 0, 0),
                1f);
    }

    private void sendArmorStandUpdatePacket(AbstractPacketBundle packetBundle) {
        if (packetArmorStandEntity != null) {
            packetArmorStandEntity.generateLocationAndRotationAndScalePackets(
                    packetBundle,
                    getArmorStandTargetLocation(),
                    getArmorStandEntityRotation(),
                    1f);
        }
    }

    private void sendDisplayEntityUpdatePacket(AbstractPacketBundle packetBundle) {
        if (packetDisplayEntity != null) {
            packetDisplayEntity.generateLocationAndRotationAndScalePackets(packetBundle, getDisplayEntityTargetLocation(), getDisplayEntityRotation(), (float) globalMatrix.getScale()[0] * 2.5f);
        }
    }

    protected float getDisplayEntityScale() {
        float scale = bone.getAnimationScale() == -1 ? 2.5f : bone.getAnimationScale() * 2.5f;
        //Only the root bone/head should be scaling up globally like this, otherwise the scale will be inherited by each bone and then become progressively larger or smaller
        if (bone.getParent() == null) {
            double scaleModifier = bone.getSkeleton().getModeledEntity().getScaleModifier();
            if (bone.getSkeleton().getModeledEntity().getUnderlyingEntity() != null && bone.getSkeleton().getModeledEntity() instanceof LivingEntity livingEntity && livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")) != null)
                scaleModifier *= livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")).getValue();
            scale *= (float) scaleModifier;
        }
        return scale;
    }

}