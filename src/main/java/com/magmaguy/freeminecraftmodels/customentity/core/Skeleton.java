package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.SkeletonBlueprint;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;

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
       return modeledEntity.getLocation();
    }

    public void generateDisplays() {
        rootBone.generateDisplay();
        boneMap.values().forEach(bone -> {
            if (bone.getBoneBlueprint().isNameTag()) nametags.add(bone);
        });
    }

    public void remove() {
        boneMap.values().forEach(Bone::remove);
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

    /**
     * This updates animations. The plugin runs this automatically, don't use it unless you know what you're doing!
     */
    public void tick(AbstractPacketBundle abstractPacketBundle) {
        skeletonWatchers.tick();

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
                    // done
                    tinting = false;
                    boneMap.values().forEach(b -> b.setHorseLeatherArmorColor(Color.WHITE));
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

        if (getSkeletonWatchers().hasObservers()) {
            rootBone.transform(abstractPacketBundle);
        }
    }

    public void tint() {
        // start (or restart) the tint animation
        tinting = true;
        tintCounter = 0;
    }
}
