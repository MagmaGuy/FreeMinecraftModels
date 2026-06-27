package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketTextEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.utils.TransformationMatrix;
import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.VersionChecker;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

public class BoneTransforms {

    private final Bone parent;
    private final Bone bone;
    private final TransformationMatrix localMatrix = new TransformationMatrix();
    // Reused Vector for rotateAnimation()'s local math — never escapes the method.
    private final Vector animationRotationVector = new Vector();
    private TransformationMatrix globalMatrix = new TransformationMatrix();
    @Getter
    private PacketModelEntity packetArmorStandEntity = null;
    @Getter
    private PacketModelEntity packetDisplayEntity = null;
    @Getter
    private PacketTextEntity packetTextDisplayArmorStandEntity = null;

    public BoneTransforms(Bone bone, Bone parent) {
        this.bone = bone;
        this.parent = parent;
    }

    public void setTextDisplayText(String text) {
        if (packetTextDisplayArmorStandEntity == null) return;
        packetTextDisplayArmorStandEntity.setText(text);
        // Text changes ride the per-tick metadata packet; force it out next tick
        // even if the bone didn't move (see sendUpdatePacket dirty-checking).
        markDirty();
    }

    public void setTextDisplayVisible(boolean visible) {
        if (packetTextDisplayArmorStandEntity == null) return;
        packetTextDisplayArmorStandEntity.setTextVisible(visible);
        markDirty();
    }

    public void transform() {
        updateLocalTransform();
        updateGlobalTransform();
    }

    public void updateGlobalTransform() {
        if (parent != null) {
            TransformationMatrix.multiplyMatrices(parent.getBoneTransforms().globalMatrix, localMatrix, globalMatrix);
            if (bone.getBoneBlueprint().isHead()) {
                // Store the inherited scale before resetting
                double[] inheritedScale = globalMatrix.getScale();

                globalMatrix.resetRotation();
                float yaw = -bone.getSkeleton().getCurrentHeadYaw() + 180;
                globalMatrix.rotateY((float) Math.toRadians(yaw));
                globalMatrix.rotateX(-(float) Math.toRadians(bone.getSkeleton().getCurrentHeadPitch()));

                // Reapply the inherited scale
                globalMatrix.scale(inheritedScale[0], inheritedScale[1], inheritedScale[2]);
            }
        } else {
            globalMatrix = localMatrix;
        }
    }

    public void updateLocalTransform() {
        localMatrix.resetToIdentityMatrix();
        shiftPivotPoint();
        translateModelCenter();
        translateAnimation();
        rotateDefaultBoneRotation();
        rotateAnimation();
        scaleAnimation();
        shiftPivotPointBack();
        rotateByEntityYaw();
    }

    private void scaleAnimation() {
        Vector3f currentScale = getDisplayEntityScale();
        localMatrix.scale(currentScale.x / 2.5f, currentScale.y / 2.5f, currentScale.z / 2.5f);
    }

    //Shift to model center
    private void translateModelCenter() {
        localMatrix.translateLocal(bone.getBoneBlueprint().getModelCenter());

        //The bone is relative to its parent, so remove the offset of the parent
        if (parent != null) {
            Vector3f modelCenter = parent.getBoneBlueprint().getModelCenter();
            modelCenter.mul(-1);
            localMatrix.translateLocal(modelCenter);
        }
    }

    private void shiftPivotPoint() {
        localMatrix.translateLocal(bone.getBoneBlueprint().getBlueprintModelPivot().mul(-1));
    }

    private void translateAnimation() {
        localMatrix.translateLocal(
                -bone.getAnimationTranslation().get(0),
                bone.getAnimationTranslation().get(1),
                bone.getAnimationTranslation().get(2));
    }

    private void rotateAnimation() {
        // Use IK rotation if available, otherwise use animation rotation
        org.joml.Vector3f effectiveRotation = bone.getEffectiveRotation();
        animationRotationVector.setX(effectiveRotation.get(0));
        animationRotationVector.setY(-effectiveRotation.get(1));
        animationRotationVector.setZ(-effectiveRotation.get(2));
        animationRotationVector.rotateAroundY(Math.PI);
        localMatrix.rotateAnimation(
                (float) animationRotationVector.getX(),
                (float) animationRotationVector.getY(),
                (float) animationRotationVector.getZ());
    }

