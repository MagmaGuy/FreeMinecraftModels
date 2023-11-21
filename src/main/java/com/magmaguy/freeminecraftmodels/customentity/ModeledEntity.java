package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.animation.AnimationManager;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.freeminecraftmodels.utils.ChunkHasher;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.List;

public class ModeledEntity {

    @Getter
    private final String entityID;
    @Getter
    private final String name = "default";
    private final Location spawnLocation;
    protected Integer chunkHash = null;
    AnimationManager animationManager;
    @Getter
    private SkeletonBlueprint skeletonBlueprint = null;
    @Getter
    private Location lastSeenLocation;
    @Getter
    private Skeleton skeleton;
    @Getter
    protected Entity entity = null;

    public ModeledEntity(String entityID, Location spawnLocation) {
        this.entityID = entityID;
        this.spawnLocation = spawnLocation;
        this.lastSeenLocation = spawnLocation;
        ModeledEntityEvents.addLoadedModeledEntity(this);
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return;
        skeletonBlueprint = fileModelConverter.getSkeletonBlueprint();
        skeleton = new Skeleton(skeletonBlueprint);
        if (fileModelConverter.getAnimationsBlueprint() != null)
            animationManager = new AnimationManager(this, fileModelConverter.getAnimationsBlueprint());
    }

    private static boolean isNameTag(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(BoneBlueprint.nameTagKey, PersistentDataType.BYTE);
    }

    protected void armorStandInitializer(Location targetLocation) {
        skeleton.generateDisplays(targetLocation);
    }

    public void spawn(Location location) {
        armorStandInitializer(location);
        animationManager.start();
    }

    /**
     * Plays an animation as set by the string name.
     *
     * @param animationName  Name of the animation - case-sensitive
     * @param blendAnimation If the animation should blend. If set to false, the animation passed will stop other animations.
     *                      If set to true, the animation will be mixed with any currently ongoing animations
     * @return Whether the animation successfully started playing.
     */
    public boolean playAnimation(String animationName, boolean blendAnimation) {
        return animationManager.playAnimation(animationName, blendAnimation);
    }

    public void loadChunk() {
        spawn();
    }

    public void spawn() {
        spawn(lastSeenLocation);
    }


    public void remove() {
        skeleton.remove();
        if (entity != null) entity.remove();
        ModeledEntityEvents.removeLoadedModeledEntity(this);
        ModeledEntityEvents.removeUnloadedModeledEntity(this);
        terminateAnimation();
    }

    private void terminateAnimation() {
        if (animationManager != null) animationManager.stop();
    }

    public void unloadChunk() {
        lastSeenLocation = getLocation();
        skeleton.remove();
        terminateAnimation();
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

    /**
     * This just moves the static entity over by the set amount in the vector.
     *
     * @param vector Vector to be added to the location of the static entity
     */
    public void move(Vector vector) {
//        armorStandList.forEach(armorStand -> armorStand.teleport(armorStand.getLocation().add(vector)));
    }

    public Location getLocation() {
        return spawnLocation;
    }

    public int getChunkHash() {
        if (chunkHash == null) return ChunkHasher.hash(getLocation());
        return chunkHash;
    }
}
