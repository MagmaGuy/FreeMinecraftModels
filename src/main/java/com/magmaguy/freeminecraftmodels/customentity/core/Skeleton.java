package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import lombok.Getter;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

public class Skeleton {

    @Getter
    private final List<BoneBlueprint> mainModel = new ArrayList<>();
    //In BlockBench models are referred to by name for animations, and names are unique
    @Getter
    private final HashMap<String, Bone> boneMap = new HashMap<>();
    private final SkeletonBlueprint skeletonBlueprint;
    private Location currentLocation = null;
    private BoneBlueprint hitbox;

    public Skeleton(SkeletonBlueprint skeletonBlueprint) {
        this.skeletonBlueprint = skeletonBlueprint;
        skeletonBlueprint.getBoneMap().forEach((key, value) -> {
            if (value.getParent() == null) {
                Developer.debug("1");
                Bone bone = new Bone(value, null, this);
                boneMap.put(key, bone);
                bone.getAllChildren(boneMap);
            }
        });
    }

    public Location getCurrentLocation() {
        return currentLocation.clone();
    }

    public void generateDisplays(Location location) {
        currentLocation = location;
        boneMap.values().forEach(bone -> bone.generateDisplay(location));
    }

    public void remove() {
        boneMap.values().forEach(Bone::remove);
    }

    /**
     * Used to set the name over nameable bones
     *
     * @param name The name to set over the bone
     */
    public void setName(String name) {
        boneMap.values().forEach(bone -> bone.setName(name));
    }

    /**
     * Used to make names over nameable bones visible
     *
     * @param visible Whether the name should be visible
     */
    public void setNameVisible(boolean visible) {
        boneMap.values().forEach(bone -> bone.setNameVisible(visible));
    }

    public List<ArmorStand> getNametags() {
        List<ArmorStand> nametags = new ArrayList<>();
        boneMap.values().forEach(bone -> bone.getNametags(nametags));
        return nametags;
    }

    /**
     * Returns the list of bones the Skeleton has
     *
     * @param deep If set to true, this will return every single bone the entity has. Otherwise, it will only return to top-level bones, and the children of those bones can be accessed in the bones.
     * @return
     */
    public Collection<Bone> getBones() {
        return boneMap.values();
    }

    /**
     * This updates animations. The plugin runs this automatically, don't use it unless you know what you're doing!
     */
    public void transform() {
        boneMap.values().forEach(bone-> {
            if (bone.getBoneBlueprint().getParent() == null)
                bone.transform();
        });
    }
}