    private void rotateDefaultBoneRotation() {
        localMatrix.rotateLocal(
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(0),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(1),
                bone.getBoneBlueprint().getBlueprintOriginalBoneRotation().get(2));
    }

    private void shiftPivotPointBack() {
        //Remove the pivot point, go back to the model center
        localMatrix.translateLocal(bone.getBoneBlueprint().getBlueprintModelPivot());
    }

    public void generateDisplay() {
        transform();
        if (bone.getBoneBlueprint().isMountPoint()) {
            // Mount-point bones need a packet entity for position tracking
            // (so getBoneLocation() works and tick updates follow animations)
            // but no visible model since the bone is empty.
            initializeMountPointBone();
            return;
        }
        if (bone.getBoneBlueprint().isDisplayModel()) {
            if (bone.getBoneBlueprint().isNameTag()) {
                initializeTextDisplayBone();
                return;
            }
            initializeDisplayEntityBone();
            initializeArmorStandBone();
        }
    }

    private void initializeTextDisplayBone() {
        Location textDisplayLocation = getArmorStandTargetLocation();
        packetTextDisplayArmorStandEntity = NMSManager.getAdapter().createPacketTextArmorStandEntity(textDisplayLocation);
        packetTextDisplayArmorStandEntity.initializeText(textDisplayLocation);
        packetTextDisplayArmorStandEntity.sendLocationAndRotationPacket(textDisplayLocation, new EulerAngle(0, 0, 0));
    }

    private void initializeMountPointBone() {
        // Create an invisible packet armor stand just for position tracking.
        // No model is set — this entity is never shown to players, it only
        // provides a location that follows the skeleton's animation transforms.
        Location loc = getMountPointTargetLocation();
        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(loc);
        packetArmorStandEntity.sendLocationAndRotationPacket(loc, new EulerAngle(0, 0, 0));
    }

    private void initializeDisplayEntityBone() {
        if (!DefaultConfig.useDisplayEntitiesWhenPossible) return;
        Location displayEntityLocation = getDisplayEntityTargetLocation();
        packetDisplayEntity = NMSManager.getAdapter().createPacketDisplayEntity(displayEntityLocation);
        if (VersionChecker.serverVersionOlderThan(21, 4))
            packetDisplayEntity.initializeModel(displayEntityLocation, Integer.parseInt(bone.getBoneBlueprint().getModelID()));
        else {
            packetDisplayEntity.initializeModel(displayEntityLocation, bone.getBoneBlueprint().getModelID());
        }
        packetDisplayEntity.sendLocationAndRotationPacket(displayEntityLocation, getDisplayEntityRotation());
    }

    private void initializeArmorStandBone() {
        //todo: add way to disable armor stands later via config
        Location armorStandLocation = getArmorStandTargetLocation();
        packetArmorStandEntity = NMSManager.getAdapter().createPacketArmorStandEntity(armorStandLocation);
        if (VersionChecker.serverVersionOlderThan(21, 4))
            packetArmorStandEntity.initializeModel(armorStandLocation, Integer.parseInt(bone.getBoneBlueprint().getModelID()));
        else
            packetArmorStandEntity.initializeModel(armorStandLocation, bone.getBoneBlueprint().getModelID());

        packetArmorStandEntity.sendLocationAndRotationPacket(armorStandLocation, getArmorStandEntityRotation());
    }

    private void rotateByEntityYaw() {
        //rotate by yaw amount
        if (parent == null) {
            localMatrix.rotateLocal(0, (float) -Math.toRadians(bone.getSkeleton().getCurrentLocation().getYaw() + 180), 0);
        }
    }

