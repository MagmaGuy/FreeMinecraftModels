package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.thirdparty.Floodgate;
import com.magmaguy.freeminecraftmodels.utils.QuaternionHelper;
import com.magmaguy.freeminecraftmodels.utils.VersionChecker;
import lombok.Getter;
import org.apache.commons.math3.complex.Quaternion;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static com.magmaguy.freeminecraftmodels.utils.QuaternionHelper.quaternionToEuler;

public class Bone {
    @Getter
    private final BoneBlueprint boneBlueprint;
    @Getter
    private final List<Bone> boneChildren = new ArrayList<>();
    private final Bone parent;
    private final Skeleton skeleton;
    @Getter
    //value set by the animation task
    private Quaternion localRotation = null;
    @Getter
    private Quaternion globalRotation = null;
    @Getter
    //value set by the animation task
    private Vector animationTranslation = new Vector(0, 0, 0);
    //    @Getter
//    private Vector globalTranslation = null;
    private EulerAngle targetRotation = null;
    private EulerAngle displayEntityTargetRotation = null;
    private PacketModelEntity packetArmorStandEntity = null;
    private PacketModelEntity packetDisplayEntity = null;


    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public void rotateTo(double newX, double newY, double newZ) {
        localRotation = QuaternionHelper.eulerToQuaternion(newX, newY, newZ);
    }

    public void translateTo(double x, double y, double z) {
        animationTranslation = new Vector(x, y, z);
        //Move animation by parent amount
        if (parent != null) animationTranslation.add(parent.getAnimationTranslation());
    }

    //Note that several optimizations might be possible here, but that syncing with a base entity is necessary.
    public void transform() {
        //Inherit rotation and translation values from parents
        updateGlobalRotation();
//        updateGlobalTranslation();
        if (globalRotation != null)
            targetRotation = quaternionToEuler(globalRotation);
        else
            targetRotation = new EulerAngle(0, 0, 0);
//        testTargetRotation = quaternionToEuler(globalRotation.multiply(QuaternionHelper.eulerToQuaternion(0, skeleton.getCurrentLocation().getYaw() - 180, 0)));
        displayEntityTargetRotation = targetRotation;
//        updateTickPositionBasedOnParentRotation();
//        targetArmorStandAnimationLocation = updateArmorStandEntityLocation();
//        targetDisplayEntityAnimationLocation = updateDisplayEntityEntityLocation();
        boneChildren.forEach(Bone::transform);
//        animationTranslation = null;
//        globalTranslation = null;
        //todo: this needs to be double checked and moved
        localRotation = null;
        globalRotation = null;
    }

    private void updateGlobalRotation() {
        if (localRotation == null) localRotation = QuaternionHelper.eulerToQuaternion(0, 0, 0);
        if (parent == null || parent.getGlobalRotation() == null)
            //Root bone, rotated by the yaw of the base model to face the right way. It's also rotated by 90 due to Blockbench compat.
            globalRotation = localRotation.multiply(QuaternionHelper.eulerToQuaternion(0, skeleton.getCurrentLocation().getYaw() - 180, 0));
        else globalRotation = parent.getGlobalRotation().multiply(localRotation);
    }

//    private void updateGlobalTranslation() {
//        if (localTranslation == null) localTranslation = new Vector(0, 0, 0);
//        if (parent == null || parent.getGlobalTranslation() == null) globalTranslation = localTranslation;
//        else globalTranslation = localTranslation.clone().add(parent.getGlobalTranslation());
//    }
//
//    private void updateTickPositionBasedOnParentRotation() {
//        if (parent == null || parent.getGlobalRotation() == null) return;
//        Vector fromParentToChild = getBoneBlueprint().getBlockSpaceOrigin().subtract(parent.getBoneBlueprint().getBlockSpaceOrigin());
//        Vector rotatedVector = fromParentToChild.clone();
//        EulerAngle parentRotation = quaternionToEuler(parent.getGlobalRotation());
//        rotatedVector.rotateAroundX(-parentRotation.getX());
//        rotatedVector.rotateAroundY(-parentRotation.getY());
//        rotatedVector.rotateAroundZ(parentRotation.getZ());
//
//        if (globalTranslation == null) globalTranslation = new Vector(0, 0, 0);
//        Vector vector = rotatedVector.subtract(fromParentToChild);
//        globalTranslation.add(vector);
//    }
//
//    private EulerAngle getOffsetBasedOnSkeletonRotation() {
//        return new EulerAngle(0, Math.toRadians(skeleton.getCurrentLocation().getYaw()), 0);
//    }

