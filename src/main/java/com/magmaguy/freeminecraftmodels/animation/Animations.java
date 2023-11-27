package com.magmaguy.freeminecraftmodels.animation;

import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.AnimationsBlueprint;
import lombok.Getter;

import java.util.HashMap;

public class Animations {
    @Getter
    private final HashMap<String, Animation> animations = new HashMap<>();
    private final AnimationsBlueprint animationBlueprint;
    public Animations(AnimationsBlueprint animationsBlueprint, ModeledEntity modeledEntity){
        this.animationBlueprint = animationsBlueprint;
        animationsBlueprint.getAnimations().forEach((key, value) -> animations.put(key, new Animation(value, modeledEntity)));
    }
}
