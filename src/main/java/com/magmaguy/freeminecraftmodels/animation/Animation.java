package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.IKChain;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationFrame;
import com.magmaguy.freeminecraftmodels.dataconverter.IKAnimationFrame;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public class Animation {
    @Getter
    private final AnimationBlueprint animationBlueprint;
    @Getter
    private final HashMap<Bone, AnimationFrame[]> animationFrames = new HashMap<>();
    // IK animation frames keyed by IKChain instance
    @Getter
    private final HashMap<IKChain, IKAnimationFrame[]> ikAnimationFrames = new HashMap<>();
    @Getter
    private int counter = 0;

    public void incrementCounter() {
        counter++;
    }

    public Animation(AnimationBlueprint animationBlueprint, ModeledEntity modeledEntity) {
        this.animationBlueprint = animationBlueprint;

        // Map bone blueprints to bone instances
        animationBlueprint.getAnimationFrames().forEach((key, value) -> {
            for (Bone bone : modeledEntity.getSkeleton().getBones())
                if (bone.getBoneBlueprint().equals(key)) {
                    animationFrames.put(bone, value);
                    break;
                }
        });
        modeledEntity.getSkeleton().getBones().forEach(bone -> {
            if (!animationFrames.containsKey(bone)) {
                animationFrames.put(bone, null);
            }
        });

        // Map IK controller names to IKChain instances
        for (Map.Entry<String, IKAnimationFrame[]> entry : animationBlueprint.getIkAnimationFrames().entrySet()) {
            String controllerName = entry.getKey();
            IKAnimationFrame[] frames = entry.getValue();

            IKChain chain = modeledEntity.getSkeleton().getIKChain(controllerName);
            if (chain != null) {
                ikAnimationFrames.put(chain, frames);
            }
        }
    }

    public void resetCounter() {
        counter = 0;
    }

    /**
     * Checks if this animation has any IK chains to animate.
     *
     * @return true if this animation has IK animations
     */
    public boolean hasIKAnimations() {
        return !ikAnimationFrames.isEmpty();
    }
}
