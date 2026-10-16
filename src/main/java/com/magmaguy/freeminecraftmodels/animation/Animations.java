package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.core.Bone;
import com.magmaguy.freeminecraftmodels.customentity.core.Skeleton;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
import lombok.Getter;

import java.util.HashMap;

public class Animations {
    @Getter
    private final HashMap<String, Animation> animations = new HashMap<>();

    public Animations(AnimationsBlueprint animationsBlueprint, Bone[] bones, Skeleton skeleton) {
        animationsBlueprint.getAnimations().forEach((key, value) -> animations.put(key, new Animation(value, bones, skeleton)));
    }
}
