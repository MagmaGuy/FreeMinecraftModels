package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.IKChain;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationFrame;
import com.magmaguy.freeminecraftmodels.dataconverter.IKAnimationFrame;
import lombok.Getter;

import java.util.HashMap;
import java.util.Map;

public class Animation {
    @Getter
    private final AnimationBlueprint animationBlueprint;
    // Frames per bone in the manager's bone order. A missing track stays null, so that bone rests.
    @Getter
    private final AnimationFrame[][] tracks;
    // IK animation frames keyed by IKChain instance
    @Getter
    private final HashMap<IKChain, IKAnimationFrame[]> ikAnimationFrames = new HashMap<>();

    public Animation(AnimationBlueprint animationBlueprint, Bone[] bones, Skeleton skeleton) {
        this.animationBlueprint = animationBlueprint;

        tracks = new AnimationFrame[bones.length][];
        for (int bone = 0; bone < bones.length; bone++)
            tracks[bone] = animationBlueprint.getAnimationFrames().get(bones[bone].getBoneBlueprint());

        // Map IK controller names to IKChain instances
        for (Map.Entry<String, IKAnimationFrame[]> entry : animationBlueprint.getIkAnimationFrames().entrySet()) {
            IKChain chain = skeleton.getIKChain(entry.getKey());
            if (chain != null) ikAnimationFrames.put(chain, entry.getValue());
        }
    }
}
