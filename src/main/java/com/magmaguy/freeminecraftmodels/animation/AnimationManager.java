package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.Animation;
import com.magmaguy.freeminecraftmodels.dataconverter.Animations;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public class AnimationManager {
    private static List<AnimationManager> loadedAnimations;
    private static List<AnimationManager> unloadedAnimations;
    private final Animations animations;
    private Animation idleAnimation = null;

    private BukkitTask clock = null;

    public void stop(){
        if (clock !=null) clock.cancel();
    }

    public AnimationManager(ModeledEntity modeledEntity, Animations animations) {
        this.animations = animations;
        idleAnimation = animations.getAnimations().get("idle");
        if (idleAnimation != null) {
            clock = new BukkitRunnable() {
                int counter = 0;

                @Override
                public void run() {
                    int adjustedAnimationPosition = (int)(counter - Math.floor(counter / (double) idleAnimation.getDuration()) * idleAnimation.getDuration());

                    int finalAdjustedAnimationPosition = adjustedAnimationPosition;
                    idleAnimation.getAnimationFrames().entrySet().forEach(boneEntry -> {
                        boneEntry.getKey().rotateTo(
                                boneEntry.getValue()[finalAdjustedAnimationPosition].xRotation,
                                boneEntry.getValue()[finalAdjustedAnimationPosition].yRotation,
                                boneEntry.getValue()[finalAdjustedAnimationPosition].zRotation);
                    });

                    idleAnimation.getAnimationFrames().entrySet().forEach(boneEntry -> {
                        boneEntry.getKey().translateTo(
                                boneEntry.getValue()[finalAdjustedAnimationPosition].xPosition,
                                boneEntry.getValue()[finalAdjustedAnimationPosition].yPosition,
                                boneEntry.getValue()[finalAdjustedAnimationPosition].zPosition);
                    });

                    idleAnimation.getAnimationFrames().entrySet().forEach(boneEntry -> {
                        boneEntry.getKey().transform();
                    });
                    counter++;
                }
            }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
        }
    }


}
