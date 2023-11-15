package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public class AnimationManager {
    private static List<AnimationManager> loadedAnimations;
    private static List<AnimationManager> unloadedAnimations;
    private Animation idleAnimation = null;
    private final Animations animations;

    private BukkitTask clock = null;
    private final ModeledEntity modeledEntity;

    public AnimationManager(ModeledEntity modeledEntity, AnimationsBlueprint animationsBlueprint) {
        this.modeledEntity = modeledEntity;
        this.animations = new Animations(animationsBlueprint, modeledEntity);

        idleAnimation = animations.getAnimations().get("idle");
        if (idleAnimation != null) {
            clock = new BukkitRunnable() {
                int counter = 0;

                @Override
                public void run() {
                    int adjustedAnimationPosition = (int) (counter - Math.floor(counter / (double) idleAnimation.getAnimationBlueprint().getDuration()) * idleAnimation.getAnimationBlueprint().getDuration());

                    idleAnimation.getAnimationFrames().entrySet().forEach(boneEntry -> {
                        boneEntry.getKey().rotateTo(
                                boneEntry.getValue()[adjustedAnimationPosition].xRotation,
                                boneEntry.getValue()[adjustedAnimationPosition].yRotation,
                                boneEntry.getValue()[adjustedAnimationPosition].zRotation);
                    });

                    idleAnimation.getAnimationFrames().entrySet().forEach(boneEntry -> {
                        boneEntry.getKey().translateTo(
                                boneEntry.getValue()[adjustedAnimationPosition].xPosition,
                                boneEntry.getValue()[adjustedAnimationPosition].yPosition,
                                boneEntry.getValue()[adjustedAnimationPosition].zPosition);
                    });

                    modeledEntity.getSkeleton().transform();

//                    idleAnimation.getAnimationFrames().entrySet().forEach(boneEntry -> {
//                        boneEntry.getKey().transform();
//                    });
                    counter++;
                }
            }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);
        }
    }

    public void stop() {
        if (clock != null) clock.cancel();
    }
}
