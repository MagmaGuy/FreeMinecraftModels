package com.magmaguy.freeminecraftmodels.dataconverter;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class Animations {
    @Getter
    private HashMap<String, Animation> animations = new HashMap<>();

    public Animations(List<Object> rawAnimationData, String modelName, Skeleton skeleton) {
        for (Object animation : rawAnimationData) {
            Animation animationObject =new Animation(animation, modelName, skeleton);
            animations.put(animationObject.getAnimationName(), animationObject);
        }
    }
}
