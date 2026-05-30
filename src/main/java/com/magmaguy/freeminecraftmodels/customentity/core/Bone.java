package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.packets.PacketEntityDisplayHelper;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
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
    boolean warned = false;
    @Getter
    private Vector3f animationTranslation = new Vector3f();
    @Getter
    private Vector3f animationRotation = new Vector3f();
    @Getter
    private Vector3f animationScale = new Vector3f(-1, -1, -1);
    // IK rotation override - when set, this takes priority over animation rotation
    @Getter
    private Vector3f ikRotation = null;

    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        this.boneTransforms = new BoneTransforms(this, parent);
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public void updateAnimationTranslation(float x, float y, float z) {
        animationTranslation.set(x, y, z);
    }

    // x, y, z are already in radians (precomputed in AnimationBlueprint at load time).
    public void updateAnimationRotation(double x, double y, double z) {
        animationRotation.set((float) x, (float) y, (float) z);
    }

    public void updateAnimationScale(float scaleX, float scaleY, float scaleZ) {
        this.animationScale.set(scaleX, scaleY, scaleZ);
    }

    /**
     * Sets the IK rotation for this bone.
     * When set, this rotation takes priority over animation rotation.
     *
     * @param rotation The IK-solved rotation in radians
     */
    public void setIKRotation(Vector3f rotation) {
        this.ikRotation = new Vector3f(rotation);
    }

    /**
     * Clears the IK rotation, allowing animation rotation to be used.
     */
    public void clearIKRotation() {
        this.ikRotation = null;
    }

    /**
     * Checks if this bone currently has IK rotation applied.
     *
     * @return true if IK rotation is active
     */
    public boolean hasIKRotation() {
        return ikRotation != null;
    }

    /**
     * Gets the effective rotation for this bone.
     * Returns IK rotation if set, otherwise returns animation rotation.
     *
     * @return The effective rotation in radians
     */
    public Vector3f getEffectiveRotation() {
        return ikRotation != null ? ikRotation : animationRotation;
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

    public void displayTo(Player player) {
        if (player == null || !player.isValid() || !player.isOnline()) return;
        // Mount-point bones have a packet armor stand created in
        // BoneTransforms.initializeMountPointBone for position tracking, but it has
        // no model and no invisibility flags set. Without this early-return the
        // bone is routed through the ARMOR_STAND branch below and a bare vanilla
        // armor stand is sent to every viewer — visible on Bedrock (and Java if
        // no resource-pack attachable covers it). The packet entity itself is only
        // used server-side; it must never be displayed to a player.
        if (boneBlueprint.isMountPoint()) return;
        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock && !DefaultConfig.sendCustomModelsToBedrockClientsV2) {
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "Bone.displayTo SKIPPED (bedrock + V2=false) — player=" + player.getName()
                            + " bone=" + boneBlueprint.getBoneName());
            return;
        }
        if (boneBlueprint.isNameTag()) {
            if (boneTransforms.getPacketTextDisplayArmorStandEntity() == null) {
                if (!warned) {
                    Logger.warn("nametag bone did not spawn name tag");
                    warned = true;
                }
                return;
            }
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "Bone.displayTo branch=TEXT bedrock=" + isBedrock
                            + " player=" + player.getName()
                            + " bone=" + boneBlueprint.getBoneName());
            PacketEntityDisplayHelper.displayToPlayer(boneTransforms.getPacketTextDisplayArmorStandEntity(), player);
        } else if (boneTransforms.getPacketArmorStandEntity() != null &&
                (!DefaultConfig.useDisplayEntitiesWhenPossible ||
                        isBedrock ||
                        VersionChecker.serverVersionOlderThan(19, 4))) {
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "Bone.displayTo branch=ARMOR_STAND bedrock=" + isBedrock
                            + " player=" + player.getName()
                            + " bone=" + boneBlueprint.getBoneName()
                            + " modelID=" + boneBlueprint.getModelID()
                            + " packetClass=" + boneTransforms.getPacketArmorStandEntity().getClass().getSimpleName());
            PacketEntityDisplayHelper.displayToPlayer(boneTransforms.getPacketArmorStandEntity(), player);
        } else if (boneTransforms.getPacketDisplayEntity() != null) {
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "Bone.displayTo branch=DISPLAY_ENTITY bedrock=" + isBedrock
                            + " player=" + player.getName()
                            + " bone=" + boneBlueprint.getBoneName()
                            + " modelID=" + boneBlueprint.getModelID());
            PacketEntityDisplayHelper.displayToPlayer(boneTransforms.getPacketDisplayEntity(), player);
        } else {
            com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                    "Bone.displayTo branch=NONE — no packet entity initialized! bedrock=" + isBedrock
                            + " player=" + player.getName()
                            + " bone=" + boneBlueprint.getBoneName()
                            + " (BUG: every bone except mount-points should have ≥1 packet entity)");
        }
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
        if (boneBlueprint.isMountPoint()) return;
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

    public Location getBoneLocation() {
        // Mount-point bones need the raw global matrix position with no
        // armor-stand pivot offset — the position is used to place a real
        // server-side armor stand that players ride.
        if (boneBlueprint.isMountPoint()) {
            return boneTransforms.getMountPointTargetLocation();
        }
        if (boneTransforms.getPacketDisplayEntity() != null) {
            return boneTransforms.getDisplayEntityTargetLocation();
        } else if (boneTransforms.getPacketArmorStandEntity() != null) {
            return boneTransforms.getArmorStandTargetLocation();
        } else {
            // Fallback for meta bones without packet entities
            return boneTransforms.getDisplayEntityTargetLocation();
        }
    }

}
