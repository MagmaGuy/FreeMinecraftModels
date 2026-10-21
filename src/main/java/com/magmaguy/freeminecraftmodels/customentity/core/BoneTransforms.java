package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.utils.TransformationMatrix;
import com.magmaguy.magmacore.util.VersionChecker;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;
import org.joml.Vector3f;

public class BoneTransforms {

    private static final float MAX_HEAD_TWIST = 75;
    private final Bone parent;
    private final Bone bone;
    private final TransformationMatrix localMatrix = new TransformationMatrix();
    // Reused Vector for rotateAnimation()'s local math — never escapes the method.
    private final Vector animationRotationVector = new Vector();
    // Per-bone scratch storage for the sequential transform/update path. Packets receive scalars only.
    private final double[] scaleBuffer = new double[3];
    private final double[] positionBuffer = new double[3];
    private final double[] rotationBuffer = new double[3];
    private TransformationMatrix globalMatrix = new TransformationMatrix();
    @Getter
    private PacketModelEntity packetArmorStandEntity = null;
    @Getter
    private PacketModelEntity packetDisplayEntity = null;
    public BoneTransforms(Bone bone, Bone parent) {
        this.bone = bone;
        this.parent = parent;
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
                globalMatrix.getScale(scaleBuffer);

                globalMatrix.resetRotation();
                Location gaze = bone.getSkeleton().hasLookTarget()
                        ? bone.getSkeleton().getLookDirection(getMountPointTargetLocation()) : null;
                float headYaw = gaze == null ? bone.getSkeleton().getCurrentHeadYaw() : gaze.getYaw();
                // The head leads a slow-turning body but stays within ±75° of it, so the neck never twists around.
                float bodyYaw = bone.getSkeleton().getCurrentLocation().getYaw();
                float twist = (float) TurnInertia.wrap(headYaw - bodyYaw);
                headYaw = bodyYaw + Math.max(-MAX_HEAD_TWIST, Math.min(MAX_HEAD_TWIST, twist));
                float headPitch = gaze == null ? bone.getSkeleton().getCurrentHeadPitch() : gaze.getPitch();
                float yaw = -headYaw + 180;
                globalMatrix.rotateY((float) Math.toRadians(yaw));
                globalMatrix.rotateX(-(float) Math.toRadians(headPitch));

                // Reapply the inherited scale
                globalMatrix.scale(scaleBuffer[0], scaleBuffer[1], scaleBuffer[2]);
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
                return;
            }
            initializeDisplayEntityBone();
            initializeArmorStandBone();
        }
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
     * World offset, relative to the skeleton location, of a model-space point (blocks, Blockbench
     * axes) that belongs to this bone. Uses the same chain as mount points.
     */
    public void globalPoint(Vector3f modelSpacePoint, double[] result) {
        Vector3f center = bone.getBoneBlueprint().getModelCenter();
        globalMatrix.transformPoint(modelSpacePoint.x - center.x, modelSpacePoint.y - center.y,
                modelSpacePoint.z - center.z, result);
    }

    /** The bone's world rotation with scale removed. */
    public org.joml.Quaterniond globalRotation(org.joml.Quaterniond result) {
        return globalMatrix.getRotation(result);
    }

    /**
     * Returns the bone's world position derived purely from the global
     * transform matrix, with no armor-stand pivot offset. Used for mount
     * points where the raw pivot position is needed.
     */
    protected Location getMountPointTargetLocation() {
        // Hoisted: getCurrentLocation() rebuilds a Location per call (NMS
        // body-rotation lookup + clone for dynamic entities) — snapshot once.
        Location currentLocation = bone.getSkeleton().getCurrentLocation();
        double[] translatedGlobalMatrix = globalMatrix.getTranslation();
        return new Location(currentLocation.getWorld(),
                translatedGlobalMatrix[0],
                translatedGlobalMatrix[1],
                translatedGlobalMatrix[2])
                .add(currentLocation);
    }

    protected Location getArmorStandTargetLocation() {
        Location currentLocation = bone.getSkeleton().getCurrentLocation();
        double[] position = new double[3];
        readTargetPosition(currentLocation, position, true);
        return createTargetLocation(currentLocation, position, true);
    }

    protected Location getDisplayEntityTargetLocation() {
        Location currentLocation = bone.getSkeleton().getCurrentLocation();
        double[] position = new double[3];
        readTargetPosition(currentLocation, position, false);
        return createTargetLocation(currentLocation, position, false);
    }

    protected EulerAngle getDisplayEntityRotation() {
        double[] rotation = new double[3];
        readTargetRotation(rotation, false);
        return new EulerAngle(rotation[0], rotation[1], rotation[2]);
    }

    protected EulerAngle getArmorStandEntityRotation() {
        double[] rotation = new double[3];
        readTargetRotation(rotation, true);
        return new EulerAngle(rotation[0], rotation[1], rotation[2]);
    }

    private void readTargetPosition(Location origin, double[] position, boolean armorStand) {
        globalMatrix.getTranslation(position);
        position[0] += origin.getX();
        position[1] += origin.getY();
        position[2] += origin.getZ();
        if (armorStand) position[1] -= BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT();
    }

    private Location createTargetLocation(Location origin, double[] position, boolean armorStand) {
        float yaw = armorStand || !VersionChecker.serverVersionOlderThan(20, 0) ? 180 : 0;
        return new Location(origin.getWorld(), position[0], position[1], position[2], yaw, 0);
    }

    private void readTargetRotation(double[] rotation, boolean armorStand) {
        globalMatrix.getRotation(rotation);
        if (armorStand) {
            rotation[0] = -rotation[0];
            rotation[1] = -rotation[1];
        } else if (!VersionChecker.serverVersionOlderThan(20, 0)) {
            rotation[0] = -rotation[0];
            rotation[2] = -rotation[2];
        }
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
            Location origin = bone.getSkeleton().getCurrentLocation();
            readTargetPosition(origin, positionBuffer, true);
            readTargetRotation(rotationBuffer, true);
            if (force || armorStandChanged(positionBuffer, rotationBuffer)) {
                if (packetBundle == null) packetBundle = packetArmorStandEntity.createPacketBundle();
                sendArmorStandUpdatePacket(packetBundle, createTargetLocation(origin, positionBuffer, true),
                        new EulerAngle(rotationBuffer[0], rotationBuffer[1], rotationBuffer[2]));
            }
        }
        if (packetDisplayEntity != null && packetDisplayEntity.hasViewers()) {
            if (isRidden()) {
                readRiddenPose();
                if (force || riddenChanged()) {
                    if (packetBundle == null) packetBundle = packetDisplayEntity.createPacketBundle();
                    sendRiddenPosePacket(packetBundle);
                }
                return;
            }
            Location origin = bone.getSkeleton().getCurrentLocation();
            readTargetPosition(origin, positionBuffer, false);
            readTargetRotation(rotationBuffer, false);
            globalMatrix.getScale(scaleBuffer);
            if (force || displayChanged(positionBuffer, rotationBuffer, scaleBuffer)) {
                if (packetBundle == null) packetBundle = packetDisplayEntity.createPacketBundle();
                sendDisplayEntityUpdatePacket(packetBundle, createTargetLocation(origin, positionBuffer, false),
                        new EulerAngle(rotationBuffer[0], rotationBuffer[1], rotationBuffer[2]), scaleBuffer);
            }
        }

    }

    /** Refresh packet-entity state once at admission, even when no viewer existed. */
    void prepareDisplayPose(AbstractPacketBundle packetBundle) {
        Location origin = bone.getSkeleton().getCurrentLocation();
        if (packetArmorStandEntity != null) {
            readTargetPosition(origin, positionBuffer, true);
            readTargetRotation(rotationBuffer, true);
            sendArmorStandUpdatePacket(packetBundle, createTargetLocation(origin, positionBuffer, true),
                    new EulerAngle(rotationBuffer[0], rotationBuffer[1], rotationBuffer[2]));
        }
        if (packetDisplayEntity != null) {
            if (isRidden()) {
                readRiddenPose();
                sendRiddenPosePacket(packetBundle);
                return;
            }
            readTargetPosition(origin, positionBuffer, false);
            readTargetRotation(rotationBuffer, false);
            globalMatrix.getScale(scaleBuffer);
            sendDisplayEntityUpdatePacket(packetBundle, createTargetLocation(origin, positionBuffer, false),
                    new EulerAngle(rotationBuffer[0], rotationBuffer[1], rotationBuffer[2]), scaleBuffer);
        }
    }

    // ---- ridden placement ----------------------------------------------------
    // The display rides the entity as a packet passenger (Skeleton.setRiddenPlacement), so viewers put it at
    // the seat every tick and only its transformation changes. The entity position never enters the pose.
    private volatile int riddenVehicleId = -1;
    private final double[] seatBuffer = new double[3];
    private final Vector3f translationBuffer = new Vector3f();
    // Reused every pass: the packet layer reads the seat at once and keeps nothing of it.
    private final Location seatLocation = new Location(null, 0, 0, 0, 180, 0);
    private boolean riddenStateValid = false;
    private double rdTx, rdTy, rdTz, rdRx, rdRy, rdRz, rdSx, rdSy, rdSz;

    private boolean isRidden() {
        return riddenVehicleId != -1;
    }

    /**
     * Mounts the display on the vehicle, or frees it with -1; either way the next pass resends the full pose.
     * Returns the mount packet step for the skeleton to run on the main thread, or null when nothing changes.
     */
    Runnable setRiddenVehicle(int vehicleId) {
        if (packetDisplayEntity == null || !packetDisplayEntity.supportsRiddenPlacement()) return null;
        if (riddenVehicleId == vehicleId) return null;
        riddenVehicleId = vehicleId;
        // The free pose carries no translation and the ridden pose no position, so neither cache applies.
        displayStateValid = false;
        riddenStateValid = false;
        forceNextUpdate = true;
        PacketModelEntity display = packetDisplayEntity;
        return vehicleId == -1 ? display::dismount : () -> display.mountTo(vehicleId);
    }

    /**
     * Pose relative to the seat. Viewers rotate a fixed display by its own yaw before applying the
     * transformation, and bones face yaw 180 (see createTargetLocation), so x and z flip.
     */
    private void readRiddenPose() {
        globalMatrix.getTranslation(positionBuffer);
        bone.getSkeleton().getSeatOffset(seatBuffer);
        translationBuffer.set(
                (float) -(positionBuffer[0] - seatBuffer[0]),
                (float) (positionBuffer[1] - seatBuffer[1]),
                (float) -(positionBuffer[2] - seatBuffer[2]));
        readTargetRotation(rotationBuffer, false);
        globalMatrix.getScale(scaleBuffer);
    }

    private boolean riddenChanged() {
        if (!riddenStateValid) return true;
        return !same(rdTx, translationBuffer.x) || !same(rdTy, translationBuffer.y) || !same(rdTz, translationBuffer.z)
                || !same(rdRx, rotationBuffer[0]) || !same(rdRy, rotationBuffer[1]) || !same(rdRz, rotationBuffer[2])
                || !same(rdSx, scaleBuffer[0]) || !same(rdSy, scaleBuffer[1]) || !same(rdSz, scaleBuffer[2]);
    }

    private void sendRiddenPosePacket(AbstractPacketBundle packetBundle) {
        Location origin = bone.getSkeleton().getCurrentLocation();
        seatLocation.setWorld(origin.getWorld());
        seatLocation.setX(origin.getX() + seatBuffer[0]);
        seatLocation.setY(origin.getY() + seatBuffer[1]);
        seatLocation.setZ(origin.getZ() + seatBuffer[2]);
        // Synched entity data keeps the vector it is given, so hand over a copy the next pass cannot change.
        packetDisplayEntity.generateRiddenTransformationPackets(packetBundle, seatLocation, new Vector3f(translationBuffer),
                new EulerAngle(rotationBuffer[0], rotationBuffer[1], rotationBuffer[2]),
                (float) scaleBuffer[0] * 2.5f, (float) scaleBuffer[1] * 2.5f, (float) scaleBuffer[2] * 2.5f,
                Math.max(0, DefaultConfig.riddenBoneInterpolationTicks));
        rdTx = translationBuffer.x;
        rdTy = translationBuffer.y;
        rdTz = translationBuffer.z;
        rdRx = rotationBuffer[0];
        rdRy = rotationBuffer[1];
        rdRz = rotationBuffer[2];
        rdSx = scaleBuffer[0];
        rdSy = scaleBuffer[1];
        rdSz = scaleBuffer[2];
        riddenStateValid = true;
    }

    private boolean armorStandChanged(double[] position, double[] rotation) {
        if (!armorStandStateValid) return true;
        return !same(asLx, position[0]) || !same(asLy, position[1]) || !same(asLz, position[2])
                || !same(asRx, rotation[0]) || !same(asRy, rotation[1]) || !same(asRz, rotation[2]);
    }

    private boolean displayChanged(double[] position, double[] rotation, double[] scale) {
        if (!displayStateValid) return true;
        return !same(deLx, position[0]) || !same(deLy, position[1]) || !same(deLz, position[2])
                || !same(deRx, rotation[0]) || !same(deRy, rotation[1]) || !same(deRz, rotation[2])
                || !same(deSx, scale[0]) || !same(deSy, scale[1]) || !same(deSz, scale[2]);
    }

    private static boolean same(double a, double b) {
        return Math.abs(a - b) < TRANSFORM_EPSILON;
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
            // scaleModifier already tracks the underlying LivingEntity's generic_scale
            // attribute (ModeledEntity.tick refreshes it every tick). A ModeledEntity is
            // never itself a Bukkit LivingEntity, so no extra attribute lookup is needed.
            double scaleModifier = bone.getSkeleton().getModeledEntity().getScaleModifier();
            scaleX *= (float) scaleModifier;
            scaleY *= (float) scaleModifier;
            scaleZ *= (float) scaleModifier;
        }
        return new Vector3f(scaleX, scaleY, scaleZ);
    }

}
