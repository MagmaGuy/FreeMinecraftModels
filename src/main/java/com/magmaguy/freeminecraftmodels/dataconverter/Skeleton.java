package com.magmaguy.freeminecraftmodels.dataconverter;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Skeleton {
    private Bone hitbox;
    @Getter
    private final List<Bone> mainModel = new ArrayList<>();

    public Skeleton(double projectResolution,
                    List outlinerJSON,
                    HashMap<String, Object> values,
                    Map<String, Map<String, Object>> textureReferences,
                    String modelName,
                    String pathName) {
        for (int i = 0; i < outlinerJSON.size(); i++) {
            if (!(outlinerJSON.get(i) instanceof Map<?, ?>)) continue;
            Map<String, Object> bone = (Map<String, Object>) outlinerJSON.get(i);
            if (((String) bone.get("name")).equalsIgnoreCase("hitbox"))
                hitbox = new Bone(projectResolution, bone, values, textureReferences, modelName, null);
            else
                mainModel.add(new Bone(projectResolution, bone, values, textureReferences, modelName, null));
        }
    }
}
