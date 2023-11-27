package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.entities.ModelArmorStand;
import com.magmaguy.freeminecraftmodels.utils.QuaternionHelper;
import lombok.Getter;
import org.apache.commons.math3.complex.Quaternion;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
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
    private ArmorStand armorStand;
    @Getter
    private Quaternion localRotation = null;
    @Getter
    private Quaternion globalRotation = null;
    @Getter
    private Vector localTranslation = null;
    @Getter
    private Vector globalTranslation = null;
    //This is the location that the bone should be at when the tick ends. Due to teleport interpolation, it takes about 100ms to actually get there
    private Location targetAnimationLocation = null;

    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public void rotateTo(double newX, double newY, double newZ) {
        if (armorStand == null) return;
        localRotation = QuaternionHelper.eulerToQuaternion(newX, newY, newZ);
    }

    public void translateTo(double x, double y, double z) {
        if (armorStand == null) return;
        localTranslation = new Vector(x, y, z);
    }

    //Note that several optimizations might be possible here, but that syncing with a base entity is necessary.
    public void transform() {
        //Inherit rotation and translation values from parents
        updateGlobalRotation();
        updateGlobalTranslation();

        //Optimization: avoid sending a rotation
        EulerAngle eulerAngle = quaternionToEuler(globalRotation);
        if (Math.abs(eulerAngle.getX() - armorStand.getHeadPose().getX()) > .00000001 ||
                Math.abs(eulerAngle.getY() - armorStand.getHeadPose().getY()) > .00000001 ||
                Math.abs(eulerAngle.getZ() - armorStand.getHeadPose().getZ()) > .00000001) {
            armorStand.setHeadPose(eulerAngle);
        }

        updateTickPositionBasedOnParentRotation();
        targetAnimationLocation = updateArmorStandLocation();
        //Optimization: avoid a teleport packet if the target location is the same
        if (!armorStand.getLocation().equals(targetAnimationLocation))
            armorStand.teleport(targetAnimationLocation);

        boneChildren.forEach(Bone::transform);
        localTranslation = null;
        globalTranslation = null;
        localRotation = null;
        globalRotation = null;
    }

    private void updateGlobalRotation() {
        if (localRotation == null) localRotation = QuaternionHelper.eulerToQuaternion(0, 0, 0);
        if (parent == null)
            //Root bone, rotated by the yaw of the base model to face the right way. It's also rotated by 90 due to Blockbench compat.
            globalRotation = localRotation.multiply(QuaternionHelper.eulerToQuaternion(0, skeleton.getCurrentLocation().getYaw() - 180, 0));
        else globalRotation = parent.getGlobalRotation().multiply(localRotation);
    }

    private void updateGlobalTranslation() {
        if (localTranslation == null) localTranslation = new Vector(0, 0, 0);
        if (parent == null || parent.getGlobalTranslation() == null) globalTranslation = localTranslation;
        else globalTranslation = localTranslation.clone().add(parent.getGlobalTranslation());
    }

    private void updateTickPositionBasedOnParentRotation() {
        if (parent == null || parent.getGlobalRotation() == null) return;
        Vector fromParentToChild = getBoneBlueprint().getArmorStandOffsetFromModel().subtract(parent.getBoneBlueprint().getArmorStandOffsetFromModel());
        Vector rotatedVector = fromParentToChild.clone();
        EulerAngle parentRotation = quaternionToEuler(parent.getGlobalRotation());
        rotatedVector.rotateAroundX(-parentRotation.getX());
        rotatedVector.rotateAroundY(-parentRotation.getY());
        rotatedVector.rotateAroundZ(parentRotation.getZ());

        if (globalTranslation == null) globalTranslation = new Vector(0, 0, 0);
        Vector vector = rotatedVector.subtract(fromParentToChild);
        globalTranslation.add(vector);
    }

    public ArmorStand generateDisplay(Location location) {
        armorStand = ModelArmorStand.generate(location.clone().add(boneBlueprint.getArmorStandOffsetFromModel()), this);
        armorStand.setHeadPose(boneBlueprint.getArmorStandHeadRotation());
        targetAnimationLocation = location;
        if (boneBlueprint.isNameTag())
            armorStand.getPersistentDataContainer().set(BoneBlueprint.nameTagKey, PersistentDataType.BYTE, (byte) 0);
        return armorStand;
    }

    public void setName(String name) {
        if (armorStand != null && armorStand.isValid() && boneBlueprint.isNameTag())
            armorStand.setCustomName(name);
        boneChildren.forEach(child -> child.setName(name));
    }

    public void setNameVisible(boolean visible) {
        if (armorStand != null && armorStand.isValid() && boneBlueprint.isNameTag())
            armorStand.setCustomNameVisible(visible);
        boneChildren.forEach(child -> child.setNameVisible(visible));
    }

    public void getNametags(List<ArmorStand> nametags) {
        if (boneBlueprint.isNameTag()) nametags.add(armorStand);
        boneChildren.forEach(child -> child.getNametags(nametags));
    }

    private Location updateArmorStandLocation() {
        Location testLocation = skeleton.getCurrentLocation().add(boneBlueprint.getBoneOriginOffset().add(globalTranslation));
        testLocation.setYaw(180);
//        return skeleton.getCurrentLocation().add(boneBlueprint.getBoneOriginOffset().add(globalTranslation));
        return testLocation;
    }

    public void remove() {
        armorStand.remove();
        boneChildren.forEach(Bone::remove);
    }

    protected void getAllChildren(HashMap<String, Bone> children) {
        boneChildren.forEach(child -> {
            children.put(child.getBoneBlueprint().getBoneName(), child);
            child.getAllChildren(children);
        });
    }
}
