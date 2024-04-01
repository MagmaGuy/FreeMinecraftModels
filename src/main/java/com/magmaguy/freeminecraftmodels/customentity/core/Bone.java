package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.thirdparty.Floodgate;
import com.magmaguy.freeminecraftmodels.utils.TransformationMatrix;
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
    private final TransformationMatrix localMatrix = new TransformationMatrix();
    private final int reset = 20 * 60;
    //Relative to the parent
    private PacketModelEntity packetArmorStandEntity = null;
    private PacketModelEntity packetDisplayEntity = null;
    private Vector animationTranslation = new Vector();
    private Vector animationRotation = new Vector();
    private TransformationMatrix globalMatrix = new TransformationMatrix();
    private int counter = 0;

    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public void updateAnimationTranslation(double x, double y, double z) {
        animationTranslation = new Vector(x, y, z);
    }

    public void updateGlobalTransform() {
        if (parent != null) TransformationMatrix.multiplyMatrices(parent.globalMatrix, localMatrix, globalMatrix);
        else globalMatrix = localMatrix;
    }

    //Note that several optimizations might be possible here, but that syncing with a base entity is necessary.
    public void transform() {
        updateLocalTransform();
        updateGlobalTransform();

        boneChildren.forEach(Bone::transform);
        skeleton.getSkeletonWatchers().sendPackets(this);
    }

    public void generateDisplay() {
        updateLocalTransform();
        updateGlobalTransform();
        if (boneBlueprint.getModelID() != null) {
            initializeDisplayEntityBone();
            initializeArmorStandBone();
        }
        boneChildren.forEach(Bone::generateDisplay);
    }

    private void initializeDisplayEntityBone() {
        if (!DefaultConfig.useDisplayEntitiesWhenPossible) return;
        packetDisplayEntity = NMSManager.getAdapter().createPacketDisplayEntity(getDisplayEntityTargetLocation());
        packetDisplayEntity.initializeModel(getDisplayEntityTargetLocation(), boneBlueprint.getModelID());
        packetDisplayEntity.setScale(2.5f);
        packetDisplayEntity.sendLocationAndRotationPacket(getDisplayEntityTargetLocation(), getDisplayEntityRotation());
    }

    private void initializeArmorStandBone() {
        //todo: add way to disable armor stands later via config
        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(getArmorStandTargetLocation());
        packetArmorStandEntity.initializeModel(getArmorStandTargetLocation(), boneBlueprint.getModelID());
        packetArmorStandEntity.sendLocationAndRotationPacket(getArmorStandTargetLocation(), getArmorStandEntityRotation());
    }

    private Location getArmorStandTargetLocation() {
        float[] translatedGlobalMatrix = globalMatrix.applyTransformation(new float[]{0, 0, 0, 1});
        Location armorStandLocation = new Location(skeleton.getCurrentLocation().getWorld(), translatedGlobalMatrix[0], translatedGlobalMatrix[1], translatedGlobalMatrix[2]).add(skeleton.getCurrentLocation());
        armorStandLocation.setYaw(180);
        armorStandLocation.subtract(new Vector(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0));
        return armorStandLocation;
    }

    private Location getDisplayEntityTargetLocation() {
        float[] translatedGlobalMatrix = globalMatrix.applyTransformation(new float[]{0, 0, 0, 1});

        Location armorStandLocation = new Location(skeleton.getCurrentLocation().getWorld(), translatedGlobalMatrix[0], translatedGlobalMatrix[1], translatedGlobalMatrix[2]).add(skeleton.getCurrentLocation());
        if (!VersionChecker.serverVersionOlderThan(20, 0))
            armorStandLocation.setYaw(180);
        return armorStandLocation;
    }

    private EulerAngle getDisplayEntityRotation() {
        float[] rotation = globalMatrix.getRotation();
        if (VersionChecker.serverVersionOlderThan(20, 0))
            return new EulerAngle(rotation[0], rotation[1], rotation[2]);
        else
            return new EulerAngle(-rotation[0], rotation[1], -rotation[2]);

    }

    private EulerAngle getArmorStandEntityRotation() {
        float[] rotation = globalMatrix.getRotation();
        return new EulerAngle(-rotation[0], -rotation[1], rotation[2]);
    }

    public void updateAnimationRotation(double x, double y, double z) {
        animationRotation = new Vector(Math.toRadians(x), Math.toRadians(y), Math.toRadians(z));
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

    public void updateLocalTransform() {
        localMatrix.resetToIdentityMatrix();
        //Shift to model center
        localMatrix.translate(boneBlueprint.getModelCenter());

        //The bone is relative to its parent, so remove the offset of the parent
        if (parent != null) localMatrix.translate(parent.boneBlueprint.getModelCenter().multiply(-1));

        //Add the pivot point for the rotation - is removed later
        localMatrix.translate((float) -boneBlueprint.getBlueprintModelPivot().getX(), (float) -boneBlueprint.getBlueprintModelPivot().getY(), (float) -boneBlueprint.getBlueprintModelPivot().getZ());

        //Animate
        localMatrix.rotate((float) animationRotation.getX(), (float) -(animationRotation.getY() + Math.PI), (float) animationRotation.getZ());
        localMatrix.translate((float) animationTranslation.getX(), (float) animationTranslation.getY(), (float) animationTranslation.getZ());

        //Apply the bone's default rotation to the matrix
        localMatrix.rotate(
                (float) boneBlueprint.getBlueprintOriginalBoneRotation().getX(),
                (float) (boneBlueprint.getBlueprintOriginalBoneRotation().getY() + Math.PI),
                (float) -boneBlueprint.getBlueprintOriginalBoneRotation().getZ());


        //Remove the pivot point, go back to the model center
        localMatrix.translate((float) boneBlueprint.getBlueprintModelPivot().getX(), (float) boneBlueprint.getBlueprintModelPivot().getY(), (float) boneBlueprint.getBlueprintModelPivot().getZ());

        //rotate by yaw amount
        if (parent == null) {
            localMatrix.rotate(0, (float) -Math.toRadians(skeleton.getCurrentLocation().getYaw() + 180), 0);
        }
    }

    public void sendUpdatePacket() {
        counter++;
        if (counter > reset) {
            counter = 0;
            skeleton.getSkeletonWatchers().reset();
        }
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
        if (packetArmorStandEntity != null)
            packetArmorStandEntity.hideFrom(playerUUID);
        if (packetDisplayEntity != null)
            packetDisplayEntity.hideFrom(playerUUID);
    }

    public void setHorseLeatherArmorColor(Color color) {
        if (packetArmorStandEntity != null) packetArmorStandEntity.setHorseLeatherArmorColor(color);
        if (packetDisplayEntity != null) packetDisplayEntity.setHorseLeatherArmorColor(color);
    }
}