    /**
     * Returns the bone's world position derived purely from the global
     * transform matrix, with no armor-stand pivot offset. Used for mount
     * points where the raw pivot position is needed.
     */
    protected Location getMountPointTargetLocation() {
        double[] translatedGlobalMatrix = globalMatrix.getTranslation();
        return new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                translatedGlobalMatrix[0],
                translatedGlobalMatrix[1],
                translatedGlobalMatrix[2])
                .add(bone.getSkeleton().getCurrentLocation());
    }

    protected Location getArmorStandTargetLocation() {
        double[] translatedGlobalMatrix = globalMatrix.getTranslation();
        Location armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                translatedGlobalMatrix[0],
                translatedGlobalMatrix[1],
                translatedGlobalMatrix[2])
                .add(bone.getSkeleton().getCurrentLocation());
        armorStandLocation.setYaw(180);
        armorStandLocation.subtract(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0);
        return armorStandLocation;
    }

    protected Location getDisplayEntityTargetLocation() {
        double[] translatedGlobalMatrix = globalMatrix.getTranslation();
        Location armorStandLocation;
        if (!VersionChecker.serverVersionOlderThan(20, 0)) {
            armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                    translatedGlobalMatrix[0],
                    translatedGlobalMatrix[1],
                    translatedGlobalMatrix[2])
                    .add(bone.getSkeleton().getCurrentLocation());
            armorStandLocation.setYaw(180);
        } else
            armorStandLocation = new Location(bone.getSkeleton().getCurrentLocation().getWorld(),
                    translatedGlobalMatrix[0],
                    translatedGlobalMatrix[1],
                    translatedGlobalMatrix[2])
                    .add(bone.getSkeleton().getCurrentLocation());
        return armorStandLocation;
    }

    protected EulerAngle getDisplayEntityRotation() {
        double[] rotation = globalMatrix.getRotation();
        if (VersionChecker.serverVersionOlderThan(20, 0))
            return new EulerAngle(rotation[0], rotation[1], rotation[2]);
        else {
            return new EulerAngle(-rotation[0], rotation[1], -rotation[2]);
        }
    }

    protected EulerAngle getArmorStandEntityRotation() {
        double[] rotation = globalMatrix.getRotation();
        return new EulerAngle(-rotation[0], -rotation[1], rotation[2]);
    }

    // ---- per-tick change detection ----------------------------------------
    // The model clock asks every visible bone to resend its move + metadata
    // packets every tick. Historically that happened unconditionally, so a
    // perfectly still NPC still blasted 2 packets per bone per viewer per tick.
    // We now cache the last-sent target transform per packet entity and skip the
    // resend when nothing moved. forceNextUpdate guarantees the first tick after
    // (re)display, and any non-transform change (tint, text) that still needs a
    // metadata packet, always goes out. See DefaultConfig.skipUnchangedBoneUpdates.
    private static final double TRANSFORM_EPSILON = 1.0E-4;
    // volatile: markDirty() is called from the main thread (damage flash, tint, text
    // changes) while sendUpdatePacket reads it on the async model-clock thread.
    private volatile boolean forceNextUpdate = true;
    private boolean armorStandStateValid = false;
    private double asLx, asLy, asLz, asRx, asRy, asRz;
    private boolean displayStateValid = false;
    private double deLx, deLy, deLz, deRx, deRy, deRz, deSx, deSy, deSz;
    private boolean textStateValid = false;
    private double txLx, txLy, txLz;

    /**
     * Forces this bone to resend its packets on the next tick regardless of whether
     * its transform changed. Call after any non-transform mutation that relies on the
     * per-tick metadata packet to reach clients (tint/leather-armor color, text).
     */
    public void markDirty() {
        forceNextUpdate = true;
    }

    public void sendUpdatePacket(AbstractPacketBundle packetBundle) {
        boolean force = forceNextUpdate || !DefaultConfig.skipUnchangedBoneUpdates;
        forceNextUpdate = false;

        if (packetArmorStandEntity != null && packetArmorStandEntity.hasViewers()) {
            Location loc = getArmorStandTargetLocation();
            EulerAngle rot = getArmorStandEntityRotation();
            if (force || armorStandChanged(loc, rot)) {
                if (packetBundle == null) packetBundle = packetArmorStandEntity.createPacketBundle();
                sendArmorStandUpdatePacket(packetBundle, loc, rot);
            }
        }
        if (packetDisplayEntity != null && packetDisplayEntity.hasViewers()) {
            Location loc = getDisplayEntityTargetLocation();
            EulerAngle rot = getDisplayEntityRotation();
            double[] scale = globalMatrix.getScale();
            if (force || displayChanged(loc, rot, scale)) {
                if (packetBundle == null) packetBundle = packetDisplayEntity.createPacketBundle();
                sendDisplayEntityUpdatePacket(packetBundle, loc, rot, scale);
            }
        }
        if (packetTextDisplayArmorStandEntity != null && packetTextDisplayArmorStandEntity.hasViewers()) {
            Location loc = getArmorStandTargetLocation();
            if (force || textChanged(loc)) {
                if (packetBundle == null) packetBundle = packetTextDisplayArmorStandEntity.createPacketBundle();
                sendTextDisplayUpdatePacket(packetBundle, loc);
            }
        }
    }

    private boolean armorStandChanged(Location loc, EulerAngle rot) {
        if (!armorStandStateValid) return true;
        return !same(asLx, loc.getX()) || !same(asLy, loc.getY()) || !same(asLz, loc.getZ())
                || !same(asRx, rot.getX()) || !same(asRy, rot.getY()) || !same(asRz, rot.getZ());
    }

    private boolean displayChanged(Location loc, EulerAngle rot, double[] scale) {
        if (!displayStateValid) return true;
        return !same(deLx, loc.getX()) || !same(deLy, loc.getY()) || !same(deLz, loc.getZ())
                || !same(deRx, rot.getX()) || !same(deRy, rot.getY()) || !same(deRz, rot.getZ())
                || !same(deSx, scale[0]) || !same(deSy, scale[1]) || !same(deSz, scale[2]);
    }

    private boolean textChanged(Location loc) {
        if (!textStateValid) return true;
        return !same(txLx, loc.getX()) || !same(txLy, loc.getY()) || !same(txLz, loc.getZ());
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < TRANSFORM_EPSILON;
    }

    private void sendTextDisplayUpdatePacket(AbstractPacketBundle packetBundle, Location loc) {
        packetTextDisplayArmorStandEntity.generateLocationAndRotationAndScalePackets(
                packetBundle,
                loc,
                new EulerAngle(0, 0, 0),
                1f);
        txLx = loc.getX();
        txLy = loc.getY();
        txLz = loc.getZ();
        textStateValid = true;
    }

    private void sendArmorStandUpdatePacket(AbstractPacketBundle packetBundle, Location loc, EulerAngle rot) {
        if (packetArmorStandEntity != null) {
            packetArmorStandEntity.generateLocationAndRotationAndScalePackets(
                    packetBundle,
                    loc,
                    rot,
                    1f);
            asLx = loc.getX();
            asLy = loc.getY();
            asLz = loc.getZ();
            asRx = rot.getX();
            asRy = rot.getY();
            asRz = rot.getZ();
            armorStandStateValid = true;
        }
    }

    private void sendDisplayEntityUpdatePacket(AbstractPacketBundle packetBundle, Location loc, EulerAngle rot, double[] scale) {
        if (packetDisplayEntity != null) {
            packetDisplayEntity.generateLocationAndRotationAndScalePackets(
                    packetBundle,
                    loc,
                    rot,
                    (float) scale[0] * 2.5f,
                    (float) scale[1] * 2.5f,
                    (float) scale[2] * 2.5f);
            deLx = loc.getX();
            deLy = loc.getY();
            deLz = loc.getZ();
            deRx = rot.getX();
            deRy = rot.getY();
            deRz = rot.getZ();
            deSx = scale[0];
            deSy = scale[1];
            deSz = scale[2];
            displayStateValid = true;
        }
    }

    protected Vector3f getDisplayEntityScale() {
        Vector3f animScale = bone.getAnimationScale();
        float scaleX = animScale.x == -1 ? 2.5f : animScale.x * 2.5f;
        float scaleY = animScale.y == -1 ? 2.5f : animScale.y * 2.5f;
        float scaleZ = animScale.z == -1 ? 2.5f : animScale.z * 2.5f;
        //Only the root bone/head should be scaling up globally like this, otherwise the scale will be inherited by each bone and then become progressively larger or smaller
        if (bone.getParent() == null) {
            double scaleModifier = bone.getSkeleton().getModeledEntity().getScaleModifier();
            if (bone.getSkeleton().getModeledEntity().getUnderlyingEntity() != null && bone.getSkeleton().getModeledEntity() instanceof LivingEntity livingEntity && livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")) != null)
                scaleModifier *= livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")).getValue();
            scaleX *= (float) scaleModifier;
            scaleY *= (float) scaleModifier;
            scaleZ *= (float) scaleModifier;
        }
        return new Vector3f(scaleX, scaleY, scaleZ);
    }

}