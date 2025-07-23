package com.magmaguy.freeminecraftmodels.dataconverter;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SkeletonBlueprint {
    //In BlockBench models are referred to by name for animations, and names are unique
    @Getter
    private final HashMap<String, BoneBlueprint> boneMap = new HashMap<>();
    @Getter
    private final List<BoneBlueprint> mainModel = new ArrayList<>();
    @Getter
    private String modelName = "Default Name";
    @Getter
    private HitboxBlueprint hitbox;

    public SkeletonBlueprint(List<ParsedTexture> parsedTextures,
                             List outlinerJSON,
                             HashMap<String, Object> values,
                             Map<String, Map<String, Object>> textureReferences,
                             String modelName,
                             String pathName) {
        this.modelName = modelName;

        //Create a root bone for everything
        BoneBlueprint rootBone = new BoneBlueprint(modelName, null, this);
        List<BoneBlueprint> rootChildren = new ArrayList<>();

        for (int i = 0; i < outlinerJSON.size(); i++) {
            if (!(outlinerJSON.get(i) instanceof Map<?, ?>)) continue;
            Map<String, Object> bone = (Map<String, Object>) outlinerJSON.get(i);
            if (((String) bone.get("name")).equalsIgnoreCase("hitbox"))
                hitbox = new HitboxBlueprint(bone, values, modelName, null);
            else {
                BoneBlueprint boneBlueprint = new BoneBlueprint(parsedTextures, bone, values, textureReferences, modelName, rootBone, this);
                rootChildren.add(boneBlueprint);
                if (boneBlueprint.getMetaBone() != null)
                    rootChildren.add(boneBlueprint.getMetaBone());
            }
        }

        rootBone.setBoneBlueprintChildren(rootChildren);
        mainModel.add(rootBone);
    }
}
