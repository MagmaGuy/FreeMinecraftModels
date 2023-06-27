package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.Bone;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ModelsFolder {
    private static int counter;

    public static void initializeConfig() {
        counter = 1;
        File file = ConfigurationEngine.directoryCreator("models");
        if (!file.exists()) {
            Developer.warn("Failed to create models directory!");
            return;
        }

        if (!file.isDirectory()) {
            Developer.warn("Directory models was not a directory!");
            return;
        }

        Gson gson = new Gson();
        List<FileModelConverter> bbModelConverterList = new ArrayList<>();
        HashMap<String, Object> leatherHorseArmor = new HashMap<>();
        leatherHorseArmor.put("parent", "item/generated");
        leatherHorseArmor.put("textures", Collections.singletonMap("layer0", "minecraft:item/leather_horse_armor"));

        for (File childFile : file.listFiles()) {
            FileModelConverter bbModelConverter = new FileModelConverter(childFile);
            bbModelConverterList.add(bbModelConverter);

            for (Bone bone : bbModelConverter.getSkeleton().getMainModel())
                if (!bone.getBoneName().equals("hitbox") && !bone.getBoneName().equals("tag_name") && !bone.getCubeChildren().isEmpty())
                    assignBoneModelID(leatherHorseArmor, bone);
        }

        leatherHorseArmor.put("data", counter - 1);

        try {
            FileUtils.writeStringToFile(
                    new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output"
                            + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar +
                            "minecraft" + File.separatorChar + "models" + File.separatorChar + "item" + File.separatorChar
                            + "leather_horse_armor.json"),
                    gson.toJson(leatherHorseArmor), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Developer.warn("Failed to generate the iron horse armor file!");
            throw new RuntimeException(e);
        }
    }

    private static void assignBoneModelID(HashMap<String, Object> ironHorseArmorFile, Bone bone) {
        Map<String, Object> entryMap = new HashMap<>();
        entryMap.put("predicate", Collections.singletonMap("custom_model_data", counter));
        bone.setModelID(counter);
        entryMap.put("model", bone.getBoneName().toLowerCase());
        ironHorseArmorFile.computeIfAbsent("overrides", k -> new ArrayList<Map<String, Object>>());
        List<Map<String, Object>> existingList = ((List<Map<String, Object>>) ironHorseArmorFile.get("overrides"));
        existingList.add(entryMap);
        ironHorseArmorFile.put("overrides", existingList);
        counter++;
        if (!bone.getBoneChildren().isEmpty())
            for (Bone childBone : bone.getBoneChildren())
                assignBoneModelID(ironHorseArmorFile, childBone);
    }
}
