package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.IKChainBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.LocatorBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

public class Skeleton {

    //In BlockBench models are referred to by name for animations, and names are unique
    @Getter
    private final HashMap<String, Bone> boneMap = new HashMap<>();
    @Getter
    private final SkeletonBlueprint skeletonBlueprint;
    @Getter
    private final SkeletonWatchers skeletonWatchers;
    @Getter
    private final List<Bone> mountPointBones = new ArrayList<>();
    // IK chains for inverse kinematics animation
    @Getter
    private final List<IKChain> ikChains = new ArrayList<>();
    // Map of controller names to IK chains for quick lookup
    @Getter
    private final HashMap<String, IKChain> ikChainMap = new HashMap<>();
    @Getter
    @Setter
    private float currentHeadPitch = 0;
    @Getter
    @Setter
    private float currentHeadYaw = 0;
    @Getter
    @Setter
    private ModeledEntity modeledEntity = null;
    private Bone rootBone = null;
    // Published from the main thread and consumed by the asynchronous model clock.
    // Never mutate or expose the stored Location after publication.
    private volatile Location lookTarget;

    /** Overrides model gaze. Pass null to resume the carrier's normal head rotation. */
    public void setLookTarget(Location target) {
        if (target != null) {
            target.checkFinite();
            if (target.getWorld() == null) throw new IllegalArgumentException("Look target must have a world");
        }
        lookTarget = target == null ? null : target.clone();
    }

    /** Resolves gaze from an actual bone position, including the model's scale and animation. */
    public Location getLookDirection(Location origin) {
        Location target = lookTarget;
        if (target == null || origin == null || origin.getWorld() != target.getWorld()) return null;
        var direction = target.toVector().subtract(origin.toVector());
        if (direction.lengthSquared() < 1.0E-8D) return null;
        return origin.clone().setDirection(direction);
    }

    public boolean hasLookTarget() {
        return lookTarget != null;
    }

    public Skeleton(SkeletonBlueprint skeletonBlueprint, ModeledEntity modeledEntity) {
        this.skeletonBlueprint = skeletonBlueprint;
        this.modeledEntity = modeledEntity;
        skeletonBlueprint.getBoneMap().forEach((key, value) -> {
            if (value.getParent() == null) {
                Bone bone = new Bone(value, null, this);
                boneMap.put(key, bone);
                bone.getAllChildren(boneMap);
                rootBone = bone;
            }
        });
        skeletonWatchers = new SkeletonWatchers(this);

        // Create IK chain instances from blueprints
        for (IKChainBlueprint chainBlueprint : skeletonBlueprint.getIkChains()) {
            IKChain chain = new IKChain(chainBlueprint, this);
            ikChains.add(chain);
            String controllerName = chain.getControllerName();
            ikChainMap.put(controllerName, chain);
        }

        // Collect mount point bones
        for (Bone bone : boneMap.values()) {
            if (bone.getBoneBlueprint().isMountPoint()) mountPointBones.add(bone);
        }
    }

    @Nullable
    public Location getCurrentLocation() {
        return modeledEntity.getBoneTransformLocation();
    }

    // The model's location for one transform pass, read once so its bones neither rebuild it each nor see it move
    // halfway through. Only the thread running the pass, under this skeleton's lock, sets and reads it.
    private Location passLocation;

    /** The location of the transform pass running now, or the current location outside one. */
    @Nullable
    Location passLocation() {
        Location location = passLocation;
        return location != null ? location : getCurrentLocation();
    }

