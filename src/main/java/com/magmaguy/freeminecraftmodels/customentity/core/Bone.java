package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.thirdparty.Floodgate;
import com.magmaguy.freeminecraftmodels.utils.VersionChecker;
import lombok.Getter;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class Bone {
    @Getter
    private final BoneBlueprint boneBlueprint;
    @Getter
    private final List<Bone> boneChildren = new ArrayList<>();
    @Getter
    private final Bone parent;
    private final Skeleton skeleton;
    //Relative to the parent
    private PacketModelEntity packetArmorStandEntity = null;
    private PacketModelEntity packetDisplayEntity = null;
    private Vector animationTranslation = new Vector();
    private Vector animationRotation = new Vector();

    @Getter
    private ArmorStandBone armorStandBone;
    @Getter
    private DisplayEntityBone displayEntityBone;

    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public void updateAnimationRotation(double x, double y, double z) {
        animationRotation = new Vector(Math.toRadians(x), -Math.toRadians(y), Math.toRadians(z));
    }

    public void updateAnimationTranslation(double x, double y, double z) {
        animationTranslation = new Vector(x, y, z);
    }

    //Note that several optimizations might be possible here, but that syncing with a base entity is necessary.
    public void transform() {
        //Inherit rotation and translation values from parents
        if (armorStandBone != null)
            armorStandBone.transform(animationRotation, animationTranslation, skeleton.getCurrentLocation().getYaw());
        if (displayEntityBone != null)
            displayEntityBone.transform(animationRotation, animationTranslation, skeleton.getCurrentLocation().getYaw());

        boneChildren.forEach(Bone::transform);
        skeleton.getSkeletonWatchers().sendPackets(this);
    }

    public void generateDisplay() {
        if (boneBlueprint.getModelID() != null) {
            initializeDisplayEntityBone();
            initializeArmorStandBone();
        }
        boneChildren.forEach(Bone::generateDisplay);
    }

    private void initializeDisplayEntityBone() {
        if (!DefaultConfig.useDisplayEntitiesWhenPossible) return;
        if (parent == null || parent.getDisplayEntityBone() == null)
            displayEntityBone = new DisplayEntityBone(
                    boneBlueprint.getDisplayEntityBlueprintModelCenter(),
                    null,
                    boneBlueprint.getBlueprintModelPivot(),
                    boneBlueprint.getBlueprintOriginalBoneRotation(),
                    true,
                    null);
        else
            displayEntityBone = new DisplayEntityBone(
                    boneBlueprint.getDisplayEntityBlueprintModelCenter(),
                    parent.getBoneBlueprint().getDisplayEntityBlueprintModelCenter(),
                    boneBlueprint.getBlueprintModelPivot(),
                    boneBlueprint.getBlueprintOriginalBoneRotation(),
                    parent == null,
                    parent.getDisplayEntityBone().getGlobalMatrix());

        packetDisplayEntity = NMSManager.getAdapter().createPacketDisplayEntity(getDisplayEntityTargetLocation());

        updateDisplayEntityBone();

        packetDisplayEntity.initializeModel(getDisplayEntityTargetLocation(), boneBlueprint.getModelID());
        packetDisplayEntity.setScale(2.5f);
        packetDisplayEntity.sendLocationAndRotationPacket(getDisplayEntityTargetLocation(), getDisplayEntityRotation());
    }

    private void initializeArmorStandBone() {
        //todo: add way to disable armor stands later via config
        if (parent == null || parent.getArmorStandBone() == null)
            armorStandBone = new ArmorStandBone(boneBlueprint.getArmorStandBlueprintModelCenter(),
                    null,
                    boneBlueprint.getBlueprintModelPivot(),
                    boneBlueprint.getBlueprintOriginalBoneRotation(),
                    true,
                    null);
        else
            armorStandBone = new ArmorStandBone(boneBlueprint.getArmorStandBlueprintModelCenter(),
                    parent.getBoneBlueprint().getArmorStandBlueprintModelCenter(),
                    boneBlueprint.getBlueprintModelPivot(),
                    boneBlueprint.getBlueprintOriginalBoneRotation(),
                    parent == null,
                    parent.getArmorStandBone().getGlobalMatrix());

        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(getArmorStandTargetLocation());

        updateArmorStandBone();

        packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), boneBlueprint.getModelID());
        packetArmorStandEntity.sendLocationAndRotationPacket(getArmorStandTargetLocation(), getArmorStandEntityRotation());
    }

    private void updateDisplayEntityBone() {
        displayEntityBone.updateLocalTransform(animationRotation, animationTranslation, skeleton.getCurrentLocation().getYaw());
        if (parent == null || parent.getDisplayEntityBone() == null) {
            displayEntityBone.updateGlobalTransform();
        } else {
            displayEntityBone.updateGlobalTransform();
        }
    }

    private void updateArmorStandBone() {
        armorStandBone.updateLocalTransform(animationRotation, animationTranslation, skeleton.getCurrentLocation().getYaw());
        if (parent == null || parent.getArmorStandBone() == null) {
            armorStandBone.updateGlobalTransform();
        } else {
            armorStandBone.updateGlobalTransform();
        }
    }

    private Location getArmorStandTargetLocation() {
        float[] translatedGlobalMatrix = armorStandBone.getGlobalMatrix().applyTransformation(new float[]{0, 0, 0, 1});
        Location armorStandLocation = new Location(skeleton.getCurrentLocation().getWorld(), translatedGlobalMatrix[0], translatedGlobalMatrix[1], translatedGlobalMatrix[2]).add(skeleton.getCurrentLocation());
        armorStandLocation.setYaw(180);
        return armorStandLocation;
    }

    private Location getDisplayEntityTargetLocation() {
        float[] translatedGlobalMatrix = displayEntityBone.getGlobalMatrix().applyTransformation(new float[]{0, 0, 0, 1});
        return new Location(skeleton.getCurrentLocation().getWorld(), translatedGlobalMatrix[0], translatedGlobalMatrix[1], translatedGlobalMatrix[2]).add(skeleton.getCurrentLocation());
    }

    private EulerAngle getDisplayEntityRotation() {
        float[] rotation = displayEntityBone.getGlobalMatrix().getRotation();
        return new EulerAngle(rotation[0], rotation[1], rotation[2]);
    }

    private EulerAngle getArmorStandEntityRotation() {
        float[] rotation = armorStandBone.getGlobalMatrix().getRotation();
        return new EulerAngle(-rotation[0], -rotation[1], rotation[2]);
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
                    getArmorStandEntityRotation());
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
            packetArmorStandEntity.displayTo(player.getUniqueId());
        else if (packetDisplayEntity != null)
            packetDisplayEntity.displayTo(player.getUniqueId());
    }

    public void hideFrom(UUID playerUUID) {
        packetArmorStandEntity.hideFrom(playerUUID);
        if (packetDisplayEntity != null)
            packetDisplayEntity.hideFrom(playerUUID);
    }

    public void setHorseLeatherArmorColor(Color color) {
        if (packetArmorStandEntity != null) packetArmorStandEntity.setHorseLeatherArmorColor(color);
        if (packetDisplayEntity != null) packetDisplayEntity.setHorseLeatherArmorColor(color);
    }
}
