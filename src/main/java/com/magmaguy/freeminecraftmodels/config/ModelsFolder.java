package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.VersionChecker;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class ModelsFolder {
    private static int counter;
    private static int folderCounter;

    public static void initializeConfig() {
        counter = 1;
        folderCounter = 50;

        File file = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "models");

        if (!file.exists()) {
            file.mkdirs();
            file.mkdir();
        }

        if (!file.exists()) {
            Logger.warn("Failed to create models directory!");
            return;
        }

        if (!file.isDirectory()) {
            Logger.warn("Directory models was not a directory!");
            return;
        }

        if (VersionChecker.serverVersionOlderThan(21, 4))
            legacyHorseArmorGeneration(file);
        else
            newModelGeneration(file);
    }

    /**
     * In the old file generation, a horse armor file just had a series of numbers reserved for the different IDs of the different models
     */
    private static void legacyHorseArmorGeneration(File file) {
        Gson gson = new Gson();
        List<FileModelConverter> bbModelConverterList = new ArrayList<>();
        HashMap<String, Object> leatherHorseArmor = new HashMap<>();
        leatherHorseArmor.put("parent", "item/generated");
        leatherHorseArmor.put("textures", Collections.singletonMap("layer0", "minecraft:item/leather_horse_armor"));

        processFolders(file, bbModelConverterList, leatherHorseArmor, true);
        leatherHorseArmor.put("data", counter - 1 + folderCounter * 1000);

        try {
            FileUtils.writeStringToFile(
                    new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output"
                            + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar +
                            "minecraft" + File.separatorChar + "models" + File.separatorChar + "item" + File.separatorChar
                            + "leather_horse_armor.json"),
                    gson.toJson(leatherHorseArmor), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Failed to generate the iron horse armor file!");
            throw new RuntimeException(e);
        }
    }

    /**
     * In the new file generation, each model can be its own file, and referenced by namespace and name
     *
     * @param file
     */
    private static void newModelGeneration(File file) {
        //Items holds the item model definition, which will be used as the reference for what the namespaces and names are
        //and then point to where the actual json models are
        File itemModelsFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
                File.separatorChar + "output" +
                File.separatorChar + "FreeMinecraftModels" +
                File.separatorChar + "assets" +
                File.separatorChar + "freeminecraftmodels" +
                File.separatorChar + "items");
        if (!itemModelsFolder.exists()) itemModelsFolder.mkdir();

        List<FileModelConverter> bbModelConverterList = new ArrayList<>();
        HashMap<String, Object> jsonConfig = new HashMap<>();
        processFolders(file, bbModelConverterList, jsonConfig, true);

        HashMap<String, List<FileModelConverter>> mappedModels = new HashMap<>();
        for (FileModelConverter model : bbModelConverterList) {
            String modelName = model.getID();
            mappedModels.computeIfAbsent(modelName, k -> new ArrayList<>()).add(model);
        }

        for (Map.Entry<String, List<FileModelConverter>> entry : mappedModels.entrySet()) {
            String modelName = entry.getKey();
            List<FileModelConverter> models = entry.getValue();
            File modelFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
                    File.separatorChar + "output" +
                    File.separatorChar + "FreeMinecraftModels" +
                    File.separatorChar + "assets" +
                    File.separatorChar + "freeminecraftmodels" +
                    File.separatorChar + "items" +
                    File.separatorChar + modelName);
            modelFolder.mkdir();
            for (FileModelConverter fileModelConverter : models) {
                for (BoneBlueprint boneBlueprint : fileModelConverter.getSkeletonBlueprint().getBoneMap().values()) {
                    if (boneBlueprint.getBoneName().contains("freeminecraftmodels_autogenerated_root")) continue;
                    HashMap<String, Object> modelJson = new HashMap<>();

                    HashMap<String, Object> modelContentsJson = new HashMap<>();
                    modelContentsJson.put("tints", List.of(Map.of("type", "minecraft:custom_model_data", "index", 0, "default", 0xFFFFFF)));
                    modelContentsJson.put("type", "minecraft:model");
                    modelContentsJson.put("model", "freeminecraftmodels:" + boneBlueprint.getBoneName().split(":")[1]);

                    modelJson.put("model", modelContentsJson);

                    Gson gson = new Gson();
                    try {
                        FileUtils.writeStringToFile(
                                new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
                                        File.separatorChar + "output" +
                                        File.separatorChar + "FreeMinecraftModels" +
                                        File.separatorChar + "assets" +
                                        File.separatorChar + "freeminecraftmodels" +
                                        File.separatorChar + "items" +
                                        File.separatorChar + boneBlueprint.getBoneName().split(":")[1] + ".json"),
                                gson.toJson(modelJson), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

    }

    private static void processFiles(File childFile,
                                     List<FileModelConverter> bbModelConverterList,
                                     HashMap<String, Object> leatherHorseArmor) {
        try {
            FileModelConverter bbModelConverter = new FileModelConverter(childFile);
            bbModelConverterList.add(bbModelConverter);
            for (BoneBlueprint boneBlueprint : bbModelConverter.getSkeletonBlueprint().getMainModel())
                if (!boneBlueprint.getBoneName().equals("hitbox") &&
                        !boneBlueprint.getBoneName().equals("tag_name") &&
                        !boneBlueprint.getBoneName().equals("freeminecraftmodels_autogenerated_root"))
                    assignBoneModelID(leatherHorseArmor, boneBlueprint);
        } catch (Exception e) {
            Logger.warn("Failed to parse model " + childFile.getName() + "! Warn the developer about this");
            e.printStackTrace();
        }
    }

    private static void processFolders(File file,
                                       List<FileModelConverter> bbModelConverterList,
                                       HashMap<String, Object> leatherHorseArmor,
                                       boolean firstLevel) {
        if (!firstLevel) folderCounter++;
        File[] modelFiles = file.listFiles();
        Arrays.sort(modelFiles, new Comparator<File>() {
            @Override
            public int compare(File o1, File o2) {
                return o1.getName().compareTo(o2.getName());
            }
        });

        for (File childFile : modelFiles) {
            if (childFile.isFile()) processFiles(childFile, bbModelConverterList, leatherHorseArmor);
            else processFolders(childFile, bbModelConverterList, leatherHorseArmor, false);
        }
    }

    private static void assignBoneModelID(HashMap<String, Object> ironHorseArmorFile, BoneBlueprint boneBlueprint) {
        Map<String, Object> entryMap = new HashMap<>();
        entryMap.put("predicate", Collections.singletonMap("custom_model_data", counter + folderCounter * 1000));
        if (!boneBlueprint.getCubeBlueprintChildren().isEmpty()) {
            if (VersionChecker.serverVersionOlderThan(21, 4))
                boneBlueprint.setModelID(counter + folderCounter * 1000 + "");
            else
                boneBlueprint.setModelID(boneBlueprint.getBoneName());
            counter++;
        }
        entryMap.put("model", boneBlueprint.getBoneName());
        ironHorseArmorFile.computeIfAbsent("overrides", k -> new ArrayList<Map<String, Object>>());
        List<Map<String, Object>> existingList = ((List<Map<String, Object>>) ironHorseArmorFile.get("overrides"));
        existingList.add(entryMap);
        ironHorseArmorFile.put("overrides", existingList);
        if (!boneBlueprint.getBoneBlueprintChildren().isEmpty())
            for (BoneBlueprint childBoneBlueprint : boneBlueprint.getBoneBlueprintChildren())
                assignBoneModelID(ironHorseArmorFile, childBoneBlueprint);
    }
}
