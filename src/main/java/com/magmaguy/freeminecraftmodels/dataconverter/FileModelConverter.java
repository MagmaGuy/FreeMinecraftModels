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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // Detect version from meta field
        blockBenchVersion = detectVersion(map);
        Logger.info("Detected bbmodel format version: " + blockBenchVersion);

        List<ParsedTexture> parsedTextures = parseTextures(map);

        //This parses the blocks/elements
        List<Map> elementValues = (ArrayList<Map>) map.get("elements");
        for (Map element : elementValues) {
            values.put((String) element.get("uuid"), element);
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
        skeletonBlueprint = new SkeletonBlueprint(parsedTextures, outlinerValues, values, generateFileTextures(parsedTextures), modelName, null);

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
            if (meta == null) return 4; // Default to v4

            String formatVersion = (String) meta.get("format_version");
            if (formatVersion == null) return 4;

            // Parse major version (e.g., "5.0" -> 5, "4.10" -> 4)
            String majorVersionStr = formatVersion.split("\\.")[0];
            return Integer.parseInt(majorVersionStr);
        } catch (Exception e) {
            Logger.warn("Failed to detect bbmodel version, defaulting to v4");
            return 4;
        }
    }

    /**
     * For v4: Merge groups array with outliner
     * For v5: This shouldn't be called, but returns outliner as-is for safety
     */
    private List mergeGroupsAndOutliner(Map<?, ?> map) {
        List outlinerValues = (ArrayList) map.get("outliner");

        if (blockBenchVersion < 5) {
            // v5 doesn't need merging
            return outlinerValues;
        }

        // v4: groups are separate
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
        for (int i = 0; i < texturesValues.size(); i++) {
            ParsedTexture parsedTexture = new ParsedTexture(texturesValues.get(i), modelName, i);
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