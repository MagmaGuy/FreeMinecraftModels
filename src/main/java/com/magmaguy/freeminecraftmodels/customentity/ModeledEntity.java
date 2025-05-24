package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.animation.AnimationManager;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityHitboxContactEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityLeftClickEvent;
import com.magmaguy.freeminecraftmodels.api.ModeledEntityRightClickEvent;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class ModeledEntity {
    @Getter
    private static final HashSet<ModeledEntity> loadedModeledEntities = new HashSet<>();
    private final int scaleDurationTicks = 20;
    @Getter
    private final String entityID;
    @Getter
    private final String name = "default";
    private final Location spawnLocation;
    @Getter
    private final List<TextDisplay> nametags = new ArrayList<>();
    @Getter
    private final Location lastSeenLocation;
    @Getter
    protected LivingEntity livingEntity = null;
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
    private AnimationManager animationManager = null;
    @Getter
    private OrientedBoundingBox obbHitbox = null;
    @Getter
    private boolean isRemoved = false;
    @Getter
    @Setter
    private double scaleModifier = 1.0;
    private boolean isScalingDown = false;
    private int scaleTicksElapsed = 0;
    private double scaleStart = 1.0;
    private double scaleEnd = 0.0;

    protected int tickCounter = 0;
    // Collision detection properties
    @Getter
    @Setter
    private boolean collisionDetectionEnabled = false;

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

        if (fileModelConverter.getAnimationsBlueprint() != null) {
            try {
                animationManager = new AnimationManager(this, fileModelConverter.getAnimationsBlueprint());
            } catch (Exception e) {
                Logger.warn("Failed to initialize AnimationManager for entityID: " + entityID + ". Error: " + e.getMessage());
            }
        } else {
            Logger.warn("No AnimationsBlueprint found for entityID: " + entityID + ". AnimationManager not initialized.");
        }

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

    public OrientedBoundingBox getObbHitbox() {
        if (obbHitbox == null) {
            if (getSkeletonBlueprint().getHitbox() != null) {
                return obbHitbox = new OrientedBoundingBox(
                        getSkeleton().getCurrentLocation(),
                        //For some reason the width is the Z axis, not the X axis
                        getSkeletonBlueprint().getHitbox().getWidthZ(),
                        getSkeletonBlueprint().getHitbox().getHeight(),
                        //For some reason the width is the X axis, not the Z axis
                        getSkeletonBlueprint().getHitbox().getWidthX());
            } else {
                return obbHitbox = new OrientedBoundingBox(getSkeleton().getCurrentLocation(), 1, 2, 1);
            }
        } else return obbHitbox;
    }

    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    protected void displayInitializer(Location targetLocation) {
        skeleton.generateDisplays(targetLocation);
    }

    public void spawn(Location location) {
        displayInitializer(location);
    }

    public void spawn() {
        spawn(lastSeenLocation);
    }

    protected void shutdownRemove() {
        remove();
    }

    public void tick() {
        if (isRemoved) return; // ⬅ Stop ticking if the entity is already removed

        getSkeleton().transform();
        updateHitbox();

        if (isScalingDown) {
            scaleTicksElapsed++;

            double t = Math.min(scaleTicksElapsed / (double) scaleDurationTicks, 1.0);
            scaleModifier = lerp(scaleStart, scaleEnd, t);

            if (scaleTicksElapsed >= scaleDurationTicks) {
                scaleModifier = 0.0;
                isScalingDown = false;
                remove(); // triggers isRemoved = true
            }
        }

        if (animationManager != null) {
            animationManager.tick();
        }

        // Perform collision detection every 2 ticks if enabled
        if (collisionDetectionEnabled && tickCounter % 2 == 0) {
            checkPlayerCollisions();
        }
        tickCounter++;
    }

    private double lerp(double start, double end, double t) {
        return start + (end - start) * t;
    }

    protected void updateHitbox() {
        getObbHitbox().update(getLocation());
    }

    /**
     * Checks for collisions with nearby players and fires appropriate events
     */
    protected void checkPlayerCollisions() {
        if (getWorld() == null) {
            return;
        }

        // Check for nearby players (within 10 blocks)
        List<Player> nearbyPlayers = getWorld().getPlayers().stream()
                .filter(player -> player.getLocation().distanceSquared(getLocation()) < Math.pow(10, 2))
                .collect(Collectors.toList());

        // For each nearby player, check collision
        for (Player player : nearbyPlayers) {
            if (isPlayerColliding(player)) {
                // Fire the appropriate hitbox contact event
                triggerHitboxContactEvent(player);

                // Call the collision handler (to be overridden by subclasses)
                handlePlayerCollision(player);
            }
        }
    }

    /**
     * Checks if a player is colliding with this entity's OBB hitbox
     */
    protected boolean isPlayerColliding(Player player) {
        return getObbHitbox().isAABBCollidingWithOBB(player.getBoundingBox(), getObbHitbox());
    }

    /**
     * Override this method in subclasses to handle player collisions
     *
     * @param player The player that is colliding with this entity
     */
    protected void handlePlayerCollision(Player player) {
        // Default implementation does nothing
        // Subclasses like DynamicEntity will override this to apply damage
    }

    /**
     * Triggers the appropriate hitbox contact event based on entity type
     * This method should be overridden by subclasses to fire their specific event types
     */
    protected void triggerHitboxContactEvent(Player player) {
        ModeledEntityHitboxContactEvent event = new ModeledEntityHitboxContactEvent(player, this);
        Bukkit.getPluginManager().callEvent(event);
    }

    /**
     * Plays an animation as set by the string name.
     *
     * @param animationName  Name of the animation - case-sensitive
     * @param blendAnimation If the animation should blend. If set to false, the animation passed will stop other animations.
     *                       If set to true, the animation will be mixed with any currently ongoing animations
     * @return Whether the animation successfully started playing.
     */
    public boolean playAnimation(String animationName, boolean blendAnimation, boolean loop) {
        return animationManager.play(animationName, blendAnimation, loop);
    }

    public void removeWithDeathAnimation() {
        isDying = true;
        if (animationManager != null) {
            if (!animationManager.play("death", false, false)) {
                remove();
            }
        } else remove();
    }

    public void removeWithMinimizedAnimation() {
        if (isScalingDown) return;
        isDying = true;
        isScalingDown = true;
        scaleTicksElapsed = 0;
        scaleStart = scaleModifier;
        scaleEnd = 0.0;
    }

    public void remove() {
        if (isRemoved) {
            return;
        }

        skeleton.remove();
        loadedModeledEntities.remove(this);
        if (livingEntity != null) livingEntity.remove();
        isRemoved = true;
    }

    /**
     * Stops all currently playing animations
     */
    public void stopCurrentAnimations() {
        if (animationManager != null) animationManager.stop();
    }

    public boolean hasAnimation(String animationName) {
        if (animationManager == null) return false;
        return animationManager.hasAnimation(animationName);
    }

    /**
     * Sets the custom name that is visible in-game on the entity
     *
     * @param name Name to set
     */
    public void setName(String name) {
        skeleton.setName(name);
    }

    /**
     * Default is false
     *
     * @param visible Sets whether the name is visible
     */
    public void setNameVisible(boolean visible) {
        skeleton.setNameVisible(visible);
    }

    /**
     * Returns the name tag locations. Useful if you want to add more text above or below them.
     * Not currently guaranteed to be the exact location.
     *
     * @return
     */
    public List<ArmorStand> getNametagArmorstands() {
        return skeleton.getNametags();
    }

    public Location getLocation() {
        return spawnLocation.clone();
    }

    public boolean isChunkLoaded() {
        return getWorld().isChunkLoaded(getLocation().getBlockX() >> 4, getLocation().getBlockZ() >> 4);
    }

    public World getWorld() {
        //Overriden by extending classes
        return null;
    }

    public void damageByLivingEntity(LivingEntity player, double damage) {
        //Overriden by extending classes
    }

    public void damageByLivingEntity(LivingEntity livingEntity) {
        //Overriden by extending classes
    }

    public void damageByEntity(Entity entity, double damage) {
        if (entity instanceof LivingEntity livingEntity) damageByLivingEntity(livingEntity);
    }

    public boolean damageByProjectile(Projectile projectile) {
        double damage = 0;

        if (projectile.getShooter() != null && projectile.getShooter().equals(livingEntity)) return false;

        // 1) If it's an arrow, use its damage field and velocity
        if (projectile instanceof Arrow arrow) {
            // base: speed * damage‐multiplier
            double speed = arrow.getVelocity().length();           // blocks/tick
            damage = Math.ceil(speed * arrow.getDamage());         // round up

            // optional: add Power‐enchantment bonus from the bow that shot it
            if (arrow.getShooter() instanceof LivingEntity shooter) {
                ItemStack bow = arrow.getWeapon(); // or track last bow in metadata
                if (bow != null && bow.containsEnchantment(Enchantment.POWER)) {
                    int level = bow.getEnchantmentLevel(Enchantment.POWER);
                    // Power adds 25% per level, rounded up half‐heart increments
                    double bonus = Math.ceil(0.25 * (level + 1) * damage);
                    damage += bonus;
                }
            }
        }

        // 2) Dispatch to your normal damage handlers
        if (projectile.getShooter() instanceof LivingEntity damager) {
            damageByLivingEntity(damager, damage);
        } else {
            damageByEntity((Entity) projectile.getShooter(), damage);
        }
        return true;
    }

    public void showUnderlyingEntity(Player player) {
        if (livingEntity == null) return;
        player.showEntity(MetadataHandler.PLUGIN, livingEntity);
        livingEntity.setGlowing(true);
    }

    public void hideUnderlyingEntity(Player player) {
        if (livingEntity == null) return;
        player.hideEntity(MetadataHandler.PLUGIN, livingEntity);
        livingEntity.setGlowing(false);
    }

    public void teleport(Location location) {
        skeleton.teleport(location);
    }

    public void triggerLeftClickEvent(Player player) {
        ModeledEntityLeftClickEvent event = new ModeledEntityLeftClickEvent(player, this);
        Bukkit.getPluginManager().callEvent(event);
    }

    public void triggerRightClickEvent(Player player) {
        ModeledEntityRightClickEvent event = new ModeledEntityRightClickEvent(player, this);
        Bukkit.getPluginManager().callEvent(event);
    }
}