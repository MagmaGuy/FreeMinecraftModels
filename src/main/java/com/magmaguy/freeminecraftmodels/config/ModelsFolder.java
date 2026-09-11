package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.bedrock.BedrockEntityBundleExporter;
import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.dataconverter.ParsedTexture;
import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
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

    /**
     * Returns the canonical lowercase models folder when it exists, then falls
     * back to the legacy uppercase name for old installs on case-sensitive
     * filesystems.
     */
    public static File resolveModelsFolder() {
        File dataFolder = MetadataHandler.PLUGIN.getDataFolder();
        File canonical = new File(dataFolder, "models");
        if (canonical.exists()) return canonical;

        File legacy = new File(dataFolder, "Models");
        if (legacy.exists()) return legacy;
        return canonical;
    }

    public static void initializeConfig() {
        File directory = resolveModelsFolder();
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Cannot create models directory: " + directory);
        initializeConfig(ItemScriptManager.prepareCatalog(directory));
    }

    public static void initializeConfig(ItemScriptManager.ItemCatalog candidate) {
        counter = 1;
        folderCounter = 50;

        File file = resolveModelsFolder();

        if (!file.isDirectory()) throw new IllegalStateException("Models directory is unavailable: " + file);

        FileModelConverter.preflightNormalizedModelIds(collectModelFiles(file));

        if (VersionChecker.serverVersionOlderThan(21, 4))
            legacyHorseArmorGeneration(file);
        else
            newModelGeneration(file);
        ItemScriptManager.activateCandidate(candidate);
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
        bbModelConverterList.removeIf(model -> !model.isRegisteredModel());
        bbModelConverterList.forEach(BedrockEntityBundleExporter::export);
        leatherHorseArmor.put("data", counter - 1 + folderCounter * 1000);

        try {
            FileUtils.writeStringToFile(
                    new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output"
                            + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar +
                            "minecraft" + File.separatorChar + "models" + File.separatorChar + "item" + File.separatorChar
                            + "leather_horse_armor.json"),
                    gson.toJson(leatherHorseArmor), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Failed to generate the leather horse armor file!");
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
        // mkdirs, not mkdir: on a fresh install none of the parent output folders exist yet.
        if (!itemModelsFolder.exists()) itemModelsFolder.mkdirs();

        DisplayModelRegistry.shutdown(); // Clear from any previous generation

        List<FileModelConverter> bbModelConverterList = new ArrayList<>();
        HashMap<String, Object> jsonConfig = new HashMap<>();
        processFolders(file, bbModelConverterList, jsonConfig, true);
        bbModelConverterList.removeIf(model -> !model.isRegisteredModel());
        bbModelConverterList.forEach(BedrockEntityBundleExporter::export);

        Gson itemDefinitionGson = new Gson();
        for (FileModelConverter fileModelConverter : bbModelConverterList) {
            if (fileModelConverter.getSkeletonBlueprint() == null) continue;
            for (BoneBlueprint boneBlueprint : fileModelConverter.getSkeletonBlueprint().getBoneMap().values()) {
                // Skip the auto-generated root only when it has no cubes of its own.
                // If freefloating cubes were attached to it, it has a real model JSON
                // and needs an item-model definition, or its display entity renders as
                // the missing-texture purple/black cube.
                if (boneBlueprint.getBoneName().contains("freeminecraftmodels_autogenerated_root")
                        && boneBlueprint.getCubeBlueprintChildren().isEmpty()) continue;
                // Skip bones that BoneBlueprint.generateAndWriteCubes also skips —
                // those have no geometry JSON written, so writing an item-stack
                // definition for them just produces a dangling reference. MC
                // 1.21.x tolerated it silently; MC 26.1+ logs every dangling
                // reference as "Missing block model: freeminecraftmodels:...".
                // Covers: hitbox bones (collision-only), tag_name / tag_head
                // positional anchors, FMM's auto-wrapped fmm_nametag_bone_*
                // variants, and any empty/anchor-only bone (waist, chair2,
                // "everything" wrapper, etc.).
                // Derived exactly the way generateAndWriteCubes derives its file
                // name; the full bone name is "freeminecraftmodels:model/bone",
                // so comparing the whole name (or everything after the ":")
                // against "hitbox"/"tag_name" never matched anything.
                String shortBoneName = com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename
                        .convert(boneBlueprint.getOriginalBoneName());
                if (boneBlueprint.getCubeBlueprintChildren().isEmpty()
                        || shortBoneName.equalsIgnoreCase("hitbox")
                        || shortBoneName.equalsIgnoreCase("tag_name")) continue;
                // LinkedHashMap, not Map.of / HashMap: these maps are serialised straight to
                // disk, so their iteration order is the file's byte order. Map.of is backed by
                // java.util.ImmutableCollections, which seeds a per-JVM random SALT and probes
                // from a salt-dependent index — the same three keys come out in a different
                // order on every server start. That rewrote every item definition on each boot,
                // which changed the pack zip hash, which defeated ResourcePackManager's SHA1
                // "remote already has this pack" check, forcing a full re-upload every restart
                // and making every player re-download the whole resource pack.
                Map<String, Object> tintJson = new LinkedHashMap<>();
                tintJson.put("type", "minecraft:custom_model_data");
                tintJson.put("index", 0);
                tintJson.put("default", 0xFFFFFF);

                Map<String, Object> modelContentsJson = new LinkedHashMap<>();
                modelContentsJson.put("tints", List.of(tintJson));
                modelContentsJson.put("type", "minecraft:model");
                modelContentsJson.put("model", "freeminecraftmodels:" + boneBlueprint.getBoneName().split(":")[1]);

                Map<String, Object> modelJson = new LinkedHashMap<>();
                modelJson.put("model", modelContentsJson);

                try {
                    FileUtils.writeStringToFile(
                            new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
                                    File.separatorChar + "output" +
                                    File.separatorChar + "FreeMinecraftModels" +
                                    File.separatorChar + "assets" +
                                    File.separatorChar + "freeminecraftmodels" +
                                    File.separatorChar + "items" +
                                    File.separatorChar + boneBlueprint.getBoneName().split(":")[1] + ".json"),
                            itemDefinitionGson.toJson(modelJson), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // --- Display model processing ---
        File displayModelsFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
                File.separatorChar + "output" +
                File.separatorChar + "FreeMinecraftModels" +
                File.separatorChar + "assets" +
                File.separatorChar + "freeminecraftmodels" +
                File.separatorChar + "models" +
                File.separatorChar + "display");
        if (!displayModelsFolder.exists()) displayModelsFolder.mkdirs();

        File displayItemsFolder = new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() +
                File.separatorChar + "output" +
                File.separatorChar + "FreeMinecraftModels" +
                File.separatorChar + "assets" +
                File.separatorChar + "freeminecraftmodels" +
                File.separatorChar + "items" +
                File.separatorChar + "display");
        if (!displayItemsFolder.exists()) displayItemsFolder.mkdirs();

        Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
        for (FileModelConverter converter : bbModelConverterList) {
            if (converter.getSourceFile() == null) continue;
            File sourceFile = converter.getSourceFile();
            String modelId = converter.getID();

            // Check for sibling .json
            String baseName = sourceFile.getName();
            int dotIndex = baseName.lastIndexOf('.');
            if (dotIndex > 0) baseName = baseName.substring(0, dotIndex);
            File displayJsonFile = new File(sourceFile.getParentFile(), baseName + ".json");
            if (!displayJsonFile.exists()) continue;

            try {
                // Read the admin-provided JSON
                String jsonContent = FileUtils.readFileToString(displayJsonFile, StandardCharsets.UTF_8);
                JsonObject displayModel = JsonParser.parseString(jsonContent).getAsJsonObject();

                // Rewrite bare texture references (no ":" means not namespaced)
                if (displayModel.has("textures")) {
                    JsonObject textures = displayModel.getAsJsonObject("textures");
                    // Build texture index → FMM path mapping
                    Map<String, String> textureMapping = new HashMap<>();
                    List<ParsedTexture> parsedTextures = converter.getParsedTextures();
                    if (parsedTextures != null) {
                        for (ParsedTexture pt : parsedTextures) {
                            String fmmPath = "freeminecraftmodels:entity/" + modelId + "/"
                                    + pt.getFilename().replace(".png", "");
                            textureMapping.put("" + pt.getId(), fmmPath);
                        }
                    }

                    for (Map.Entry<String, com.google.gson.JsonElement> entry :
                            new java.util.ArrayList<>(textures.entrySet())) {
                        String value = entry.getValue().getAsString();
                        if (!value.contains(":")) {
                            // Try to map by texture key (index)
                            String mapped = textureMapping.get(entry.getKey());
                            if (mapped != null) {
                                textures.addProperty(entry.getKey(), mapped);
                            } else if (!textureMapping.isEmpty()) {
                                // Fallback: use first available texture
                                textures.addProperty(entry.getKey(), textureMapping.values().iterator().next());
                            }
                        }
                    }
                }

                // Write rewritten model
                FileUtils.writeStringToFile(
                        new File(displayModelsFolder, modelId + ".json"),
                        prettyGson.toJson(displayModel), StandardCharsets.UTF_8);

                // Generate item model definition
                HashMap<String, Object> itemDef = new HashMap<>();
                HashMap<String, Object> modelObj = new HashMap<>();
                modelObj.put("type", "minecraft:model");
                modelObj.put("model", "freeminecraftmodels:display/" + modelId);
                itemDef.put("model", modelObj);
                FileUtils.writeStringToFile(
                        new File(displayItemsFolder, modelId + ".json"),
                        itemDefinitionGson.toJson(itemDef), StandardCharsets.UTF_8);

                DisplayModelRegistry.register(modelId);

            } catch (Exception e) {
                Logger.warn("Failed to process display model JSON for " + modelId + ": " + e.getMessage());
            }
        }

        // --- Bow/Crossbow draw state detection ---
        // After all display models are processed, detect state groups and generate
        // conditional item definitions (replacing the simple ones generated above).
        Set<String> allDisplayModelIds = new HashSet<>(DisplayModelRegistry.getRegisteredModels());
        List<BowStateDetector.StateGroup> stateGroups = BowStateDetector.detectStateGroups(allDisplayModelIds);

        for (BowStateDetector.StateGroup group : stateGroups) {
            String base = group.baseName();

            // Collect all state model IDs so we can suppress their individual item defs
            Set<String> stateModelIds = new HashSet<>();
            stateModelIds.add(base + "_idle");
            stateModelIds.add(base + "_draw_start");
            stateModelIds.add(base + "_draw_half");
            stateModelIds.add(base + "_draw_full");
            if (group.isCrossbow()) stateModelIds.add(base + "_charged");

            // Generate one public item definition for the base model ID. The
            // state-specific models remain internal render states for drawing.
            String itemJson = group.isCrossbow()
                    ? BowStateDetector.generateCrossbowItemJson(base, "freeminecraftmodels")
                    : BowStateDetector.generateBowItemJson(base, "freeminecraftmodels");

            try {
                FileUtils.writeStringToFile(
                        new File(displayItemsFolder, base + ".json"),
                        itemJson, StandardCharsets.UTF_8);
                Logger.info("Generated " + (group.isCrossbow() ? "crossbow" : "bow")
                        + " state item definition for: " + base);
            } catch (IOException e) {
                Logger.warn("Failed to write bow/crossbow item definition for " + base + ": " + e.getMessage());
            }

            // Remove individual item definitions for render states (they should
            // never appear as standalone selectable/menu item models).
            new File(displayItemsFolder, base + "_idle.json").delete();
            new File(displayItemsFolder, base + "_draw_start.json").delete();
            new File(displayItemsFolder, base + "_draw_half.json").delete();
            new File(displayItemsFolder, base + "_draw_full.json").delete();
            if (group.isCrossbow()) new File(displayItemsFolder, base + "_charged.json").delete();

            for (String stateModelId : stateModelIds) {
                DisplayModelRegistry.unregister(stateModelId);
            }
            DisplayModelRegistry.register(base);
        }

    }

    private static void processFiles(File childFile,
                                     List<FileModelConverter> bbModelConverterList,
                                     HashMap<String, Object> leatherHorseArmor) {
        try {
            FileModelConverter bbModelConverter = new FileModelConverter(childFile);
            if (bbModelConverter.getSkeletonBlueprint() == null) {
                // File was not a valid model file (e.g., PNG or other non-model file)
                return;
            }
            bbModelConverterList.add(bbModelConverter);
            // Note: this used to "filter out" hitbox/tag_name/root bones, but the
            // comparisons ran against the namespaced bone name
            // ("freeminecraftmodels:model/bone") so they never matched and every
            // bone has always gone through assignBoneModelID. Kept unconditional
            // on purpose: skipping utility bones now would renumber the legacy
            // (<1.21.4) custom_model_data IDs, breaking items already given out.
            // Utility bones are excluded from display/output generation downstream
            // (BoneBlueprint.generateAndWriteCubes and newModelGeneration's
            // item-definition filter).
            for (BoneBlueprint boneBlueprint : bbModelConverter.getSkeletonBlueprint().getMainModel())
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
        if (modelFiles == null) {
            Logger.warn("Failed to list models directory " + file.getAbsolutePath());
            return;
        }
        Arrays.sort(modelFiles, Comparator.comparing(File::getName));

        for (File childFile : modelFiles) {
            if (childFile.isFile()) processFiles(childFile, bbModelConverterList, leatherHorseArmor);
            else processFolders(childFile, bbModelConverterList, leatherHorseArmor, false);
        }
    }

    private static List<File> collectModelFiles(File root) {
        List<File> modelFiles = new ArrayList<>();
        File[] children = root.listFiles();
        if (children == null) return modelFiles;
        Arrays.sort(children, Comparator.comparing(File::getName));
        for (File child : children) {
            if (child.isDirectory()) {
                modelFiles.addAll(collectModelFiles(child));
            } else {
                String lowercaseName = child.getName().toLowerCase(Locale.ROOT);
                if (lowercaseName.endsWith(".bbmodel") || lowercaseName.endsWith(".fmmodel")) {
                    modelFiles.add(child);
                }
            }
        }
        return modelFiles;
    }

    private static void assignBoneModelID(HashMap<String, Object> ironHorseArmorFile, BoneBlueprint boneBlueprint) {
        boolean legacy = VersionChecker.serverVersionOlderThan(21, 4);
        // The overrides list is only ever serialized by the legacy (<1.21.4)
        // horse-armor generation; on the modern path the accumulating map is
        // discarded, so only the setModelID/counter side effects are needed.
        if (legacy) {
            Map<String, Object> entryMap = new HashMap<>();
            entryMap.put("predicate", Collections.singletonMap("custom_model_data", counter + folderCounter * 1000));
            entryMap.put("model", boneBlueprint.getBoneName());
            ironHorseArmorFile.computeIfAbsent("overrides", k -> new ArrayList<Map<String, Object>>());
            List<Map<String, Object>> existingList = ((List<Map<String, Object>>) ironHorseArmorFile.get("overrides"));
            existingList.add(entryMap);
            ironHorseArmorFile.put("overrides", existingList);
        }
        if (!boneBlueprint.getCubeBlueprintChildren().isEmpty()) {
            if (legacy)
                boneBlueprint.setModelID(counter + folderCounter * 1000 + "");
            else
                boneBlueprint.setModelID(boneBlueprint.getBoneName());
            counter++;
        }
        if (!boneBlueprint.getBoneBlueprintChildren().isEmpty())
            for (BoneBlueprint childBoneBlueprint : boneBlueprint.getBoneBlueprintChildren())
                assignBoneModelID(ironHorseArmorFile, childBoneBlueprint);
    }
}
