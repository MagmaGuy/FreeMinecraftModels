package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.entities.ModelArmorStand;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import lombok.Getter;
import org.apache.commons.math3.geometry.euclidean.threed.Rotation;
import org.apache.commons.math3.geometry.euclidean.threed.RotationOrder;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

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
    private EulerAngle tickRotation = null;
    @Getter
    private Vector tickPosition = new Vector(0, 0, 0);
    //This is the location that the bone should be at when the tick ends. Due to teleport interpolation, it takes about 100ms to actually get there
    private Location targetAnimationLocation = null;

    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        Developer.debug("parent is null " + (parent == null) + " for bone " + boneBlueprint.getBoneName());
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public void rotateTo(double newX, double newY, double newZ) {
        if (armorStand == null) return;
        if (tickRotation != null)
            tickRotation = tickRotation.add(Math.toRadians(newX), Math.toRadians(newY), Math.toRadians(newZ));
        else
            tickRotation = new EulerAngle(Math.toRadians(newX), Math.toRadians(newY), Math.toRadians(newZ));
        boneChildren.forEach(boneChild -> boneChild.rotateTo(newX, newY, newZ));
    }

    public void translateTo(double x, double y, double z) {
        if (armorStand == null) return;
        if (tickPosition == null) tickPosition = new Vector(x, y, z);
        else tickPosition.add(new Vector(x, y, z));
        boneChildren.forEach(boneChild -> boneChild.translateTo(x, y, z));
    }

    public void transform() {
        if (tickRotation != null) {
            //Note: teleport interpolation adds not exactly 100ms of interpolation, or not quite 2 ticks. 3 usually fits best
            armorStand.setHeadPose(tickRotation.add(boneBlueprint.getArmorStandHeadRotation().getX(), boneBlueprint.getArmorStandHeadRotation().getY(), boneBlueprint.getArmorStandHeadRotation().getZ()));
        }

        if (tickRotation != null || !tickPosition.isZero()) {
            if (parent != null) updateTickPositionBasedOnParentRotation();
            targetAnimationLocation = updateArmorStandLocation();
            //targetAnimationLocation = skeleton.getCurrentLocation().add(boneBlueprint.getBoneOriginOffset());
            armorStand.teleport(targetAnimationLocation);
        }

        boneChildren.forEach(Bone::transform);
        tickRotation = null;
        if (!tickPosition.isZero()) tickPosition = new Vector(0, 0, 0);
    }

    private void updateTickPositionBasedOnParentRotation() {
        //Calculate offset purely from the animation transform
        //Vector animationTranslationOffset = new Vector(0,0,0);
        EulerAngle parentRotation = parent.getTickRotation();

        if (parentRotation != null) {


            // Convert EulerAngle to Quaternion for rotation
            double yaw = parentRotation.getY();
            double pitch = parentRotation.getZ();
            double roll = -parentRotation.getX();

//            double cy = Math.cos(yaw * 0.5);
//            double sy = Math.sin(yaw * 0.5);
//            double cp = Math.cos(pitch * 0.5);
//            double sp = Math.sin(pitch * 0.5);
//            double cr = Math.cos(roll * 0.5);
//            double sr = Math.sin(roll * 0.5);
//
//            double q0 = cy * cp * cr - sy * sp * sr;
//            double q1 = cy * cp * sr + sy * sp * cr;
//            double q2 = sy * cp * sr - cy * sp * cr;
//            double q3 = sy * cp * cr + cy * sp * sr;

//            // Create a Quaternion from EulerAngle
//            Quaternion parentQuaternion = new Quaternion(q0, q1, q2, q3);

            //Vector fromParentToChild = getEntityBoneOriginUnanimated().subtract(parent.getEntityBoneOriginUnanimated()).toVector();
            Vector fromParentToChild = getBoneBlueprint().getArmorStandOffsetFromModel().subtract(parent.getBoneBlueprint().getArmorStandOffsetFromModel());

            Rotation rotation = new Rotation(RotationOrder.XYZ, parentRotation.getX(), parentRotation.getY(), parentRotation.getZ());
            Vector3D rotated = rotation.applyTo(new Vector3D(fromParentToChild.getX(), fromParentToChild.getY(), fromParentToChild.getZ()));
//            // Create a Quaternion from the fromParentToChild vector
//            Quaternion vectorQuaternion = new Quaternion(0, fromParentToChild.getX(), fromParentToChild.getY(), fromParentToChild.getZ());
//
//            // Rotate the vectorQuaternion by parentQuaternion
//            Quaternion rotatedVector = parentQuaternion.multiply(vectorQuaternion).multiply(parentQuaternion.getConjugate());
//
//            // Extract the rotated vector from the resulting Quaternion
//            Vector rotatedVectorResult = new Vector(rotatedVector.getQ1(), rotatedVector.getQ2(), rotatedVector.getQ3());

            //tickPosition.add(rotatedVectorResult.subtract(fromParentToChild));
            tickPosition.add(new Vector(rotated.getX(), rotated.getY(), rotated.getZ()).subtract(fromParentToChild));
        }
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
        return skeleton.getCurrentLocation().add(boneBlueprint.getBoneOriginOffset().add(tickPosition));
       // return skeleton.getCurrentLocation().add(boneBlueprint.getBoneOriginOffset().add(tickPosition));
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
