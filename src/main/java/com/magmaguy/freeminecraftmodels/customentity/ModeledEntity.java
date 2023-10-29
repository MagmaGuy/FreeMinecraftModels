package com.magmaguy.freeminecraftmodels.customentity;

import com.magmaguy.freeminecraftmodels.animation.AnimationManager;
import com.magmaguy.freeminecraftmodels.dataconverter.Bone;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.Skeleton;
import com.magmaguy.freeminecraftmodels.utils.ChunkHasher;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

public class ModeledEntity {

    @Getter
    private final String entityID;
    private final ArrayList<ArmorStand> armorStandList = new ArrayList<>();
    @Getter
    private final String name = "default";
    private final Location spawnLocation;
    protected Integer chunkHash = null;
    AnimationManager animationManager;
    @Getter
    private Skeleton skeleton = null;
    @Getter
    private Location lastSeenLocation;

    public ModeledEntity(String entityID, Location spawnLocation) {
        this.entityID = entityID;
        this.spawnLocation = spawnLocation;
        this.lastSeenLocation = spawnLocation;
        ModeledEntityEvents.addLoadedModeledEntity(this);
        FileModelConverter fileModelConverter = FileModelConverter.getConvertedFileModels().get(entityID);
        if (fileModelConverter == null) return;
        skeleton = fileModelConverter.getSkeleton();
        if (fileModelConverter.getAnimations() != null) animationManager = new AnimationManager(this, fileModelConverter.getAnimations());
    }

    private static boolean isNameTag(ArmorStand armorStand) {
        return armorStand.getPersistentDataContainer().has(Bone.nameTagKey, PersistentDataType.BYTE);
    }

    protected void armorStandInitializer(Location targetLocation, Bone bone) {
        if (!bone.getCubeChildren().isEmpty() || bone.isNameTag()) {
            ArmorStand armorStand = bone.generateDisplay(targetLocation);
            armorStandList.add(armorStand);
            if (bone.isNameTag()) armorStand.setMarker(false);
        }
        if (!bone.getBoneChildren().isEmpty())
            bone.getBoneChildren().forEach(boneChild -> armorStandInitializer(targetLocation, boneChild));
    }

    public void spawn(Location location) {
        skeleton.getMainModel().forEach(bone -> armorStandInitializer(location, bone));
    }

    public void loadChunk() {
        spawn();
    }

    public void spawn() {
        spawn(lastSeenLocation);
    }


    public void remove() {
        for (ArmorStand armorStand : armorStandList) armorStand.remove();
        armorStandList.clear();
        ModeledEntityEvents.removeLoadedModeledEntity(this);
        ModeledEntityEvents.removeUnloadedModeledEntity(this);
        terminateAnimation();
    }

    private void terminateAnimation(){
        if (animationManager !=null) animationManager.stop();
    }

    public void unloadChunk() {
        lastSeenLocation = getLocation();
        for (ArmorStand armorStand : armorStandList) armorStand.remove();
        armorStandList.clear();
        terminateAnimation();
    }

    /**
     * Sets the custom name that is visible in-game on the entity
     *
     * @param name Name to set
     */
    public void setName(String name) {
        armorStandList.forEach(armorStand -> {
            if (isNameTag(armorStand))
                armorStand.setCustomName(name);
        });
    }

    /**
     * Default is false
     *
     * @param display Sets whether the name is visible
     */
    public void setNameVisible(boolean display) {
        armorStandList.forEach(armorStand -> {
            if (isNameTag(armorStand))
                armorStand.setCustomNameVisible(display);
        });
    }

    /**
     * Returns the name tag locations. Useful if you want to add more text above or below them.
     * Not currently guaranteed to be the exact location.
     *
     * @return
     */
    public List<Location> getNameTagLocations() {
        List<Location> locations = new ArrayList<>();
        armorStandList.forEach(armorStand -> {
            if (isNameTag(armorStand)) locations.add(armorStand.getLocation().add(new Vector(0, 1.9875, 0)));
        });
        return locations;
    }

    /**
     * This just moves the static entity over by the set amount in the vector.
     *
     * @param vector Vector to be added to the location of the static entity
     */
    public void move(Vector vector) {
        armorStandList.forEach(armorStand -> armorStand.teleport(armorStand.getLocation().add(vector)));
    }

    public Location getLocation() {
        return spawnLocation;
    }

    public int getChunkHash() {
        if (chunkHash == null) return ChunkHasher.hash(getLocation());
        return chunkHash;
    }
}
