package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.entities.ModelArmorStand;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import com.magmaguy.freeminecraftmodels.utils.EulerAngleUtils;
import lombok.Getter;
import org.apache.commons.math3.complex.Quaternion;
import org.apache.commons.math3.geometry.euclidean.threed.*;
import org.apache.commons.math3.util.FastMath;
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
    private Vector tickPosition = null;
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
        tickRotation = new EulerAngle(Math.toRadians(newX), Math.toRadians(newY), Math.toRadians(newZ));
    }

    public void translateTo(double x, double y, double z) {
        if (armorStand == null) return;
        tickPosition = new Vector(x, y, z);
    }

    public void transform(boolean parentUpdate, EulerAngle parentRotation) {
        if (tickRotation != null) {
//            if (getBoneBlueprint().getBoneName().contains("bone9"))
//                Developer.debug(Developer.eulerAngleToString(tickRotation));
            //todo: found the issue - the parent rotation needs to ROTATE the tickrotation, not just add to it.
//           Quaternion quaternion = eulerToQuaternion(parentRotation);
//           Rotation rotation = new Rotation(quaternion.getQ0(), quaternion.getQ1(), quaternion.getQ2(), quaternion.getQ3(), false);
//           Vector3D rotated = rotation.applyTo(new Vector3D(tickRotation.getX(), tickRotation.getY(), tickRotation.getZ()));
//            tickRotation = EulerAngleUtils.add(new EulerAngle(rotated.getX(), rotated.getY(), rotated.getZ()), boneBlueprint.getArmorStandHeadRotation());
            tickRotation = EulerAngleUtils.add(parentRotation, tickRotation, boneBlueprint.getArmorStandHeadRotation());
        }
        else {
            tickRotation = EulerAngleUtils.add(parentRotation, boneBlueprint.getArmorStandHeadRotation());
        }

        if (!EulerAngleUtils.isZero(tickRotation)) armorStand.setHeadPose(tickRotation);

        if (!EulerAngleUtils.isZero(tickRotation) || tickPosition != null || parentUpdate) {
            parentUpdate = true;
            if (parent != null) updateTickPositionBasedOnParentRotation(parentRotation);
            updateTickPositionBasedOnParentTranslation();
            targetAnimationLocation = updateArmorStandLocation();
            armorStand.teleport(targetAnimationLocation);
        }

        boolean finalParentUpdate = parentUpdate;
        boneChildren.forEach(boneChild -> boneChild.transform(finalParentUpdate, tickRotation));
        tickRotation = null;
        tickPosition = null;
    }

//    private void updateTickPositionBasedOnParentRotation(EulerAngle parentRotation) {
//        if (parentRotation != null) {
//            Vector fromParentToChild = getBoneBlueprint().getArmorStandOffsetFromModel().subtract(parent.getBoneBlueprint().getArmorStandOffsetFromModel());
//            Vector rotatedVector = fromParentToChild.clone();
//            rotatedVector.rotateAroundX(-parentRotation.getX());
//            rotatedVector.rotateAroundY(-parentRotation.getY());
//            rotatedVector.rotateAroundZ(parentRotation.getZ());
//            if (tickPosition == null) tickPosition = new Vector(0, 0, 0);
//            Vector vector = rotatedVector.subtract(fromParentToChild);
//            tickPosition.add(vector);
//        }
//    }

    private void updateTickPositionBasedOnParentRotation(EulerAngle parentRotation) {
        if (parentRotation != null) {
            Vector fromParentToChild = getBoneBlueprint().getArmorStandOffsetFromModel()
                    .subtract(parent.getBoneBlueprint().getArmorStandOffsetFromModel());

            // Convert Euler angles to a Quaternion
            Quaternion rotationQuaternion = eulerToQuaternion(parentRotation);

            // Rotate the vector using the quaternion
            Vector rotatedVector = rotate(rotationQuaternion, fromParentToChild);

            if (tickPosition == null) tickPosition = new Vector(0, 0, 0);
            Vector vector = rotatedVector.clone().subtract(fromParentToChild);
//            if (boneBlueprint.getBoneName().contains("bone9")) {
//                Developer.debug("vector " + Developer.vectorToString(vector));
//                Developer.debug("parent " + Developer.eulerAngleToString(parentRotation));
//                Developer.debug("rotated vector " + Developer.vectorToString(rotatedVector));
//                Developer.debug("from parent to child " + Developer.vectorToString(fromParentToChild));
//            }
            tickPosition.add(vector);
        }
    }

    private Quaternion eulerToQuaternion(EulerAngle eulerAngle) {
        double yaw = eulerAngle.getZ();
        double pitch = -eulerAngle.getY();
        double roll = -eulerAngle.getX();

        double cy = Math.cos(yaw * 0.5);
        double sy = Math.sin(yaw * 0.5);
        double cp = Math.cos(pitch * 0.5);
        double sp = Math.sin(pitch * 0.5);
        double cr = Math.cos(roll * 0.5);
        double sr = Math.sin(roll * 0.5);

        double w = cr * cp * cy + sr * sp * sy;
        double x = sr * cp * cy - cr * sp * sy;
        double y = cr * sp * cy + sr * cp * sy;
        double z = cr * cp * sy - sr * sp * cy;

        return new Quaternion(w, x, y, z);
    }

    public Vector rotate(Quaternion quaternion, Vector rotation) {
        Quaternion rotationQuaternion = new Quaternion(0, rotation.getX(), rotation.getY(), rotation.getZ());
        Quaternion rotatedQuaternion = quaternion.multiply(rotationQuaternion).multiply(quaternion.getConjugate());
        return new Vector(rotatedQuaternion.getQ1(), rotatedQuaternion.getQ2(), rotatedQuaternion.getQ3());
    }

    //If the expected origin of the parent is offset from its actual location, translate that offset to the child
    private void updateTickPositionBasedOnParentTranslation() {
        if (parent == null) return;
        Vector parentOffsetFromPivot = parent.getCurrentOffsetFromSkeletonLocation().subtract(parent.getBoneBlueprint().getBoneOriginOffset());
        if (tickPosition == null) tickPosition = new Vector(0, 0, 0);
        tickPosition.add(parentOffsetFromPivot);
    }

    private Vector getCurrentOffsetFromSkeletonLocation() {
        return armorStand.getLocation().subtract(skeleton.getCurrentLocation()).toVector();
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
        if (tickPosition != null)
            return skeleton.getCurrentLocation().add(boneBlueprint.getBoneOriginOffset().add(tickPosition));
        else return skeleton.getCurrentLocation().add(boneBlueprint.getBoneOriginOffset());
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