    private Vector getOffsetBasedOnParentRotation() {
        if (parent == null || parent.getGlobalRotation() == null) return new Vector(0, 0, 0);
        Vector fromParentToChild = getBoneBlueprint().getBlockSpaceOrigin().subtract(parent.getBoneBlueprint().getBlockSpaceOrigin());
        Vector rotatedVector = fromParentToChild.clone();
        EulerAngle parentRotation = quaternionToEuler(parent.getGlobalRotation());
        rotatedVector.rotateAroundX(parentRotation.getX());
        rotatedVector.rotateAroundY(parentRotation.getY());
        rotatedVector.rotateAroundZ(parentRotation.getZ());

        return rotatedVector.subtract(fromParentToChild);
    }

    public void generateDisplay() {
        targetRotation = boneBlueprint.getArmorStandHeadRotation();
        displayEntityTargetRotation = getDisplayEntityRotation();
        if (boneBlueprint.getModelID() != null) {
            packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(getArmorStandTargetLocation());
            if (DefaultConfig.useDisplayEntitiesWhenPossible)
                packetDisplayEntity = NMSManager.getAdapter().createPacketDisplayEntity(getDisplayEntityTargetLocation());
            packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), boneBlueprint.getModelID());
            packetArmorStandEntity.sendLocationAndRotationPacket(getArmorStandTargetLocation(), targetRotation);
            if (DefaultConfig.useDisplayEntitiesWhenPossible) {
                packetDisplayEntity.initializeModel(getDisplayEntityTargetLocation(), boneBlueprint.getModelID());
                packetDisplayEntity.setScale(2.5f);
                packetDisplayEntity.sendLocationAndRotationPacket(getDisplayEntityTargetLocation(), displayEntityTargetRotation);
            }
        }
    }

    private Location getArmorStandTargetLocation() {
        Location newLocation = skeleton.getCurrentLocation()
                .add(boneBlueprint.getBlockSpaceOrigin())
                .add(boneBlueprint.getArmorStandOffsetFromModel())
                .add(getOffsetBasedOnParentRotation());
        if (animationTranslation != null) newLocation.add(animationTranslation);
        return newLocation;
    }

    private Location getDisplayEntityTargetLocation() {
        Location newLocation = skeleton.getCurrentLocation();
        //This readjusts the offset from the entity origin offset, necessary because armor stands can have offset models but display entities can't
        Vector offsetBasedOnModelOrigin = boneBlueprint.getDisplayEntityModelSpaceOriginOffset().clone().multiply(1 / 25.599d);
        //This rotates the position of this specific bone relative to other bones to put it in the correct location
        offsetBasedOnModelOrigin.rotateAroundX(Math.toRadians(boneBlueprint.getOriginsForDisplayEntityLocationShiftBasedOnRotation().getX()));
        offsetBasedOnModelOrigin.rotateAroundY(Math.toRadians(boneBlueprint.getOriginsForDisplayEntityLocationShiftBasedOnRotation().getY()));
        offsetBasedOnModelOrigin.rotateAroundZ(Math.toRadians(boneBlueprint.getOriginsForDisplayEntityLocationShiftBasedOnRotation().getZ()));
        newLocation.subtract(offsetBasedOnModelOrigin);
        newLocation.add(getOffsetBasedOnParentRotation()); //todo: this will probably break some sub-bones when rotating in animations
        //todo: this part tries to add the animation
        if (animationTranslation != null) newLocation.add(animationTranslation);
        Vector skeletonRotation = getBoneLocationBasedOnSkeletonLocation(newLocation);
        newLocation = skeleton.getCurrentLocation().add(-skeletonRotation.getX(), skeletonRotation.getY(), skeletonRotation.getZ());
        return newLocation;
    }

    /**
     * Calculates the bone location based on the skeleton location.
     *
     * @param originalBoneLocation The original bone location.
     * @return A vector which when added to the current
     */
    private Vector getBoneLocationBasedOnSkeletonLocation(Location originalBoneLocation) {
        //Covert to local coordinate system
        Vector skeletonRotation = originalBoneLocation.clone().subtract(skeleton.getCurrentLocation()).toVector();
        //Rotate by yaw + display entities spawn facing south but entities face north
        skeletonRotation.rotateAroundY(Math.toRadians(skeleton.getCurrentLocation().getYaw()) + Math.PI);
//        originalBoneLocation = skeleton.getCurrentLocation().add(skeletonRotation.getX(), skeletonRotation.getY(), skeletonRotation.getZ());
        return skeletonRotation;
    }

    private EulerAngle getDisplayEntityRotation() {
        EulerAngle rotation = boneBlueprint.getDisplayEntityBoneRotation().add(0, Math.PI, 0);
        if (globalRotation != null)
            return quaternionToEuler(globalRotation).add(rotation.getX(), rotation.getY(), rotation.getZ());
        return rotation;
    }

    public void setName(String name) {
        boneChildren.forEach(child -> child.setName(name));
    }

    public void setNameVisible(boolean visible) {
        boneChildren.forEach(child -> child.setNameVisible(visible));
    }

    public void getNametags(List<ArmorStand> nametags) {
        boneChildren.forEach(child -> child.getNametags(nametags));
    }