    // Ridden placement: the display bones ride the underlying entity as packet passengers and send only their
    // pose relative to its seat, so viewers place them from the same smoothed entity position as its riders.
    // The model tick publishes this before the bones transform.
    // The seat is swapped as a whole and only when it changes, so a reader on another thread never sees a
    // half-written offset.
    private volatile int riddenVehicleId = -1;
    private volatile double[] seatOffset = new double[3];
    // While only idle or walk plays and a busy player watches the model, ridden poses go out once every
    // riddenBoneUpdateIntervalTicks; attacks, other animations and models no busy player watches keep every tick.
    // Each model starts at a random phase, so a crowd's poses spread over the interval instead of all landing in one
    // client frame.
    private int riddenPoseCounter = java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 16);
    // Set by ViewerLoad on the primary thread every few ticks, read by the model clock.
    private volatile boolean watchedByBusyPlayer = false;
    // The interval of the clock pass running now, read once so a busy flag flipping halfway through cannot split one
    // model's bones between two intervals; 0 outside a pass.
    private int passPoseInterval = 0;
    // Bone updates the running pass sent, and those of the last pass that sent poses, which ViewerLoad sums per player.
    // A ridden pass covers the whole interval and changes about as many bones as one tick would, so the count stays
    // the same whether or not the model is slowed down.
    private int passBoneUpdates = 0;
    private volatile int poseBoneUpdates = 0;

    /** Ticks between this model's ridden poses right now; see DefaultConfig.riddenBoneUpdateIntervalTicks. */
    int riddenPoseInterval() {
        int interval = passPoseInterval;
        return interval > 0 ? interval : currentRiddenPoseInterval();
    }

    private int currentRiddenPoseInterval() {
        int interval = DefaultConfig.riddenBoneUpdateIntervalTicks;
        if (interval <= 1 || !watchedByBusyPlayer
                || !modeledEntity.getAnimationComponent().isOnlyLocomotionPlaying()) return 1;
        return interval;
    }

    /** Counts one bone pose sent to this model's Java viewers in the running clock pass. */
    void countBoneUpdate() {
        passBoneUpdates++;
    }

    /** Bone updates per tick this model sends its Java viewers, from its last pass that sent poses. */
    public int getPoseBoneUpdates() {
        return poseBoneUpdates;
    }

    /** Whether a busy player watches this model, so its idle and walk poses may slow down; see ViewerLoad. */
    public void setWatchedByBusyPlayer(boolean watchedByBusyPlayer) {
        this.watchedByBusyPlayer = watchedByBusyPlayer;
    }

    /** Whether ridden bones send their pose this tick. */
    boolean isRiddenPoseTick(int interval) {
        return interval <= 1 || riddenPoseCounter % interval == 0;
    }

    public boolean isRidden() {
        return riddenVehicleId != -1;
    }

    /** World-axis offset from the entity position to the seat viewers put the bones at. */
    void getSeatOffset(double[] result) {
        double[] seat = seatOffset;
        result[0] = seat[0];
        result[1] = seat[1];
        result[2] = seat[2];
    }

    /** Pass -1 to place the bones freely again; the seat is relative to the entity position. */
    public void setRiddenPlacement(int vehicleId, org.bukkit.util.Vector seat) {
        if (vehicleId != -1) {
            double[] current = seatOffset;
            if (current[0] != seat.getX() || current[1] != seat.getY() || current[2] != seat.getZ())
                seatOffset = new double[]{seat.getX(), seat.getY(), seat.getZ()};
        }
        if (riddenVehicleId == vehicleId) return;
        riddenVehicleId = vehicleId;
        // Mount packets list the vehicle's real passengers, which only the main thread may look up; the model
        // clock is asynchronous, so every bone mounts in one task on the next tick while its ridden pose starts now.
        List<Consumer<AbstractPacketBundle>> mounts = new ArrayList<>();
        for (Bone bone : boneMap.values()) {
            Consumer<AbstractPacketBundle> mount = bone.getBoneTransforms().setRiddenVehicle(vehicleId);
            if (mount != null) mounts.add(mount);
        }
        if (mounts.isEmpty()) return;
        // Every bone's passenger list re-seats the bones before it, and each re-seat turns them back with a rotation
        // per bone. Sent one by one, a model's bones cost each viewer about half the bone count squared rotations; in
        // one bundle only the vehicle's last list turns them back, once each.
        Runnable mountAll = () -> {
            AbstractPacketBundle bundle = createMountBundle();
            mounts.forEach(mount -> mount.accept(bundle));
            if (bundle != null) bundle.send();
        };
        if (Bukkit.isPrimaryThread() || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) mountAll.run();
        else Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, mountAll);
    }

    private static AbstractPacketBundle createMountBundle() {
        com.magmaguy.easyminecraftgoals.NMSAdapter adapter = com.magmaguy.easyminecraftgoals.NMSManager.getAdapter();
        if (!com.magmaguy.easyminecraftgoals.NMSManager.isEnabled() || adapter == null) return null;
        return adapter.createPacketBundle();
    }

    /**
     * Gives the bones the animation pose the model skipped while nobody watched it. The model clock samples under
     * this skeleton's lock, so the sample here takes it too.
     */
    public void freshenPose() {
        if (modeledEntity == null || !modeledEntity.getAnimationComponent().isPoseStale()) return;
        synchronized (this) {
            modeledEntity.getAnimationComponent().refreshPose();
        }
    }

    /** Recomputes bone transforms. Packets are only sent to existing viewers. */
    void transformBones() {
        freshenPose();
        if (rootBone == null) return;
        passLocation = getCurrentLocation();
        try {
            rootBone.transform(null);
        } finally {
            passLocation = null;
        }
    }

    /**
     * Writes a locator's world position and rotation from the current bone transforms. A null
     * locator, or one whose bone is missing, resolves to the model origin and root rotation.
     */
    public void locatorTransform(LocatorBlueprint locator, Vector3d position, Quaterniond rotation) {
        Location origin = getCurrentLocation();
        position.set(origin.getX(), origin.getY(), origin.getZ());
        Bone bone = null;
        if (locator != null && locator.getParentBone() != null) {
            for (Bone candidate : boneMap.values()) {
                if (candidate.getBoneBlueprint() == locator.getParentBone()) {
                    bone = candidate;
                    break;
                }
            }
        }
        if (bone == null) {
            if (rootBone != null) rootBone.getBoneTransforms().globalRotation(rotation);
            else rotation.identity();
            return;
        }
        double[] offset = new double[3];
        bone.getBoneTransforms().globalPoint(locator.getModelSpacePosition(), offset);
        position.add(offset[0], offset[1], offset[2]);
        bone.getBoneTransforms().globalRotation(rotation);
        Vector3f locatorRotation = locator.getRotation();
        rotation.mul(new Quaterniond().rotationZYX(locatorRotation.z, locatorRotation.y, locatorRotation.x));
    }

    public void generateDisplays() {
        rootBone.generateDisplay();
        // A light override set before the displays existed reaches them now.
        if (modeledEntity != null && modeledEntity.hasBrightnessOverride())
            applyBrightnessOverride(modeledEntity.getBrightnessOverrideBlockLight(),
                    modeledEntity.getBrightnessOverrideSkyLight());
    }

    /** Fixed light for every display bone, 0 to 15 each; a negative block light drops the override. */
    public void applyBrightnessOverride(int blockLight, int skyLight) {
        for (Bone bone : boneMap.values()) bone.setBrightnessOverride(blockLight, skyLight);
    }

    public void remove() {
        skeletonWatchers.releaseTrackedBodies();
        skeletonWatchers.restoreBodyTracking();
        if (rootBone != null) rootBone.remove();
    }

    public List<Bone> getNametags() {
        List<Bone> nametags = new ArrayList<>();
        for (Bone value : boneMap.values()) {
            if (value.getBoneBlueprint().isNameTag())
                nametags.add(value);
        }
        return nametags;
    }

    /**
     * Returns the map of bones the Skeleton has
     *
     * @return
     */
    public Collection<Bone> getBones() {
        return boneMap.values();
    }

    private boolean tinting = false;
    private int tintCounter = 0;
    private Color persistentTint = null;

    /**
     * This updates animations. The plugin runs this automatically, don't use it unless you know what you're doing!
     */
    public void tick(AbstractPacketBundle abstractPacketBundle) {
        // handle tint animation
        if (tinting) {
            tintCounter++;

            if (tintCounter <= 10) {
                // ramp from red back toward white
                int gAndB = (int) (255 / (double) tintCounter);
                Color tint = Color.fromRGB(255, gAndB, gAndB);
                boneMap.values().forEach(b -> b.setHorseLeatherArmorColor(tint));
            } else {
                // after frame 10, either keep poofing (if dying) or finish
                if (!modeledEntity.isDying()) {
                    // done — fade back to persistent tint when set, else undyed
                    tinting = false;
                    Color restore = persistentTint != null ? persistentTint : Color.WHITE;
                    boneMap.values().forEach(b -> b.setHorseLeatherArmorColor(restore));
                } else if (modeledEntity.isRemoved()) {
                    // entity gone, cancel
                    tinting = false;
                } else {
                    // still dying: emit poofs every 5 ticks
                    if (tintCounter % 5 == 0) {
                        boneMap.values().forEach(b -> b.spawnParticles(Particle.POOF, .1));
                    }
                }
            }
        }

        riddenPoseCounter++;
        if (getSkeletonWatchers().hasObservers()) {
            passLocation = getCurrentLocation();
            int interval = currentRiddenPoseInterval();
            passPoseInterval = interval;
            passBoneUpdates = 0;
            try {
                rootBone.transform(abstractPacketBundle);
            } finally {
                passLocation = null;
                passPoseInterval = 0;
            }
            // Between pose ticks a ridden model sends nothing, so its load stays what its last pose pass sent.
            if (!isRidden() || isRiddenPoseTick(interval)) poseBoneUpdates = passBoneUpdates;
        } else {
            poseBoneUpdates = 0;
        }
    }

    void prepareDisplayPose(AbstractPacketBundle packetBundle) {
        freshenPose();
        if (rootBone == null) return;
        passLocation = getCurrentLocation();
        try {
            rootBone.prepareDisplayPose(packetBundle);
        } finally {
            passLocation = null;
        }
    }

    public void tint() {
        // start (or restart) the tint animation
        tinting = true;
        tintCounter = 0;
    }

    /**
     * Stores the given color as this skeleton's persistent tint and immediately
     * pushes it to every bone via the leather-armor dye channel. The damage
     * flash animation (see {@link #tint()}) now fades back to this value once
     * it completes. While a flash is in flight, the new color is stored but
     * does not clobber the in-flight animation — the flash restore path picks
     * the updated value up when it finishes.
     *
     * @param color the color to apply, or {@code null} to clear the persistent
     *              tint (bones revert to undyed / {@link Color#WHITE})
     */
    public void applyPersistentTint(Color color) {
        this.persistentTint = color;
        if (tinting) return;
        Color applied = color != null ? color : Color.WHITE;
        boneMap.values().forEach(b -> b.setHorseLeatherArmorColor(applied));
    }

    /**
     * Gets an IK chain by its controller (null object) name.
     *
     * @param controllerName The name of the null object controlling the chain
     * @return The IK chain, or null if not found
     */
    public IKChain getIKChain(String controllerName) {
        return ikChainMap.get(controllerName);
    }

    /**
     * Solves all IK chains in this skeleton.
     * Should be called after IK goal offsets have been set from animation.
     */
    public void solveAllIKChains() {
        for (IKChain chain : ikChains) {
            chain.solve();
        }
    }

    /**
     * Clears IK rotations from all chains.
     * Call this when IK animation ends to return to normal animation.
     */
    public void clearAllIKRotations() {
        for (IKChain chain : ikChains) {
            chain.clearIKRotations();
        }
    }

    /**
     * Checks if this skeleton has any IK chains.
     *
     * @return true if the skeleton has IK chains
     */
    public boolean hasIKChains() {
        return !ikChains.isEmpty();
    }
}
