package com.magmaguy.freeminecraftmodels.dataconverter;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;


public class FileModelConverter {

    @Getter
    private static final HashMap<String, FileModelConverter> convertedFileModels = new HashMap<>();
    private final HashMap<String, Object> values = new HashMap<>();
    private final HashMap<String, Object> outliner = new HashMap<>();
    private String modelName;
    @Getter
    private SkeletonBlueprint skeletonBlueprint;
    @Getter
    private AnimationsBlueprint animationsBlueprint = null;
    @Getter
    private String ID;
    private int blockBenchVersion = 4;
    @Getter
    private Map<String, Object> meta;
    private double resolutionWidth = 16;
    private double resolutionHeight = 16;
    /**
     * In this instance, the file is the raw bbmodel file which is actually in a JSON format
     *
     * @param file bbmodel file to parse
     */
    public FileModelConverter(File file) {
        if (file.getName().contains(".bbmodel")) modelName = file.getName().replace(".bbmodel", "");
        else if (file.getName().contains(".fmmodel")) modelName = file.getName().replace(".fmmodel", "");
        else {
            Bukkit.getLogger().warning("File " + file.getName() + " should not be in the models folder!");
            return;
        }

        modelName = StringToResourcePackFilename.convert(modelName);

        Gson gson = new Gson();

        Reader reader;
        // create a reader
        try {
            reader = Files.newBufferedReader(Paths.get(file.getPath()));
        } catch (Exception ex) {
            Logger.warn("Failed to read file " + file.getAbsolutePath());
            return;
        }

        // convert JSON file to map
        Map<?, ?> map = gson.fromJson(reader, Map.class);

        // close reader
        try {
            reader.close();
        } catch (Exception exception) {
            Logger.warn("Failed to close reader for file!");
            return;
        }

        if (map.containsKey("meta")) {
            this.meta = (Map<String, Object>) map.get("meta");
        }

        // Read resolution property (defines UV coordinate space)
        if (map.containsKey("resolution")) {
            Map<String, Object> resolution = (Map<String, Object>) map.get("resolution");
            if (resolution.get("width") != null) {
                resolutionWidth = ((Number) resolution.get("width")).doubleValue();
            }
            if (resolution.get("height") != null) {
                resolutionHeight = ((Number) resolution.get("height")).doubleValue();
            }
        }

        // Detect version from meta field
        blockBenchVersion = detectVersion(map);

        List<ParsedTexture> parsedTextures = parseTextures(map);

        //This parses the blocks/elements, separating them by type
        HashMap<String, Map<String, Object>> locators = new HashMap<>();
        HashMap<String, Map<String, Object>> nullObjects = new HashMap<>();

        List<Map> elementValues = (ArrayList<Map>) map.get("elements");
        if (elementValues != null) {
            for (Map element : elementValues) {
                String uuid = (String) element.get("uuid");
                String type = (String) element.get("type");

                if ("locator".equals(type)) {
                    // Locator element - used as IK anchor point
                    locators.put(uuid, element);
                } else if ("null_object".equals(type)) {
                    // Null object - IK controller
                    nullObjects.put(uuid, element);
                } else {
                    // Default: cube element
                    values.put(uuid, element);
                }
            }
        }

        //This creates the bones and skeleton
        // Handle version differences in outliner/groups
        List outlinerValues = mergeGroupsAndOutliner(map);

        for (int i = 0; i < outlinerValues.size(); i++) {
            if (!(outlinerValues.get(i) instanceof Map<?, ?> element)) {
                //I don't really know why Blockbench does this
                continue;
            } else {
                outliner.put((String) element.get("uuid"), element);
            }
        }

        ID = modelName;
        skeletonBlueprint = new SkeletonBlueprint(parsedTextures, outlinerValues, values, locators, nullObjects, generateFileTextures(parsedTextures), modelName, null, resolutionWidth, resolutionHeight);

        List animationList = (ArrayList) map.get("animations");
        if (animationList != null)
            animationsBlueprint = new AnimationsBlueprint(animationList, modelName, skeletonBlueprint, blockBenchVersion);
        convertedFileModels.put(modelName, this);
    }

    /**
     * Detect the major version from the meta field
     */
    private int detectVersion(Map<?, ?> map) {
        try {
            Map<?, ?> meta = (Map<?, ?>) map.get("meta");
            if (meta == null) {
                Logger.warn("Missing 'meta' field in model: " + modelName + ". Defaulting to version 4.");
                return 4;
            }

            Object versionObj = meta.get("format_version");
            if (versionObj == null) {
                Logger.warn("Missing 'format_version' in meta for model: " + modelName + ". Defaulting to version 4.");
                return 4;
            }

            String versionStr = versionObj.toString();
            String[] parts = versionStr.split("\\.");
            return Integer.parseInt(parts[0]);

        } catch (Exception e) {
            Logger.warn("Failed to parse format_version for model: " + modelName + ". Error: " + e.getMessage() + ". Defaulting to version 4.");
            return 4;
        }
    }

