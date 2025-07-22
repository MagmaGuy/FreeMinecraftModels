package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.thirdparty.BedrockChecker;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.VersionChecker;
import lombok.Getter;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.joml.Vector3f;

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
    @Getter
    private final Skeleton skeleton;
    @Getter
    private final BoneTransforms boneTransforms;
    @Getter
    private Vector3f animationTranslation = new Vector3f();
    @Getter
    private Vector3f animationRotation = new Vector3f();
    @Getter
    private float animationScale = -1;

    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        this.boneTransforms = new BoneTransforms(this, parent);
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public void updateAnimationTranslation(float x, float y, float z) {
        animationTranslation = new Vector3f(x, y, z);
    }

    public void updateAnimationRotation(double x, double y, double z) {
        animationRotation = new Vector3f((float) Math.toRadians(x), (float) Math.toRadians(y), (float) Math.toRadians(z));
    }

    public void updateAnimationScale(float animationScale) {
        this.animationScale = animationScale;
    }

    //Note that several optimizations might be possible here, but that syncing with a base entity is necessary.
    public void transform(AbstractPacketBundle abstractPacketBundle) {
        boneTransforms.transform();
        boneChildren.forEach(childBone -> childBone.transform(abstractPacketBundle));
        skeleton.getSkeletonWatchers().sendPackets(this, abstractPacketBundle);
    }

    public void generateDisplay() {
        boneTransforms.generateDisplay();
        boneChildren.forEach(Bone::generateDisplay);
    }

    public void setNameVisible(boolean visible) {
        boneChildren.forEach(child -> child.setNameVisible(visible));
    }

    public void remove() {
        if (boneTransforms.getPacketTextDisplayArmorStandEntity() != null)
            boneTransforms.getPacketTextDisplayArmorStandEntity().remove();
        if (boneTransforms.getPacketArmorStandEntity() != null) boneTransforms.getPacketArmorStandEntity().remove();
        if (boneTransforms.getPacketDisplayEntity() != null) boneTransforms.getPacketDisplayEntity().remove();
        boneChildren.forEach(Bone::remove);
    }

    protected void getAllChildren(HashMap<String, Bone> children) {
        boneChildren.forEach(child -> {
            children.put(child.getBoneBlueprint().getBoneName(), child);
            child.getAllChildren(children);
        });
    }

    public void sendUpdatePacket(AbstractPacketBundle abstractPacketBundle) {
        boneTransforms.sendUpdatePacket(abstractPacketBundle);
    }

    boolean warned = false;

    public void displayTo(Player player) {
        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock && DefaultConfig.sendCustomModelsToBedrockClients) return;
        if (boneBlueprint.isNameTag()) {
            if (boneTransforms.getPacketTextDisplayArmorStandEntity() == null) {
                if (!warned) {
                    Logger.warn("nametag bone did not spawn name tag");
                    warned = true;
                }
                return;
            }
            boneTransforms.getPacketTextDisplayArmorStandEntity().displayTo(player.getUniqueId());
        } else if (boneTransforms.getPacketArmorStandEntity() != null &&
                (!DefaultConfig.useDisplayEntitiesWhenPossible ||
                        isBedrock ||
                        VersionChecker.serverVersionOlderThan(19, 4)))
            boneTransforms.getPacketArmorStandEntity().displayTo(player.getUniqueId());
        else if (boneTransforms.getPacketDisplayEntity() != null)
            boneTransforms.getPacketDisplayEntity().displayTo(player.getUniqueId());
    }

    public void hideFrom(UUID playerUUID) {
        if (boneTransforms.getPacketTextDisplayArmorStandEntity() != null)
            boneTransforms.getPacketTextDisplayArmorStandEntity().hideFrom(playerUUID);
        if (boneTransforms.getPacketArmorStandEntity() != null)
            boneTransforms.getPacketArmorStandEntity().hideFrom(playerUUID);
        if (boneTransforms.getPacketDisplayEntity() != null)
            boneTransforms.getPacketDisplayEntity().hideFrom(playerUUID);
    }

    public void setHorseLeatherArmorColor(Color color) {
        if (boneTransforms.getPacketArmorStandEntity() != null)
            boneTransforms.getPacketArmorStandEntity().setHorseLeatherArmorColor(color);
        if (boneTransforms.getPacketDisplayEntity() != null)
            boneTransforms.getPacketDisplayEntity().setHorseLeatherArmorColor(color);
    }

    public void spawnParticles(Particle particle, double speed) {
        Location boneLocation;
        if (boneTransforms.getPacketDisplayEntity() != null) {
            boneLocation = boneTransforms.getDisplayEntityTargetLocation();
            if (boneLocation.getWorld() == null) return;
            boneLocation.getWorld().spawnParticle(particle, boneLocation, 1, 1, 1, 1, speed);
        } else if (boneTransforms.getPacketArmorStandEntity() != null) {
            boneLocation = boneTransforms.getArmorStandTargetLocation();
            if (boneLocation.getWorld() == null) return;
            boneLocation.getWorld().spawnParticle(particle, boneLocation, 1, 1, 1, 1, speed);
        }
    }

    public void teleport() {
        sendTeleportPacket();
        boneChildren.forEach(Bone::teleport);
    }

    private void sendTeleportPacket() {
        if (boneTransforms.getPacketArmorStandEntity() != null) {
            boneTransforms.getPacketArmorStandEntity().teleport(boneTransforms.getArmorStandTargetLocation());
        }
        if (boneTransforms.getPacketDisplayEntity() != null) {
            boneTransforms.getPacketDisplayEntity().teleport(boneTransforms.getDisplayEntityTargetLocation());
        }
        skeleton.getSkeletonWatchers().resync(true);
    }
}
