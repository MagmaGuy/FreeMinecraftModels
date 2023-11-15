package com.magmaguy.freeminecraftmodels.dataconverter;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkeletonBlueprint {
    private BoneBlueprint hitbox;
    @Getter
    private final List<BoneBlueprint> mainModel = new ArrayList<>();
    //In BlockBench models are referred to by name for animations, and names are unique
    @Getter
    private HashMap<String, BoneBlueprint> boneMap = new HashMap<>();

    public SkeletonBlueprint(double projectResolution,
                             List outlinerJSON,
                             HashMap<String, Object> values,
                             Map<String, Map<String, Object>> textureReferences,
                             String modelName,
                             String pathName) {
        for (int i = 0; i < outlinerJSON.size(); i++) {
            if (!(outlinerJSON.get(i) instanceof Map<?, ?>)) continue;
            Map<String, Object> bone = (Map<String, Object>) outlinerJSON.get(i);
            if (((String) bone.get("name")).equalsIgnoreCase("hitbox"))
                hitbox = new BoneBlueprint(projectResolution, bone, values, textureReferences, modelName, null, this);
            else
                mainModel.add(new BoneBlueprint(projectResolution, bone, values, textureReferences, modelName, null, this));
        }
    }
}
