package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.annotation.Nullable;
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
    @Getter
    private final SkeletonBlueprint skeletonBlueprint;
    @Getter
    private final SkeletonWatchers skeletonWatchers;
    private final List<Bone> nametags = new ArrayList<>();
    @Setter
    private Location currentLocation = null;
    @Getter
    @Setter
    private float currentHeadPitch = 0;
    @Getter
    @Setter
    private float currentHeadYaw = 0;
    private BukkitTask damageTintTask = null;
    @Getter
    @Setter
    private DynamicEntity dynamicEntity = null;
    @Getter
    @Setter
    private ModeledEntity modeledEntity = null;
    private Bone rootBone = null;

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
    }

    @Nullable
    public Location getCurrentLocation() {
        if (currentLocation == null) return null;
        return currentLocation.clone();
    }

    public void generateDisplays(Location location) {
        currentLocation = location;
        rootBone.generateDisplay();
        boneMap.values().forEach(bone -> {
            if (bone.getBoneBlueprint().isNameTag()) nametags.add(bone);
        });
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
     * Returns the map of bones the Skeleton has
     *
     * @return
     */
    public Collection<Bone> getBones() {
        return boneMap.values();
    }

    /**
     * This updates animations. The plugin runs this automatically, don't use it unless you know what you're doing!
     */
    public void transform() {
        skeletonWatchers.tick();
        if (getSkeletonWatchers().hasObservers()) rootBone.transform();
    }

    public void tint() {
        if (damageTintTask != null) damageTintTask.cancel();
        damageTintTask = new BukkitRunnable() {
            int counter = 0;

            @Override
            public void run() {
                counter++;
                if (counter > 10) {
                    if (!modeledEntity.isDying()) {
                        cancel();
                        boneMap.values().forEach(bone -> bone.setHorseLeatherArmorColor(Color.WHITE));
                        return;
                    } else {
                        if (modeledEntity.isRemoved()) {
                            cancel();
                            return;
                        }
                        if (counter % 5 == 0) {
                            boneMap.values().forEach(bone -> bone.spawnParticles(Particle.POOF, .1));
                        }
                    }
                }
                boneMap.values().forEach(bone -> bone.setHorseLeatherArmorColor(Color.fromRGB(255, (int) (255 / (double) counter), (int) (255 / (double) counter))));
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
    }

    public void teleport(Location location) {
        currentLocation = location;
        rootBone.teleport();
    }
}
