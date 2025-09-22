package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.RegisterModelEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.customentity.core.components.*;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ModeledEntity {
    @Getter
    private static final Set<ModeledEntity> loadedModeledEntities = ConcurrentHashMap.newKeySet();
    @Getter
    private final String entityID;
    @Getter
    private final String name = "default";
    @Getter
    private final List<TextDisplay> nametags = new ArrayList<>();
    @Getter
    private final Location lastSeenLocation;
    @Getter
    private final InteractionComponent interactionComponent = new InteractionComponent(this);
    @Getter
    private final HitboxComponent hitboxComponent = new HitboxComponent(this);
    @Getter
    private final DamageableComponent damageableComponent = new DamageableComponent(this);
    @Getter
    private final AnimationComponent animationComponent = new AnimationComponent(this);
    protected int tickCounter = 0;
    @Getter
    protected Entity underlyingEntity = null;
    protected Location spawnLocation = null;
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
    private boolean isRemoved = false;
    @Getter
    @Setter
    private double scaleModifier = 1.0;
    @Getter
    private String displayName = null;

    public ModeledEntity(String entityID, Location spawnLocation) {
        this.entityID = entityID;
        this.spawnLocation = spawnLocation;
        this.lastSeenLocation = spawnLocation;

        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
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

        animationComponent.initializeAnimationManager(fileModelConverter);

        loadedModeledEntities.add(this);
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
    }

    public void setUnderlyingEntity(Entity underlyingEntity) {
        this.underlyingEntity = underlyingEntity;
        if (!(underlyingEntity instanceof PlayerDisguiseEntity))
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
    }

    public void spawn(Entity entity) {
        setUnderlyingEntity(entity);
        this.spawnLocation = entity.getLocation();
        displayInitializer();
    }

    public void spawn(Location location) {
        this.spawnLocation = location;
        displayInitializer();
    }

    public void spawn() {
        spawn(lastSeenLocation);
    }

    protected void shutdownRemove() {
        remove();
    }

    public void tick(AbstractPacketBundle abstractPacketBundle) {
        //check if the entity exists, basically
        if (isRemoved || getLocation() == null) return;
        getSkeleton().tick(abstractPacketBundle);
        hitboxComponent.tick(tickCounter);
        animationComponent.tick();
        tickCounter++;
        if (underlyingEntity != null && underlyingEntity instanceof LivingEntity livingEntity && livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")) != null)
            scaleModifier = livingEntity.getAttribute(AttributeManager.getAttribute("generic_scale")).getValue();
    }

    public void removeWithDeathAnimation() {
        isDying = true;
        if (!animationComponent.playDeathAnimation()) remove();
    }

    public void removeWithMinimizedAnimation() {
        if (animationComponent.isScalingDown()) return;
        isDying = true;
        animationComponent.removeWithMinimizedAnimation();
    }

    public void remove() {
        if (isRemoved) {
            return;
        }

        // Clear callbacks when removing
        interactionComponent.clearCallbacks();
        skeleton.remove();
        loadedModeledEntities.remove(this);
        if (underlyingEntity != null &&
                (!(this instanceof PropEntity) ||
                        this instanceof PropEntity propEntity && !propEntity.isPersistent())) {
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                underlyingEntity.remove();
            });
        }
        isRemoved = true;
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
        if (underlyingEntity != null) return underlyingEntity.getWorld();
        return spawnLocation.getWorld();
    }

    public Location getLocation() {
        if (underlyingEntity != null) return underlyingEntity.getLocation();
        if (spawnLocation != null) return spawnLocation.clone();
        return null;
    }

    public boolean isChunkLoaded() {
        return getWorld().isChunkLoaded(getLocation().getBlockX() >> 4, getLocation().getBlockZ() >> 4);
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
        }
        skeleton.teleport();
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
     * Inflicts damage on this entity by a specified amount and logs the source of the damage.
     * Delegates the damage application and handling to the associated damageable component.
     *
     * @param damager the entity causing the damage
     * @param amount  the
     */
    public void damage(Entity damager, double amount) {
        damageableComponent.damage(damager, amount);
    }

    /**
     * Applies damage to this entity as inflicted by the specified damager.
     * Delegates the damage logic to the {@code damageableComponent} associated with this entity.
     */
    public void damage(Entity entityGettingDamaged) {
        damageableComponent.damage(entityGettingDamaged);
    }

    /**
     * Applies damage to the current entity based on the attributes of the provided projectile.
     * <p>
     * This method evaluates the projectile's properties, such as speed, damage, and any potential
     * enchantments, to
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

    /**
     * Checks if the specified animation exists in the animation component.
     *
     * @param animationName the name of the animation to check
     * @return true if the animation exists, false otherwise
     */
    public boolean hasAnimation(String animationName) {
        return animationComponent.hasAnimation(animationName);
    }

}