//    private Location updateArmorStandEntityLocation() {
//        return skeleton.getCurrentLocation().add(boneBlueprint.getBlockSpaceOrigin().add(globalTranslation));
//    }
//
//    private Location updateDisplayEntityEntityLocation() {
//        return skeleton.getCurrentLocation().add(globalTranslation);
//    }

    public void remove() {
        if (packetArmorStandEntity != null) packetArmorStandEntity.remove();
        if (packetDisplayEntity != null) packetDisplayEntity.remove();
        boneChildren.forEach(Bone::remove);
    }

    protected void getAllChildren(HashMap<String, Bone> children) {
        boneChildren.forEach(child -> {
            children.put(child.getBoneBlueprint().getBoneName(), child);
            child.getAllChildren(children);
        });
    }

    public void sendUpdatePacket() {
        if (packetArmorStandEntity != null)
            packetArmorStandEntity.sendLocationAndRotationPacket(
                    getArmorStandTargetLocation(),
                    targetRotation);
        if (packetDisplayEntity != null)
            packetDisplayEntity.sendLocationAndRotationPacket(
                    getDisplayEntityTargetLocation(),
                    getDisplayEntityRotation());
    }

    public void displayTo(Player player) {
        if (packetArmorStandEntity != null &&
                (!DefaultConfig.useDisplayEntitiesWhenPossible ||
                        Floodgate.isBedrock(player) ||
                        VersionChecker.serverVersionOlderThan(19, 4)))
            packetArmorStandEntity.displayTo(player);
        else if (packetDisplayEntity != null)
            packetDisplayEntity.displayTo(player);
    }

    public void hideFrom(Player player) {
        if (packetArmorStandEntity != null &&
                (!DefaultConfig.useDisplayEntitiesWhenPossible ||
                        Floodgate.isBedrock(player) ||
                        VersionChecker.serverVersionOlderThan(19, 4)))
            packetArmorStandEntity.hideFrom(player);
        else if (packetDisplayEntity != null)
            packetDisplayEntity.hideFrom(player);
    }
}
