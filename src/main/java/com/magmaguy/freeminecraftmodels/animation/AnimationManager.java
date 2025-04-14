package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
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
        jumpAnimation = animations.getAnimations().get("jump");
        deathAnimation = animations.getAnimations().get("death");
        spawnAnimation = animations.getAnimations().get("spawn");
    }

    private static int getAdjustedAnimationPosition(Animation animation) {
        int adjustedAnimationPosition;
        if (animation.getCounter() >= animation.getAnimationBlueprint().getDuration() && animation.getAnimationBlueprint().getLoopType() == LoopType.HOLD)
            //Case where the animation is technically over but also is set to hold
            adjustedAnimationPosition = animation.getAnimationBlueprint().getDuration() - 1;
        else {
            //Normal case, looping
            adjustedAnimationPosition = (int) (animation.getCounter() - Math.floor(animation.getCounter() / (double) animation.getAnimationBlueprint().getDuration()) * animation.getAnimationBlueprint().getDuration());
        }
        return adjustedAnimationPosition;
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

        clock = new BukkitRunnable() {
            @Override
            public void run() {
                updateStates();
                states.forEach(animation -> playAnimationFrame(animation));
//                modeledEntity.getSkeleton().transform(); todo: check if this causes a big problem, it moved to the dynamic entity instead
            }
        }.runTaskTimer(MetadataHandler.PLUGIN, 0, 1);

    }

    private void updateStates() {
        if (modeledEntity.getLivingEntity() == null) return;
        if (modeledEntity.getLivingEntity().isDead()) {
            if (deathAnimation != null) {
                animationGracePeriod = true;
                overrideStates(deathAnimation);
                return;
            } else
                modeledEntity.remove();
        }
        if (animationGracePeriod) return;
        if (jumpAnimation != null && !modeledEntity.getLivingEntity().isOnGround()) {
            overrideStates(jumpAnimation);
            return;
        }
        if (walkAnimation != null && modeledEntity.getLivingEntity().getVelocity().length() > .08) {
            overrideStates(walkAnimation);
            return;
        }
        if (idleAnimation != null) overrideStates(idleAnimation);
        //Jump
        //if (!modeledEntity.getEntity().isDead())
    }

    private void overrideStates(Animation animation) {
//        if (animation == null) return;
        if (!states.contains(animation)) {
            states.clear();
            animation.resetCounter();
            states.add(animation);
        }
    }

    private BukkitTask graceResetTask = null;

    public boolean playAnimation(String animationName, boolean blendAnimation) {
        Animation animation = animations.getAnimations().get(animationName);
        if (animation == null) return false;
        if (graceResetTask != null) graceResetTask.cancel();
        if (!blendAnimation) {
            states.clear();
            animationGracePeriod = true;
            graceResetTask = new BukkitRunnable() {
                @Override
                public void run() {
                    animationGracePeriod = false;
                }
            }.runTaskLater(MetadataHandler.PLUGIN, animation.getAnimationBlueprint().getDuration());
        }
        animation.resetCounter();
        states.add(animation);
        return true;
    }

    private void playAnimationFrame(Animation animation) {
        if (!animation.getAnimationBlueprint().getLoopType().equals(LoopType.LOOP) && animation.getCounter() >= animation.getAnimationBlueprint().getDuration()) {
            //Case where the animation doesn't loop, and it's over
            states.remove(animation);
            if (animation == deathAnimation)
                modeledEntity.remove();
            return;
        }
        int adjustedAnimationPosition = getAdjustedAnimationPosition(animation);
        //Handle rotations
        animation.getAnimationFrames().forEach((key, value) -> {
            if (value == null)
                key.updateAnimationRotation(0, 0, 0);
            else
                key.updateAnimationRotation(
                        value[adjustedAnimationPosition].xRotation,
                        value[adjustedAnimationPosition].yRotation,
                        value[adjustedAnimationPosition].zRotation);
        });

        //Handle translations
        animation.getAnimationFrames().forEach((key, value) -> {
            if (value == null)
                key.updateAnimationTranslation(0, 0, 0);
            else
                key.updateAnimationTranslation(
                        value[adjustedAnimationPosition].xPosition,
                        value[adjustedAnimationPosition].yPosition,
                        value[adjustedAnimationPosition].zPosition);
        });

        //Handle scale
        animation.getAnimationFrames().forEach((key, value) -> {
            if (value == null)
                key.updateAnimationScale(1);
            else
                key.updateAnimationScale(value[adjustedAnimationPosition].scale);
        });

        animation.incrementCounter();
    }

    public void stop() {
        states.clear();
        animationGracePeriod = false;
    }

    public boolean hasAnimation(String animationName) {
        return animations.getAnimations().containsKey(animationName);
    }

    public void end() {
        if (clock != null) clock.cancel();
    }
}
