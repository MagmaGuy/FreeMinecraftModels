package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketEquipmentVeil;
import com.magmaguy.easyminecraftgoals.internal.StackedText;
import com.magmaguy.freeminecraftmodels.bedrock.BedrockModeledEntity;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.MountPointManager;
import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.customentity.core.RegisterModelEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.customentity.core.components.*;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.freeminecraftmodels.utils.ImmutableMapSnapshots;
import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ModeledEntity {
    @Getter
    private static final Set<ModeledEntity> loadedModeledEntities = ConcurrentHashMap.newKeySet();
    private static final ConcurrentHashMap<Entity, ModeledEntity>
            loadedModeledEntitiesWithUnderlyingEntities =
            new ConcurrentHashMap<>();
    @Getter
    private final String entityID;
    /**
     * Stable identity for this logical model instance. Unlike the backing Bukkit
     * entity UUID, this is also available for props that have no underlying entity.
     */
    @Getter
    private final UUID modelInstanceId = UUID.randomUUID();
    @Getter
    private final InteractionComponent interactionComponent = new InteractionComponent(this);
    @Getter
    private final HitboxComponent hitboxComponent = new HitboxComponent(this);
    @Getter
    private final DamageableComponent damageableComponent = new DamageableComponent(this);
    @Getter
    private final AnimationComponent animationComponent = new AnimationComponent(this);
    @Getter
    private final ParticleComponent particleComponent = new ParticleComponent(this);
    @Getter
    protected Entity underlyingEntity = null;
    private Entity spawnRegistrationEntity = null;
    private String previousSpawnModelRegistration = null;
    private boolean spawnRegistrationRollbackArmed = false;
    protected Location spawnLocation = null;
    protected Location currentLocation = null;
    // Cached location specifically for bone transforms - when set, bone transforms use this instead of getLocation()
    protected Location cachedBoneTransformLocation = null;
    // Collision detection properties
    /**
     * Whether the entity is currently dying.
     * This is set to true when the entity is in the process of getting removed with a death animation.
     */
    @Getter
    private boolean isDying = false;
    @Getter
    private SkeletonBlueprint skeletonBlueprint = null;
    @Getter
    private Skeleton skeleton;
    @Getter
    private BedrockModeledEntity bedrockModeledEntity;
    @Getter
    private final FileModelConverter fileModelConverter;
    @Getter
    private volatile boolean isRemoved = false;
    @Getter
    @Setter
    private double scaleModifier = 1.0;
    private volatile org.bukkit.attribute.AttributeInstance scaleAttribute;
    @Getter
    private MountPointManager mountPointManager = null;
    @Getter
    private String displayName = null;
    /** Blocks between the top of a model without a tag bone and its name: four pixels. */
    private static final double NAMEPLATE_MARGIN = 0.25;
    @Getter
    private final StackedText nameplate = new StackedText();
    private Bone nameplateAnchor;
    private boolean nameplateAnchorResolved;
    private int viewDistanceOverride = -1;
    private Color persistentTint = null;
    // Fixed light for every display bone; -1 while the bones take the light where they are.
    private volatile int brightnessBlockLight = -1;
    private volatile int brightnessSkyLight = -1;
    // World-space lean of the whole model about its middle, after its facing; null while upright. Never mutated
    // after publication, so the model clock and viewer admission read it without locking.
    private volatile Quaterniondc bodyTilt;
    // Whether the model is drawn with its middle on its entity's middle instead of standing on the entity's feet.
    private volatile boolean centeredOnBody;

    public ModeledEntity(String entityID, Location spawnLocation) {
        this.entityID = entityID;
        this.spawnLocation = spawnLocation;
        this.currentLocation = spawnLocation;

        fileModelConverter = FileModelConverter.getModel(entityID);
        if (fileModelConverter == null) {
            Logger.warn("Failed to initialize ModeledEntity: FileModelConverter not found for entityID: " + entityID);
            return;
        }

        skeletonBlueprint = fileModelConverter.getSkeletonBlueprint();
        if (skeletonBlueprint == null) {
            Logger.warn("Failed to initialize ModeledEntity: SkeletonBlueprint not found for entityID: " + entityID);
            return;
        }

        skeleton = new Skeleton(skeletonBlueprint, this);

        // Initialize mount points if the model has mount_ bones
        if (!skeleton.getMountPointBones().isEmpty()) {
            mountPointManager = new MountPointManager(skeleton, this);
        }

        animationComponent.initializeAnimationManager(fileModelConverter);
    }

    private void initializeBedrockBackend() {
        if (bedrockModeledEntity != null || fileModelConverter == null) {
            return;
        }
        // Only build the per-model Bedrock backend when a Bedrock proxy (Geyser/Floodgate) is
        // installed locally. Proxy-only viewers require a separate activation path; constructing and
        // ticking a BedrockModeledEntity (a fake carrier entity + exported bundle) for every
        // model is pure RAM/CPU waste on a Java-only server. Bones still serve Java players;
        // every Bedrock call site already null-checks getBedrockModeledEntity().
        if (com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker.isBedrockSupportPresent()) {
            try {
                // Use explicit, initialized base-class state rather than asking a potentially
                // overridden getLocation(). This activation runs only after the subclass and
                // display spawn path are complete.
                bedrockModeledEntity = new BedrockModeledEntity(this, fileModelConverter, currentLocation);
            } catch (Throwable throwable) {
                Logger.warn("Failed to initialize Bedrock custom entity backend for " + entityID + ": " + throwable.getMessage());
            }
        }
    }

    private static boolean isNameTag(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(BoneBlueprint.nameTagKey, PersistentDataType.BYTE);
    }

    public static void shutdown() {
        // Create a copy of the collection to avoid ConcurrentModificationException
        HashSet<ModeledEntity> entitiesToRemove = new HashSet<>(loadedModeledEntities);

        // Iterate over the copy
        for (ModeledEntity entity : entitiesToRemove) {
            entity.shutdownRemove();
        }

        // Clear the original collection
        loadedModeledEntities.clear();
        loadedModeledEntitiesWithUnderlyingEntities.clear();
    }

    public static HashMap<Entity, ModeledEntity>
    getLoadedModeledEntitiesWithUnderlyingEntities() {
        return ImmutableMapSnapshots.hashMapCopyOf(
                loadedModeledEntitiesWithUnderlyingEntities);
    }

    /**
     * O(1) single-entity lookup against the live underlying-entity registry.
     * Prefer this over {@link #getLoadedModeledEntitiesWithUnderlyingEntities()}
     * when only one entity is needed — the snapshot getter copies the whole
     * registry per call.
     *
     * @param entity the underlying Bukkit entity, may be null
     * @return the modeled entity bound to it, or null if none
     */
    public static ModeledEntity getModeledEntity(Entity entity) {
        if (entity == null) return null;
        return loadedModeledEntitiesWithUnderlyingEntities.get(entity);
    }

    public void setUnderlyingEntity(Entity underlyingEntity) {
        this.underlyingEntity = underlyingEntity;
        // Resolved here, on the main thread: Bukkit's getAttribute inserts the instance into the mob's attribute map
        // on first use, and the model clock reading it off-thread raced the server saving that map.
        org.bukkit.attribute.Attribute scale = AttributeManager.getAttribute("generic_scale");
        scaleAttribute = scale != null && underlyingEntity instanceof LivingEntity livingEntity ? livingEntity.getAttribute(scale) : null;
        spawnRegistrationEntity = underlyingEntity;
        previousSpawnModelRegistration = underlyingEntity.getPersistentDataContainer()
                .get(RegisterModelEntity.ENTITY_KEY, PersistentDataType.STRING);
        spawnRegistrationRollbackArmed = true;
        loadedModeledEntitiesWithUnderlyingEntities.put(underlyingEntity, this);
        RegisterModelEntity.registerModelEntity(underlyingEntity, getSkeletonBlueprint().getModelName());
        hitboxComponent.setCustomHitboxOnUnderlyingEntity();
    }

    public void setDisplayName(String displayName) {
        if (Objects.equals(this.displayName, displayName)) return;
        this.displayName = displayName;
        nameplate.setLines(displayName == null || displayName.isEmpty() ? List.of() : List.of(displayName));
    }

    /** Lines are top-to-bottom; the last line remains at the model's name anchor. */
    public void setDisplayNameLines(List<String> lines) {
        setDisplayName(String.join("\n", lines));
    }

    public void setDisplayNameScale(float scale) { nameplate.setScale(scale); }

    /** Clear gap in blocks at scale 1; scaled Java text scales both glyphs and spacing. */
    public void setDisplayNameLineGap(double gap) { nameplate.setLineGap(gap); }

    public void setDisplayNameVisible(boolean visible) {
        nameplate.setVisible(visible);
    }

    /** Authored nametag position, or a fallback above the model's hitbox. */
    public Location getNameplateLocation() {
        if (!nameplateAnchorResolved && skeleton != null) {
            nameplateAnchor = skeleton.getNametags().stream()
                    .min(Comparator.comparing(bone -> bone.getBoneBlueprint().getBoneName())).orElse(null);
            nameplateAnchorResolved = true;
        }
        if (nameplateAnchor != null)
            return nameplateAnchor.getBoneLocation().subtract(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT() - 0.5, 0);
        Location location = getLocation();
        if (location == null) return null;
        // No authored tag bone: a few pixels above the model's highest cube at rest, scaled with the entity.
        double top = skeletonBlueprint == null ? 2 : skeletonBlueprint.getModelTop();
        return location.clone().add(0, top * scaleModifier + getBodyOffset() + NAMEPLATE_MARGIN, 0);
    }

    /**
     * Gets the set of UUIDs of all viewers (players who can see the entity)
     *
     * @return
     */
    public HashSet<UUID> getViewers() {
        return skeleton.getSkeletonWatchers().getViewers();
    }

    /**
     * Hides this model from one player while everyone else keeps seeing it. The bones, nameplate, particles,
     * Bedrock model and click hitbox all disappear for that player, and stay hidden until {@link #showTo(Player)}
     * or until the model is removed.
     */
    public void hideFrom(Player player) {
        skeleton.getSkeletonWatchers().hideFromPlayer(player);
    }

    /** Undoes {@link #hideFrom(Player)}. The player sees the model again once it is in range and in sight. */
    public void showTo(Player player) {
        skeleton.getSkeletonWatchers().showToPlayer(player);
    }

    /** Whether {@link #hideFrom(Player)} has hidden this model from the player. */
    public boolean isHiddenFrom(Player player) {
        return skeleton.getSkeletonWatchers().isHiddenFrom(player);
    }

    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    protected void displayInitializer() {
        skeleton.generateDisplays();
        // Create packet interaction entity for click detection
        hitboxComponent.createPacketInteractionEntity();
    }

    public void spawn(Entity entity) {
        try {
            setUnderlyingEntity(entity);
            this.spawnLocation = entity.getLocation();
            this.currentLocation = entity.getLocation();
            displayInitializer();
            registerLoadedEntity();
            commitSpawnActivation();
        } catch (RuntimeException | Error activationFailure) {
            rollbackFailedSpawn(activationFailure);
            throw activationFailure;
        }
    }

    public void spawn(Location location) {
        try {
            this.spawnLocation = location;
            this.currentLocation = location;
            displayInitializer();
            registerLoadedEntity();
            commitSpawnActivation();
        } catch (RuntimeException | Error activationFailure) {
            rollbackFailedSpawn(activationFailure);
            throw activationFailure;
        }
    }

    public void spawn() {
        spawn(spawnLocation);
    }

    private void registerLoadedEntity() {
        // ModeledEntitiesClock ticks asynchronously. Publishing this object from
        // the base constructor allowed the clock to invoke subclass overrides
        // before subclass fields were initialized (or before an underlying entity
        // had been bound). A modeled entity only becomes tickable after its spawn
        // path and display initialization have completed successfully.
        if (isRemoved) return;
        initializeBedrockBackend();
        onSpawnComplete();
        // Spawn hooks can remove the model, for example when its backing entity
        // is already invalid. Never publish it again after that removal. Pair
        // publication with markRemoved so the async clock cannot race this check.
        synchronized (this) {
            if (isRemoved) return;
            hitboxComponent.refreshPacketInteractionAliases();
            loadedModeledEntities.add(this);
        }
    }

    /**
     * Hook invoked once the spawn path and display initialization have completed
     * successfully, immediately before this entity is published to the tickable
     * registry. Subclasses override this to run their post-spawn wiring at a
     * point where the underlying entity (if any) and Bedrock backend are bound.
     */
    protected void onSpawnComplete() {
    }

    /** Subclasses undo any registry entry installed by a partial onSpawnComplete. */
    protected void onSpawnFailed() {
    }

    private void commitSpawnActivation() {
        spawnRegistrationRollbackArmed = false;
        spawnRegistrationEntity = null;
        previousSpawnModelRegistration = null;
    }

    /**
     * Rolls back only model-owned activation state. The caller's backing entity
     * remains alive; a rendering failure must not delete an EliteMob or restored
     * persistent prop that FMM was asked to wrap.
     */
    private void rollbackFailedSpawn(Throwable activationFailure) {
        markRemoved();
        cleanupAfterFailedSpawn(activationFailure, this::onSpawnFailed);
        cleanupAfterFailedSpawn(activationFailure, interactionComponent::clearCallbacks);
        cleanupAfterFailedSpawn(activationFailure, hitboxComponent::removePacketInteractionEntity);
        if (mountPointManager != null) {
            cleanupAfterFailedSpawn(activationFailure, mountPointManager::cleanup);
        }
        if (bedrockModeledEntity != null) {
            cleanupAfterFailedSpawn(activationFailure, bedrockModeledEntity::remove);
        }
        cleanupAfterFailedSpawn(activationFailure, skeleton::remove);
        loadedModeledEntities.remove(this);

        Entity registeredEntity = spawnRegistrationEntity;
        if (registeredEntity != null) {
            loadedModeledEntitiesWithUnderlyingEntities.remove(registeredEntity, this);
        }
        if (spawnRegistrationRollbackArmed && registeredEntity != null) {
            cleanupAfterFailedSpawn(activationFailure, () -> {
                String current = registeredEntity.getPersistentDataContainer()
                        .get(RegisterModelEntity.ENTITY_KEY, PersistentDataType.STRING);
                if (!Objects.equals(current, getSkeletonBlueprint().getModelName())) return;
                if (previousSpawnModelRegistration == null) {
                    registeredEntity.getPersistentDataContainer().remove(RegisterModelEntity.ENTITY_KEY);
                } else {
                    registeredEntity.getPersistentDataContainer().set(
                            RegisterModelEntity.ENTITY_KEY,
                            PersistentDataType.STRING,
                            previousSpawnModelRegistration);
                }
            });
        }
        commitSpawnActivation();
    }

    private static void cleanupAfterFailedSpawn(Throwable activationFailure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (RuntimeException | Error cleanupFailure) {
            activationFailure.addSuppressed(cleanupFailure);
        }
    }

    protected void shutdownRemove() {
        remove();
    }

    public void tick(AbstractPacketBundle abstractPacketBundle) {
        //check if the entity exists, basically
        if (isRemoved || getLocation() == null) return;
        updateBonePlacement();
        getSkeleton().tick(abstractPacketBundle);
        if (displayName != null && !displayName.isEmpty()) nameplate.move(getNameplateLocation());
        if (bedrockModeledEntity != null) bedrockModeledEntity.tick();
        hitboxComponent.tick(abstractPacketBundle);
        animationComponent.tick();
        particleComponent.tick(abstractPacketBundle, skeleton.getSkeletonWatchers().getJavaViewers());
        org.bukkit.attribute.AttributeInstance scale = scaleAttribute;
        if (scale != null && underlyingEntity != null && underlyingEntity.isValid()) scaleModifier = scale.getValue() / seatScale();
    }

    /** The part of the underlying entity's scale that only moves its rider's seat, which the model leaves out. */
    protected double seatScale() {
        return 1;
    }

    /**
     * Display bones ride the underlying entity while Java clients track it, so they move with the entity's own
     * client-side smoothing instead of trailing it; every other model keeps the per-bone teleports.
     */
    private void updateBonePlacement() {
        int vehicleId = -1;
        org.bukkit.util.Vector seat = null;
        if (DefaultConfig.displayBonesRideTheirEntity && ridesUnderlyingEntity()) {
            com.magmaguy.easyminecraftgoals.NMSAdapter adapter = com.magmaguy.easyminecraftgoals.NMSManager.getAdapter();
            // Packet passengers follow the real ones, so the bones take the next seat.
            seat = adapter == null ? null
                    : adapter.getPassengerSeatOffset(underlyingEntity, underlyingEntity.getPassengers().size());
            if (seat != null) vehicleId = underlyingEntity.getEntityId();
        }
        skeleton.setRiddenPlacement(vehicleId, seat);
    }

    /** Whether Java clients track the underlying entity, which its bones need in order to ride it. */
    protected boolean ridesUnderlyingEntity() {
        return false;
    }

    public void removeWithDeathAnimation() {
        isDying = true;
        // Cache the current location so animations can continue after the underlying entity is removed
        if (underlyingEntity != null) currentLocation = deathLocation();
        if (!animationComponent.playDeathAnimation()) remove();
    }

    /** Where the model stays while its death animation plays. */
    protected Location deathLocation() {
        return underlyingEntity.getLocation();
    }

    public void removeWithMinimizedAnimation() {
        if (animationComponent.isScalingDown()) return;
        isDying = true;
        animationComponent.removeWithMinimizedAnimation();
    }

    public void remove() {
        if (!markRemoved()) return;

        // Clean up mount points
        if (mountPointManager != null) {
            mountPointManager.cleanup();
        }
        // Clear callbacks when removing
        interactionComponent.clearCallbacks();
        // Remove the packet interaction entity
        hitboxComponent.removePacketInteractionEntity();
        if (bedrockModeledEntity != null) bedrockModeledEntity.remove();
        particleComponent.remove();
        nameplate.remove();
        skeleton.remove();
        if (underlyingEntity != null) {
            // Only actually despawn the underlying entity for non-persistent
            // cases — persistent props must serialize with the chunk.
            if (!(this instanceof PropEntity) ||
                    this instanceof PropEntity propEntity && !propEntity.isPersistent()) {
                Entity entityToRemove = underlyingEntity;
                Runnable removeUnderlyingEntity = entityToRemove::remove;
                if (Bukkit.isPrimaryThread() || MetadataHandler.PLUGIN == null || !MetadataHandler.PLUGIN.isEnabled()) {
                    removeUnderlyingEntity.run();
                } else {
                    Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, removeUnderlyingEntity);
                }
            }
        }
    }

    private synchronized boolean markRemoved() {
        if (isRemoved) return false;
        isRemoved = true;
        loadedModeledEntities.remove(this);
        if (underlyingEntity != null) {
            loadedModeledEntitiesWithUnderlyingEntities.remove(underlyingEntity, this);
        }
        return true;
    }

    /**
     * Returns the name tag locations. Useful if you want to add more text above or below them.
     *
     * @return
     */
    public List<Bone> getNametagBones() {
        return skeleton.getNametags();
    }

    public World getWorld() {
        if (underlyingEntity == null && spawnLocation == null) return null;
        if (underlyingEntity != null && underlyingEntity.isValid()) return underlyingEntity.getWorld();
        if (spawnLocation != null) return spawnLocation.getWorld();
        if (currentLocation != null) return currentLocation.getWorld();
        return null;
    }

    public Location getLocation() {
        if (underlyingEntity != null) return underlyingEntity.getLocation();
        if (currentLocation != null) return currentLocation.clone();
        return null;
    }

    /** The world-space lean the whole model takes about its middle, or null while it stands upright. */
    @Nullable
    public Quaterniondc getBodyTilt() {
        return bodyTilt;
    }

    /**
     * Leans the whole model: a world-space rotation about the middle of its height, applied after it faces its
     * heading and after its animations. Pass null to stand it upright. A {@link DynamicEntity} with a travel
     * orientation other than upright sets this every tick from its travel.
     */
    public void setBodyTilt(@Nullable Quaterniondc tilt) {
        bodyTilt = tilt == null ? null : new Quaterniond(tilt);
    }

    /**
     * Blocks above the entity's feet that a body tilt turns about: the middle of the model's cubes at its current
     * scale, where it is drawn. For a model centred on its body that is the body's middle.
     */
    public double getBodyTiltPivotHeight() {
        return getModelMiddleHeight() + getBodyOffset();
    }

    /** Blocks above the model's own feet of the middle of its cubes, at its current scale. */
    public double getModelMiddleHeight() {
        SkeletonBlueprint blueprint = getSkeletonBlueprint();
        if (blueprint == null) return 0D;
        return blueprint.getBodyTiltPivot() * scaleModifier;
    }

    public boolean isCenteredOnBody() {
        return centeredOnBody;
    }

    /**
     * Draws the model with the middle of its cubes on the middle of its entity instead of standing on the entity's
     * feet. A flying or swimming creature whose model is much bigger than its hidden body then moves, turns and
     * leans around that body, and its nameplate, click box and hit box move with it.
     */
    public void setCenteredOnBody(boolean centered) {
        centeredOnBody = centered;
    }

    /**
     * Blocks the model is drawn above its entity's feet: none while it stands on them; for a model centred on its
     * body, whatever puts the middle of its cubes on the body's middle, below the feet when the model is the taller.
     */
    public double getBodyOffset() {
        if (!centeredOnBody) return 0D;
        double body = bodyHeight();
        return body <= 0D ? 0D : body / 2D - getModelMiddleHeight();
    }

    /** Blocks above the entity's feet of the model's lowest cube as drawn, at its current scale. */
    public double getModelBottomHeight() {
        SkeletonBlueprint blueprint = getSkeletonBlueprint();
        if (blueprint == null) return 0D;
        return blueprint.getModelBottom() * scaleModifier + getBodyOffset();
    }

    /**
     * Blocks above the entity's feet where a box of this height standing for the model starts: at the feet, or for
     * a model centred on its body, wherever centres the box on the body too.
     */
    public double getBoxLift(double height) {
        if (!centeredOnBody) return 0D;
        double body = bodyHeight();
        return body <= 0D ? 0D : body / 2D - height / 2D;
    }

    /** Height of the entity the model is on, in blocks, or 0 without one. */
    protected double bodyHeight() {
        Entity body = underlyingEntity;
        return body == null || !body.isValid() ? 0D : body.getHeight();
    }

    /**
     * Gets the location to use for bone transforms. If a cached location is set,
     * returns that; otherwise returns getLocation().
     * This allows freezing the bone transform position independently of the entity's actual position.
     */
    public Location getBoneTransformLocation() {
        if (cachedBoneTransformLocation != null) return cachedBoneTransformLocation.clone();
        return getLocation();
    }

    /**
     * Sets the cached bone transform location. When set, bone transforms will use this
     * location instead of getLocation(). Set to null to resume using getLocation().
     */
    public void setCachedBoneTransformLocation(Location location) {
        this.cachedBoneTransformLocation = location != null ? location.clone() : null;
    }

    public void showUnderlyingEntity(Player player) {
        if (underlyingEntity == null || !underlyingEntity.isValid()) return;
        // A viewer whose client tracks the body for its bones sees it veiled; lift the veil so it shows in the flesh.
        PacketEquipmentVeil.unveil(player.getUniqueId(), underlyingEntity.getEntityId());
        player.showEntity(MetadataHandler.PLUGIN, underlyingEntity);
        underlyingEntity.setGlowing(true);
        skeleton.getSkeletonWatchers().refreshEntityTracking();
    }

    public void hideUnderlyingEntity(Player player) {
        if (underlyingEntity == null || !underlyingEntity.isValid()) return;
        skeleton.getSkeletonWatchers().reveilTrackedBody(player);
        player.hideEntity(MetadataHandler.PLUGIN, underlyingEntity);
        underlyingEntity.setGlowing(false);
        // The bones cannot ride an entity this client no longer has, so they go back to being placed freely.
        skeleton.getSkeletonWatchers().refreshEntityTracking();
    }

    /**
     * Teleports the entity represented by this {@code ModeledEntity} to the specified location.
     * Optionally teleports the underlying entity if one is associated with this {@code ModeledEntity}.
     * If another plugin has already teleported the underlying entity, do not teleport the underlying entity.
     * If another plugin did not manage the underlying entity, teleport it.
     * This primarily pushes teleport packets to clients.
     *
     * @param location                 the target {@link Location} to which the entity should be teleported
     * @param teleportUnderlyingEntity a boolean indicating whether the underlying entity, if present,
     *                                 should also be teleported to the specified location
     */
    public void teleport(Location location, boolean teleportUnderlyingEntity) {
        if (teleportUnderlyingEntity && underlyingEntity != null) {
            underlyingEntity.teleport(location);
        } else {
            currentLocation = location;
        }
    }


    //DamageableComponent

    /**
     * Inflicts damage on the entity based on the specified amount.
     * This method delegates the damage operation to the {@code damageableComponent}
     * associated with the current entity.
     *
     * @param amount the amount
     */
    public void damage(double amount) {
        damageableComponent.damage(amount);
    }

    /**
     * Inflicts damage on this entity by a specified amount, attributed to the given damager.
     * Delegates the damage application and handling to the associated damageable component.
     *
     * @param damager the entity causing the damage
     * @param amount  the amount of damage to deal
     */
    public void damage(Entity damager, double amount) {
        damageableComponent.damage(damager, amount);
    }

    /**
     * Applies damage to this entity as inflicted by the specified damager.
     * Delegates the damage logic to the {@code damageableComponent} associated with this entity.
     *
     * @param damager the entity causing the damage
     */
    public void damage(Entity damager) {
        damageableComponent.damage(damager);
    }

    /**
     * Applies damage to the current entity based on the attributes of the provided projectile
     * (impact speed, base damage, and bow enchantments such as Power and Piercing).
     *
     * @return true if the projectile hit was applied, false if it was ignored
     * (self-hit, non-arrow projectile, or duplicate within the dedup window)
     */
    public boolean damage(Projectile projectile) {
        return damageableComponent.damage(projectile);
    }

    /**
     * Performs an attack on the specified living entity target.
     * This method delegates the attack logic to the `damageableComponent` associated with the current entity.
     * Use this method to simulate attacks against other living entities in the game.
     *
     * @param target the {@link LivingEntity} that the current entity is attacking
     */
    public void attack(LivingEntity target) {
        damageableComponent.attack(target);
    }

    /**
     * Attacks the specified target entity with a specified amount of damage.
     * This method delegates to the damageable component of the entity to apply the damage to the target.
     *
     * @param target The target entity to be attacked.
     * @param damage The amount of damage to deal to the target.
     */
    public void attack(LivingEntity target, double damage) {
        damageableComponent.attack(target, damage);
    }

    /**
     * Sets a callback to be invoked when the entity is left-clicked by a player.
     *
     * @param callback the {@link ModeledEntityLeftClickCallback} to execute when a left-click interaction occurs
     * @return the current {@code ModeledEntity} instance, allowing for method chaining
     */
    //InteractionComponent
    public ModeledEntity setLeftClickCallback(ModeledEntityLeftClickCallback callback) {
        interactionComponent.setLeftClickCallback(callback);
        return this;
    }

    /**
     * Sets a callback that is triggered when the entity is right-clicked by a player.
     *
     * @param callback the {@link ModeledEntityRightClickCallback} to execute when the entity is right-clicked
     * @return the current {@link ModeledEntity} instance, allowing for method chaining
     */
    public ModeledEntity setRightClickCallback(ModeledEntityRightClickCallback callback) {
        interactionComponent.setRightClickCallback(callback);
        return this;
    }

    /**
     * Sets the callback to be triggered when a player contacts the hitbox of this entity.
     * This allows for the execution of custom behavior when a hitbox interaction event occurs.
     *
     * @param callback the callback function to handle hitbox contact events. The callback should
     *                 implement {@link ModeledEntityHitboxContactCallback}, which provides the player
     *                 involved in the contact and the current entity.
     * @return the current instance of {@code ModeledEntity} for method chaining.
     */
    public ModeledEntity setHitboxContactCallback(ModeledEntityHitboxContactCallback callback) {
        interactionComponent.setHitboxContactCallback(callback);
        return this;
    }

    public ModeledEntity setModeledEntityHitByProjectileCallback(ModeledEntityHitByProjectileCallback callback) {
        interactionComponent.setProjectileHitCallback(callback);
        return this;
    }

    //AnimationComponent

    /**
     * Plays an animation as set by the string name.
     *
     * @param animationName  Name of the animation - case-sensitive
     * @param blendAnimation If the animation should blend. If set to false, the animation passed will stop other animations.
     *                       If set to true, the animation will be mixed with any currently ongoing animations
     * @return Whether the animation successfully started playing.
     */
    public boolean playAnimation(String animationName, boolean blendAnimation, boolean loop) {
        return animationComponent.playAnimation(animationName, blendAnimation, loop);
    }

    /**
     * Stops all currently running animations.
     * <p>
     * This method invokes the stopCurrentAnimations operation
     * on the animationComponent, ensuring that any ongoing animations
     * tied to the current state are terminated immediately.
     * <p>
     * It is typically used when there is a need to halt animations
     * due to state changes or to free up resources.
     */
    public void stopCurrentAnimations() {
        animationComponent.stopCurrentAnimations();
    }

    /** Shows Bedrock clients the base animation and the action layered over it; either may be null. */
    public void updateBedrockAnimations(String baseAnimation, String actionAnimation) {
        if (bedrockModeledEntity != null) bedrockModeledEntity.playAnimations(baseAnimation, actionAnimation);
    }

    /**
     * Checks if the specified animation exists in the animation component.
     *
     * @param animationName the name of the animation to check
     * @return true if the animation exists, false otherwise
     */
    public boolean hasAnimation(String animationName) {
        return animationComponent.hasAnimation(animationName);
    }

    /**
     * Overrides the plugin-wide default view distance for this single modeled entity.
     * Pass -1 to revert to default.
     *
     * @param blocks the view distance in blocks, or -1 to use the plugin-wide default
     *               ({@code DefaultConfig.maxModelViewDistance})
     * @return the current {@code ModeledEntity} instance, allowing for method chaining
     */
    public ModeledEntity setViewDistanceOverride(int blocks) {
        this.viewDistanceOverride = blocks;
        return this;
    }

    /**
     * Returns the effective view distance for this entity. If an override has been
     * set via {@link #setViewDistanceOverride(int)} with a value greater than zero,
     * the override is returned; otherwise the plugin-wide default
     * ({@code DefaultConfig.maxModelViewDistance}) is returned.
     *
     * @return the effective view distance in blocks
     */
    public int getEffectiveViewDistance() {
        return viewDistanceOverride > 0 ? viewDistanceOverride : DefaultConfig.maxModelViewDistance;
    }

    /**
     * Persistent tint applied via the leather-armor dye channel. Damage flash
     * temporarily overrides, then fades back to this tint. Pass null to clear.
     *
     * @param color the persistent color to apply, or {@code null} to revert to
     *              undyed (equivalent to {@link Color#WHITE} on the dye channel)
     * @return the current {@code ModeledEntity} instance, allowing for method chaining
     */
    public ModeledEntity setTintColor(Color color) {
        this.persistentTint = color;
        if (skeleton != null) skeleton.applyPersistentTint(color);
        return this;
    }

    /**
     * Returns the persistent tint set via {@link #setTintColor(Color)}, or
     * {@code null} if no persistent tint has been applied.
     */
    public Color getTintColor() {
        return persistentTint;
    }

    /**
     * Lights every display bone at a fixed level instead of the light where it stands, as a glowing creature or a
     * lantern needs. 15 and 15 is fully bright day and night. Java display bones only: Bedrock and armor stand bones
     * keep their world light. Viewers get the change with the next bone update.
     *
     * @param blockLight 0 to 15
     * @param skyLight   0 to 15
     * @return the current {@code ModeledEntity} instance, allowing for method chaining
     */
    public ModeledEntity setBrightnessOverride(int blockLight, int skyLight) {
        int block = Math.max(0, Math.min(15, blockLight));
        int sky = Math.max(0, Math.min(15, skyLight));
        if (block == brightnessBlockLight && sky == brightnessSkyLight) return this;
        brightnessBlockLight = block;
        brightnessSkyLight = sky;
        if (skeleton != null) skeleton.applyBrightnessOverride(block, sky);
        return this;
    }

    /**
     * Drops the fixed light set by {@link #setBrightnessOverride(int, int)}, so the bones take the light where they
     * are again.
     *
     * @return the current {@code ModeledEntity} instance, allowing for method chaining
     */
    public ModeledEntity clearBrightnessOverride() {
        if (brightnessBlockLight < 0) return this;
        brightnessBlockLight = -1;
        brightnessSkyLight = -1;
        if (skeleton != null) skeleton.applyBrightnessOverride(-1, -1);
        return this;
    }

    /** Whether {@link #setBrightnessOverride(int, int)} lights the bones at a fixed level. */
    public boolean hasBrightnessOverride() {
        return brightnessBlockLight >= 0;
    }

    /** The fixed block light of the bones, or -1 without an override. */
    public int getBrightnessOverrideBlockLight() {
        return brightnessBlockLight;
    }

    /** The fixed sky light of the bones, or -1 without an override. */
    public int getBrightnessOverrideSkyLight() {
        return brightnessSkyLight;
    }

}
