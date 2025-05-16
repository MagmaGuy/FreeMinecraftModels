package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.animation.AnimationManager;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBox;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class ModeledEntity {

    @Getter
    private static final HashSet<ModeledEntity> loadedModeledEntities = new HashSet<>();
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
    @Getter
    private SkeletonBlueprint skeletonBlueprint = null;
    @Getter
    private Skeleton skeleton;
    private AnimationManager animationManager = null;
    @Getter
    private OrientedBoundingBox obbHitbox = null;

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

    public static void shutdown() {
        loadedModeledEntities.clear();
    }

    private static boolean isNameTag(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(BoneBlueprint.nameTagKey, PersistentDataType.BYTE);
    }

    public OrientedBoundingBox getObbHitbox() {
        if (obbHitbox == null) {
            if (getSkeletonBlueprint().getHitbox() != null) {
                return obbHitbox = new OrientedBoundingBox(
                        getSkeleton().getCurrentLocation(),
                        getSkeletonBlueprint().getHitbox().getWidthX(),
                        getSkeletonBlueprint().getHitbox().getHeight(),
                        getSkeletonBlueprint().getHitbox().getWidthZ());
            } else {
                return obbHitbox = new OrientedBoundingBox(getSkeleton().getCurrentLocation(), 1, 2, 1);
            }
        } else return obbHitbox;
    }

    public void tick() {
        getSkeleton().transform();
        getObbHitbox().update(getLocation());
        if (animationManager != null)
            animationManager.tick();
        //overriden by extending classes
    }

    public Location getSpawnLocation() {
        return spawnLocation.clone();
    }

    protected void displayInitializer(Location targetLocation) {
        skeleton.generateDisplays(targetLocation);
    }

    public void spawn(Location location) {
        displayInitializer(location);
//        ModeledEntityOBBExtension.setOBBFromHitboxProperties(this);
        if (animationManager != null) animationManager.start();
    }

    /**
     * Plays an animation as set by the string name.
     *
     * @param animationName  Name of the animation - case-sensitive
     * @param blendAnimation If the animation should blend. If set to false, the animation passed will stop other animations.
     *                       If set to true, the animation will be mixed with any currently ongoing animations
     * @return Whether the animation successfully started playing.
     */
    public boolean playAnimation(String animationName, boolean blendAnimation) {
        return animationManager.playAnimation(animationName, blendAnimation);
    }

    public void spawn() {
        spawn(lastSeenLocation);
    }

    public void remove() {
        skeleton.remove();
        loadedModeledEntities.remove(this);
        if (livingEntity != null) livingEntity.remove();
//        ModeledEntityEvents.removeLoadedModeledEntity(this);
//        ModeledEntityEvents.removeUnloadedModeledEntity(this);
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

    public void damage(Player player, double damage) {
        //Overriden by extending classes
    }

    public void damage(Player player) {
        //Overriden by extending classes
    }

    public void teleport(Location location) {
        skeleton.teleport(location);
    }
}
