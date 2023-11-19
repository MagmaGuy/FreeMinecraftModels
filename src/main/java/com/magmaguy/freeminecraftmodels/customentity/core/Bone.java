package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.entities.ModelArmorStand;
import com.magmaguy.freeminecraftmodels.utils.EulerAngleUtils;
import lombok.Getter;
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
    //    private EulerAngle lastKeyframeRotation = null;
    @Getter
    private ArmorStand armorStand;
    @Getter
    private EulerAngle tickRotation = null;
    @Getter
    private Vector tickPosition = null;
    //This is the location that the bone should be at when the tick ends. Due to teleport interpolation, it takes about 100ms to actually get there
    private Location targetAnimationLocation = null;
//    private EulerAngle lastRotation = null;
//    private EulerAngle lastParentRotation = null;

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
//        lastKeyframeRotation = tickRotation;
    }

    public void translateTo(double x, double y, double z) {
        if (armorStand == null) return;
        tickPosition = new Vector(x, y, z);
    }

    public void transform(boolean parentUpdate, EulerAngle parentRotation) {
        if (tickRotation != null)
            //Case where there is a keyframe on this tick
            tickRotation = EulerAngleUtils.add(parentRotation, tickRotation, boneBlueprint.getArmorStandHeadRotation());
//        else if (lastKeyframeRotation != null)
//            //Case where there has been a keyframe before
//            tickRotation = EulerAngleUtils.add(parentRotation, lastKeyframeRotation, boneBlueprint.getArmorStandHeadRotation());
//        else if (lastParentRotation != null && lastParentRotation == parentRotation)
//            //Case where the parent hasn't changed its rotation since the last tick, meaning things stay as they are
//            tickRotation = lastRotation;
        else
            tickRotation = EulerAngleUtils.add(parentRotation, boneBlueprint.getArmorStandHeadRotation());

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
//        if (tickRotation != null) lastRotation = tickRotation;
//        lastParentRotation = parentRotation;
        tickRotation = null;
        tickPosition = null;
    }

    private void updateTickPositionBasedOnParentRotation(EulerAngle parentRotation) {
        if (parentRotation != null) {
            Vector fromParentToChild = getBoneBlueprint().getArmorStandOffsetFromModel().subtract(parent.getBoneBlueprint().getArmorStandOffsetFromModel());
            Vector rotatedVector = fromParentToChild.clone();
            rotatedVector.rotateAroundX(-parentRotation.getX());
            rotatedVector.rotateAroundY(-parentRotation.getY());
            rotatedVector.rotateAroundZ(parentRotation.getZ());
            if (tickPosition == null) tickPosition = new Vector(0, 0, 0);
            Vector vector = rotatedVector.subtract(fromParentToChild);
            tickPosition.add(vector);
        }
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
