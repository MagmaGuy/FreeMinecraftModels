package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.packets.PacketEntityDisplayHelper;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
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
    // The animation pose. A model nobody watches skips sampling it, so the public getters sample it first; the
    // transforms read the fields directly, since a model only transforms while watched or on admission, both
    // after the pose is sampled.
    private final Vector3f animationTranslation = new Vector3f();
    private final Vector3f animationRotation = new Vector3f();
    private final Vector3f animationScale = new Vector3f(-1, -1, -1);
    // IK rotation override - when set, this takes priority over animation rotation
    private Vector3f ikRotation = null;
    // Set on the main thread, read by the model clock's transforms.
    private volatile boolean hidden = false;

    public Bone(BoneBlueprint boneBlueprint, Bone parent, Skeleton skeleton) {
        this.boneBlueprint = boneBlueprint;
        this.parent = parent;
        this.skeleton = skeleton;
        this.boneTransforms = new BoneTransforms(this, parent);
        for (BoneBlueprint child : boneBlueprint.getBoneBlueprintChildren())
            boneChildren.add(new Bone(child, this, skeleton));
    }

    public Vector3f getAnimationTranslation() {
        skeleton.freshenPose();
        return animationTranslation;
    }

    public Vector3f getAnimationRotation() {
        skeleton.freshenPose();
        return animationRotation;
    }

    public Vector3f getAnimationScale() {
        skeleton.freshenPose();
        return animationScale;
    }

    public Vector3f getIkRotation() {
        skeleton.freshenPose();
        return ikRotation;
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
     * Gets the effective rotation for this bone.
     * Returns IK rotation if set, otherwise returns animation rotation.
     *
     * @return The effective rotation in radians
     */
    public Vector3f getEffectiveRotation() {
        skeleton.freshenPose();
        return poseRotation();
    }

    /** Whether this bone is hidden: drawn at no size, and every bone under it with it. */
    public boolean isHidden() {
        return hidden;
    }

    /**
     * Hides or shows this bone and the bones under it. A hidden bone shrinks to nothing on Java display entities;
     * bones drawn as armor stands cannot shrink and stay as they are. See {@code ModeledEntity.setBoneHidden}.
     */
    public void setHidden(boolean hidden) {
        if (this.hidden == hidden) return;
        this.hidden = hidden;
        markDirtyBelow();
    }

    private void markDirtyBelow() {
        boneTransforms.markDirty();
        for (Bone child : boneChildren) child.markDirtyBelow();
    }

    /** The pose as last sampled, for the transforms. */
    Vector3f poseTranslation() {
        return animationTranslation;
    }

    Vector3f poseRotation() {
        return ikRotation != null ? ikRotation : animationRotation;
    }

    Vector3f poseScale() {
        return animationScale;
    }

    //Note that several optimizations might be possible here, but that syncing with a base entity is necessary.
    public void transform(AbstractPacketBundle abstractPacketBundle) {
        boneTransforms.transform();
        for (int childIndex = 0; childIndex < boneChildren.size(); childIndex++) {
            boneChildren.get(childIndex).transform(abstractPacketBundle);
        }
        skeleton.getSkeletonWatchers().sendPackets(this, abstractPacketBundle);
    }

    void prepareDisplayPose(AbstractPacketBundle packetBundle) {
        boneTransforms.transform();
        boneTransforms.prepareDisplayPose(packetBundle);
        for (Bone child : boneChildren) child.prepareDisplayPose(packetBundle);
    }

    public void generateDisplay() {
        boneTransforms.generateDisplay();
        boneChildren.forEach(Bone::generateDisplay);
    }

    public void remove() {
        PacketModelEntity armorStand = boneTransforms.getPacketArmorStandEntity();
        if (armorStand != null) armorStand.remove();
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
        displayTo(player, null);
    }

    public void displayTo(Player player, AbstractPacketBundle packetBundle) {
        if (player == null || !player.isValid() || !player.isOnline()) return;
        // Mount points are empty and have no packet entity to show.
        if (boneBlueprint.isMountPoint()) return;
        boolean isBedrock = BedrockChecker.isBedrock(player);
        if (isBedrock && !DefaultConfig.sendCustomModelsToBedrockClientsV2) {
            if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled())
                com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                        "Bone.displayTo SKIPPED (bedrock + V2=false) — player=" + player.getName()
                                + " bone=" + boneBlueprint.getBoneName());
            return;
        }
        if (boneBlueprint.isNameTag()) return; // ModeledEntity owns the complete nameplate.
        if (boneTransforms.hasArmorStand() &&
                (!DefaultConfig.useDisplayEntitiesWhenPossible ||
                        isBedrock ||
                        VersionChecker.serverVersionOlderThan(19, 4))) {
            PacketModelEntity armorStand = boneTransforms.armorStand();
            if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled())
                com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                        "Bone.displayTo branch=ARMOR_STAND bedrock=" + isBedrock
                                + " player=" + player.getName()
                                + " bone=" + boneBlueprint.getBoneName()
                                + " modelID=" + boneBlueprint.getModelID()
                                + " packetClass=" + armorStand.getClass().getSimpleName());
            PacketEntityDisplayHelper.displayToPlayer(armorStand, player, packetBundle);
        } else if (boneTransforms.getPacketDisplayEntity() != null) {
            if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled())
                com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                        "Bone.displayTo branch=DISPLAY_ENTITY bedrock=" + isBedrock
                                + " player=" + player.getName()
                                + " bone=" + boneBlueprint.getBoneName()
                                + " modelID=" + boneBlueprint.getModelID());
            PacketEntityDisplayHelper.displayToPlayer(boneTransforms.getPacketDisplayEntity(), player, packetBundle);
        } else {
            if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.enabled())
                com.magmaguy.freeminecraftmodels.thirdparty.BedrockDebugLog.log(
                        "Bone.displayTo branch=NONE — no packet entity initialized! bedrock=" + isBedrock
                                + " player=" + player.getName()
                                + " bone=" + boneBlueprint.getBoneName()
                                + " (BUG: every bone except mount-points should have ≥1 packet entity)");
        }
    }

    public void hideFrom(UUID playerUUID) {
        PacketModelEntity armorStand = boneTransforms.getPacketArmorStandEntity();
        if (armorStand != null) armorStand.hideFrom(playerUUID);
        if (boneTransforms.getPacketDisplayEntity() != null)
            boneTransforms.getPacketDisplayEntity().hideFrom(playerUUID);
    }

    public void setHorseLeatherArmorColor(Color color) {
        if (boneBlueprint.isMountPoint()) return;
        boneTransforms.setArmorStandTint(color);
        if (boneTransforms.getPacketDisplayEntity() != null)
            boneTransforms.getPacketDisplayEntity().setHorseLeatherArmorColor(color);
        // Tint lives in the entity-data (metadata) packet, which is now only resent
        // when the bone's transform changes. Force the next tick to push it so the
        // damage flash / persistent tint actually reaches clients on still bones.
        boneTransforms.markDirty();
    }

    /** Fixed light for this bone's display, 0 to 15 each; a negative block light drops the override. */
    public void setBrightnessOverride(int blockLight, int skyLight) {
        if (boneBlueprint.isMountPoint() || boneTransforms.getPacketDisplayEntity() == null) return;
        if (blockLight < 0) boneTransforms.getPacketDisplayEntity().clearBrightness();
        else boneTransforms.getPacketDisplayEntity().setBrightness(blockLight, skyLight);
        // The light rides the metadata packet, which still bones only resend when told to.
        boneTransforms.markDirty();
    }

    public void spawnParticles(Particle particle, double speed) {
        Location boneLocation;
        if (boneTransforms.getPacketDisplayEntity() != null) {
            boneLocation = boneTransforms.getDisplayEntityTargetLocation();
            if (boneLocation.getWorld() == null) return;
            boneLocation.getWorld().spawnParticle(particle, boneLocation, 1, 1, 1, 1, speed);
        } else if (boneTransforms.getPacketArmorStandEntity() != null || boneBlueprint.isMountPoint()) {
            // Mount points puff where their armor stand stood, though they no longer get one.
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