    /**
     * For v4: just return the old all in one outliner
     * For v5: Merge groups array with outliner
     */
    private List mergeGroupsAndOutliner(Map<?, ?> map) {
        List outlinerValues = (ArrayList) map.get("outliner");

        if (blockBenchVersion < 5) {
            // v4 doesn't need merging
            return outlinerValues;
        }

        // v5: groups are separate
        List groupsList = (ArrayList) map.get("groups");
        if (groupsList == null) {
            return outlinerValues;
        }

        // Create a map of group UUIDs to group objects for easy lookup
        HashMap<String, Map> groupsMap = new HashMap<>();
        for (Object groupObj : groupsList) {
            if (groupObj instanceof Map) {
                Map group = (Map) groupObj;
                String uuid = (String) group.get("uuid");
                if (uuid != null) {
                    groupsMap.put(uuid, group);
                }
            }
        }

        // Process outliner recursively and merge with group data
        return processOutlinerItems(outlinerValues, groupsMap);
    }

    /**
     * Recursively process outliner items and merge with group data from the groups array.
     * This traverses the entire tree structure, processing all children at every level.
     */
    private List processOutlinerItems(List items, HashMap<String, Map> groupsMap) {
        List result = new ArrayList();

        for (Object item : items) {
            if (item instanceof String) {
                // Direct UUID reference to an element (not a group)
                // These are leaf nodes that don't need merging
                result.add(item);
            } else if (item instanceof Map) {
                Map outlinerItem = (Map) item;
                String uuid = (String) outlinerItem.get("uuid");

                Map mergedItem;

                if (uuid != null && groupsMap.containsKey(uuid)) {
                    // Found matching group data - merge it in
                    Map groupData = groupsMap.get(uuid);
                    mergedItem = new HashMap(groupData);
                } else {
                    // No matching group, use outliner data as-is
                    mergedItem = new HashMap(outlinerItem);
                }

                // Recursively process children if they exist
                if (outlinerItem.containsKey("children")) {
                    List children = (List) outlinerItem.get("children");
                    if (children != null && !children.isEmpty()) {
                        List processedChildren = processOutlinerItems(children, groupsMap);
                        mergedItem.put("children", processedChildren);
                    }
                }

                result.add(mergedItem);
            }
        }

        return result;
    }

    public static void shutdown() {
        convertedFileModels.clear();
    }

    private List<ParsedTexture> parseTextures(Map<?, ?> map) {
        List<ParsedTexture> parsedTextures = new ArrayList<>();
        List<Map<?, ?>> texturesValues = (ArrayList<Map<?, ?>>) map.get("textures");
        Set<String> usedNames = new HashSet<>();

        for (int i = 0; i < texturesValues.size(); i++) {
            Map<?, ?> textureData = texturesValues.get(i);
            String originalName = (String) textureData.get("name");

            // Make filename unique if there's a collision
            if (originalName != null && usedNames.contains(originalName.toLowerCase())) {
                String baseName = originalName;
                // Remove extension if present
                if (baseName.contains(".")) {
                    baseName = baseName.substring(0, baseName.lastIndexOf("."));
                }
                String extension = originalName.contains(".") ? originalName.substring(originalName.lastIndexOf(".")) : "";

                int suffix = 1;
                String newName = baseName + suffix + extension;
                while (usedNames.contains(newName.toLowerCase())) {
                    suffix++;
                    newName = baseName + suffix + extension;
                }

                // Update the texture data with the unique name
                ((Map<String, Object>) textureData).put("name", newName);
                originalName = newName;
            }

            if (originalName != null) {
                usedNames.add(originalName.toLowerCase());
            }

            ParsedTexture parsedTexture = new ParsedTexture(textureData, modelName, i);
            if (parsedTexture.isValid()) parsedTextures.add(parsedTexture);
        }
        return parsedTextures;
    }

    private Map<String, Map<String, Object>> generateFileTextures(List<ParsedTexture> parsedTextures) {
        Map<String, Map<String, Object>> texturesMap = new HashMap<>();
        Map<String, Object> textureContents = new HashMap<>();
        for (ParsedTexture parsedTexture : parsedTextures) {
            textureContents.put("" + parsedTexture.getId(), "freeminecraftmodels:entity/" + modelName + "/" + parsedTexture.getFilename().replace(".png", ""));
        }
        texturesMap.put("textures", textureContents);
        return texturesMap;
    }
}