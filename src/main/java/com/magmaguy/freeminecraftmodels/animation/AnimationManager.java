package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import com.magmaguy.freeminecraftmodels.utils.LoopType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashSet;
import java.util.List;

public class AnimationManager {
    private static List<AnimationManager> loadedAnimations;
    private static List<AnimationManager> unloadedAnimations;
    private final Animations animations;
    private final ModeledEntity modeledEntity;
    private final HashSet<Animation> states = new HashSet<>();
    //There are some animation defaults that will activate automatically so long as the animations are adequately named
    private Animation idleAnimation = null;
    private Animation attackAnimation = null;
    private Animation walkAnimation = null;
    private Animation jumpAnimation = null;
    private Animation deathAnimation = null;
    private Animation spawnAnimation = null;
    private BukkitTask clock = null;
    //This one is used for preventing default animations other than death from playing for as long as it is true
    private boolean animationGracePeriod = false;

    public AnimationManager(ModeledEntity modeledEntity, AnimationsBlueprint animationsBlueprint) {
        this.modeledEntity = modeledEntity;
        this.animations = new Animations(animationsBlueprint, modeledEntity);

        idleAnimation = animations.getAnimations().get("idle");
        attackAnimation = animations.getAnimations().get("attack");
        walkAnimation = animations.getAnimations().get("walk");
        Developer.debug("walk is nul " + (walkAnimation == null));
        jumpAnimation = animations.getAnimations().get("jump");
        deathAnimation = animations.getAnimations().get("death");
        spawnAnimation = animations.getAnimations().get("spawn");
    }

    public void start() {
        if (spawnAnimation != null) {
            states.add(spawnAnimation);
            if (idleAnimation != null)
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        animationGracePeriod = false;
                    }
                }.runTaskLater(MetadataHandler.PLUGIN, spawnAnimation.getAnimationBlueprint().getDuration());
        } else if (idleAnimation != null) states.add(idleAnimation);

        new BukkitRunnable() {
            @Override
            public void run() {
                playAnimationFrame(idleAnimation);
                modeledEntity.getSkeleton().transform(false);
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);

        clock = new BukkitRunnable() {
            @Override
            public void run() {
                updateStates();
                states.forEach(animation -> playAnimationFrame(animation));
                modeledEntity.getSkeleton().transform(false);
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);

    }

    private void updateStates() {
        if (modeledEntity.getEntity() == null) return;
        if (deathAnimation != null && modeledEntity.getEntity().isDead()) {
            animationGracePeriod = true;
            overrideStates(deathAnimation);
            return;
        }
        if (animationGracePeriod) return;
        if (jumpAnimation != null && !modeledEntity.getEntity().isOnGround()) {
            overrideStates(jumpAnimation);
            return;
        }
        if (walkAnimation != null && modeledEntity.getEntity().getVelocity().length() > .08) {
            overrideStates(walkAnimation);
            return;
        }
        overrideStates(idleAnimation);
        //Jump
        //if (!modeledEntity.getEntity().isDead())
    }

    private void overrideStates(Animation animation) {
        if (!states.contains(animation)) {
            states.clear();
            animation.resetCounter();
            states.add(animation);
        }
    }

    public boolean playAnimation(String animationName, boolean blendAnimation) {
        Animation animation = animations.getAnimations().get(animationName);
        if (animation == null) return false;
        if (!blendAnimation) states.clear();
        animation.resetCounter();
        states.add(animation);
        animationGracePeriod = true;
        new BukkitRunnable() {
            @Override
            public void run() {
                animationGracePeriod = false;
            }
        }.runTaskLater(MetadataHandler.PLUGIN, animation.getAnimationBlueprint().getDuration());
        return true;
    }

    private void playAnimationFrame(Animation animation) {
        //Case where the animation doesn't loop, and it's over
        if (!animation.getAnimationBlueprint().getLoopType().equals(LoopType.LOOP) && animation.getCounter() >= animation.getAnimationBlueprint().getDuration()) {
            states.remove(animation);
            return;
        }
        int adjustedAnimationPosition;
        if (animation.getCounter() >= animation.getAnimationBlueprint().getDuration() && animation.getAnimationBlueprint().getLoopType() == LoopType.HOLD)
            //Case where the animation is technically over but also is set to hold
            adjustedAnimationPosition = animation.getAnimationBlueprint().getDuration() - 1;
        else
            //Normal case
            adjustedAnimationPosition = (int) (animation.getCounter() - Math.floor(animation.getCounter() / (double) idleAnimation.getAnimationBlueprint().getDuration()) * idleAnimation.getAnimationBlueprint().getDuration());

        //Handle rotations
        animation.getAnimationFrames().entrySet().forEach(boneEntry -> {
            boneEntry.getKey().rotateTo(
                    boneEntry.getValue()[adjustedAnimationPosition].xRotation,
                    boneEntry.getValue()[adjustedAnimationPosition].yRotation,
                    boneEntry.getValue()[adjustedAnimationPosition].zRotation);
        });

        //Handle translations
        animation.getAnimationFrames().entrySet().forEach(boneEntry -> {
            boneEntry.getKey().translateTo(
                    boneEntry.getValue()[adjustedAnimationPosition].xPosition,
                    boneEntry.getValue()[adjustedAnimationPosition].yPosition,
                    boneEntry.getValue()[adjustedAnimationPosition].zPosition);
        });

        animation.setCounter(animation.getCounter() + 1);
    }

    public void stop() {
        if (clock != null) clock.cancel();
    }
}
