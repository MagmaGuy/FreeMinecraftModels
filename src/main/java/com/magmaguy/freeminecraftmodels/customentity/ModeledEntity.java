package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
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
    protected Entity underlyingEntity = null;
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
    private final FileModelConverter fileModelConverter;
    @Getter
    private volatile boolean isRemoved = false;
    @Getter
    @Setter
    private double scaleModifier = 1.0;
    @Getter
    private MountPointManager mountPointManager = null;
    @Getter
    private String displayName = null;
    private int viewDistanceOverride = -1;
    private Color persistentTint = null;

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
        // actually installed. Without one there can be no Bedrock viewers, so constructing and
        // ticking a BedrockModeledEntity (a fake carrier entity + exported bundle) for every
        // model is pure RAM/CPU waste on a Java-only server. Bones still serve Java players;
        // every Bedrock call site already null-checks getBedrockModeledEntity().
        if (com.magmaguy.freeminecraftmodels.thirdparty.BedrockChecker.isBedrockSupportPresent()) {
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
        loadedModeledEntitiesWithUnderlyingEntities.put(underlyingEntity, this);
        RegisterModelEntity.registerModelEntity(underlyingEntity, getSkeletonBlueprint().getModelName());
        hitboxComponent.setCustomHitboxOnUnderlyingEntity();
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
        getSkeleton().getNametags().forEach(nametag -> nametag.getBoneTransforms().setTextDisplayText(displayName));
    }

    public void setDisplayNameVisible(boolean visible) {
        getSkeleton().getNametags().forEach(nametag -> nametag.getBoneTransforms().setTextDisplayVisible(visible));
    }

    /**
     * Gets the set of UUIDs of all viewers (players who can see the entity)
     *
     * @return
     */
    public HashSet<UUID> getViewers() {
        return skeleton.getSkeletonWatchers().getViewers();
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
        setUnderlyingEntity(entity);
        this.spawnLocation = entity.getLocation();
        this.currentLocation = entity.getLocation();
        displayInitializer();
        registerLoadedEntity();
    }

    public void spawn(Location location) {
        this.spawnLocation = location;
        this.currentLocation = location;
        displayInitializer();
        registerLoadedEntity();
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

    protected void shutdownRemove() {
        remove();
    }

    public void tick(AbstractPacketBundle abstractPacketBundle) {
        //check if the entity exists, basically
        if (isRemoved || getLocation() == null) return;
        getSkeleton().tick(abstractPacketBundle);
        if (bedrockModeledEntity != null) bedrockModeledEntity.tick();
        hitboxComponent.tick(abstractPacketBundle);
        animationComponent.tick();
        if (underlyingEntity != null && underlyingEntity.isValid() && underlyingEntity instanceof LivingEntity livingEntity && livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")) != null)
            scaleModifier = livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")).getValue();
    }

    public void removeWithDeathAnimation() {
        isDying = true;
        // Cache the current location so animations can continue after the underlying entity is removed
        if (underlyingEntity != null) currentLocation = underlyingEntity.getLocation();
        if (!animationComponent.playDeathAnimation()) remove();
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
        player.showEntity(MetadataHandler.PLUGIN, underlyingEntity);
        underlyingEntity.setGlowing(true);
    }

    public void hideUnderlyingEntity(Player player) {
        if (underlyingEntity == null || !underlyingEntity.isValid()) return;
        player.hideEntity(MetadataHandler.PLUGIN, underlyingEntity);
        underlyingEntity.setGlowing(false);
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

    public void updateBedrockAnimation(String animationName) {
        if (bedrockModeledEntity != null) bedrockModeledEntity.playAnimation(animationName);
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

}
