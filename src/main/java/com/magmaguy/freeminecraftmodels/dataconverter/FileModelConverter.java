package com.magmaguy.freeminecraftmodels.dataconverter;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.utils.StringToResourcePackFilename;
import com.magmaguy.freeminecraftmodels.utils.ImmutableMapSnapshots;
import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.Reader;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class FileModelConverter {

    private static final ConcurrentHashMap<String, FileModelConverter> convertedFileModels = new ConcurrentHashMap<>();
    private static final Set<String> rejectedModelIds = new HashSet<>();
    private String modelName;
    @Getter
    private SkeletonBlueprint skeletonBlueprint;
    @Getter
    private AnimationsBlueprint animationsBlueprint = null;
    @Getter
    private String ID;
    @Getter
    private File sourceFile;
    @Getter
    private int blockBenchVersion = 4;
    @Getter
    private Map<String, Object> meta;
    @Getter
    private List<ParsedTexture> parsedTextures = new ArrayList<>();
    private double resolutionWidth = 16;
    private double resolutionHeight = 16;
    /**
     * In this instance, the file is the raw bbmodel file which is actually in a JSON format
     *
     * @param file bbmodel file to parse
     */
    public FileModelConverter(File file) {
        this.sourceFile = file;
        modelName = normalizedModelId(file);
        if (modelName == null) {
            // Silently skip known companion files (e.g. .yml configs, .png textures)
            if (file.getName().endsWith(".yml") || file.getName().endsWith(".yaml") || file.getName().endsWith(".png") || file.getName().endsWith(".json"))
                return;
            Bukkit.getLogger().warning("File " + file.getName() + " should not be in the models folder!");
            return;
        }

        ID = modelName;
        if (rejectedModelIds.contains(modelName)) return;

        FileModelConverter existing = convertedFileModels.get(modelName);
        if (existing != null) {
            rejectCollision(modelName, List.of(existing.sourceFile, file));
            existing.invalidate();
            return;
        }

        Gson gson = new Gson();

        Map<?, ?> map;
        try (Reader reader = Files.newBufferedReader(file.toPath())) {
            map = gson.fromJson(reader, Map.class);
        } catch (Exception exception) {
            Logger.warn("Failed to read model file " + file.getAbsolutePath() + ": " + exception.getMessage());
            return;
        }
        if (map == null) {
            Logger.warn("Model file is empty: " + file.getAbsolutePath());
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
        this.parsedTextures = parsedTextures;

        //This parses the blocks/elements, separating them by type
        // Local, not a field: only needed while building the skeleton blueprint —
        // keeping it as a field retained the whole parsed cube map per model.
        HashMap<String, Object> values = new HashMap<>();
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

        skeletonBlueprint = new SkeletonBlueprint(parsedTextures, outlinerValues, values, locators, nullObjects, generateFileTextures(parsedTextures), modelName, null, resolutionWidth, resolutionHeight);

        List animationList = (ArrayList) map.get("animations");
        if (animationList != null)
            animationsBlueprint = new AnimationsBlueprint(animationList, modelName, skeletonBlueprint, blockBenchVersion);
        convertedFileModels.put(modelName, this);
    }

    /**
     * Rejects every file participating in a normalized model-ID collision before
     * parsing starts. Parsing a model writes resource-pack assets, so detecting
     * only the second file would still leave order-dependent output from the
     * first file.
     *
     * @param modelFiles all model definition files that will be parsed
     */
    public static void preflightNormalizedModelIds(Collection<File> modelFiles) {
        Map<String, List<File>> sourcesById = new TreeMap<>();
        for (File modelFile : modelFiles) {
            String normalizedId = normalizedModelId(modelFile);
            if (normalizedId == null) continue;
            sourcesById.computeIfAbsent(normalizedId, ignored -> new ArrayList<>()).add(modelFile);
        }

        for (Map.Entry<String, List<File>> entry : sourcesById.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            entry.getValue().sort(Comparator.comparing(File::getAbsolutePath));
            rejectCollision(entry.getKey(), entry.getValue());
        }
    }

    private static String normalizedModelId(File file) {
        if (file == null) return null;
        String filename = file.getName();
        String lowercaseFilename = filename.toLowerCase(Locale.ROOT);
        String rawModelName;
        if (lowercaseFilename.endsWith(".bbmodel")) {
            rawModelName = filename.substring(0, filename.length() - ".bbmodel".length());
        } else if (lowercaseFilename.endsWith(".fmmodel")) {
            rawModelName = filename.substring(0, filename.length() - ".fmmodel".length());
        } else {
            return null;
        }
        return StringToResourcePackFilename.convert(rawModelName);
    }

    private static void rejectCollision(String modelId, Collection<File> sources) {
        rejectedModelIds.add(modelId);
        convertedFileModels.remove(modelId);
        String sourceList = sources.stream()
                .filter(Objects::nonNull)
                .map(File::getAbsolutePath)
                .sorted()
                .collect(java.util.stream.Collectors.joining(", "));
        Logger.warn("[FMM Models] Rejected normalized model ID collision '" + modelId
                + "'. These files normalize to the same ID: " + sourceList
                + ". Rename the files so every normalized model ID is unique; no colliding model was loaded.");
    }

    private void invalidate() {
        skeletonBlueprint = null;
        animationsBlueprint = null;
    }

    public boolean isRegisteredModel() {
        return skeletonBlueprint != null && convertedFileModels.get(ID) == this;
    }

    /**
     * Returns an immutable point-in-time view so API consumers cannot mutate the
     * live model registry.
     */
    public static HashMap<String, FileModelConverter> getConvertedFileModels() {
        return ImmutableMapSnapshots.hashMapCopyOf(convertedFileModels);
    }

    /**
     * O(1) single-model lookup against the live registry. Prefer this over
     * {@link #getConvertedFileModels()} when only one model is needed — the
     * snapshot getter copies the whole registry per call.
     *
     * @param id the normalized model ID, may be null
     * @return the registered model, or null if the ID is null or unknown
     */
    public static FileModelConverter getModel(String id) {
        if (id == null) return null;
        return convertedFileModels.get(id);
    }

    /**
     * O(1) existence check against the live registry.
     *
     * @param id the normalized model ID, may be null
     * @return whether a model is registered under the given ID
     */
    public static boolean containsModel(String id) {
        if (id == null) return false;
        return convertedFileModels.containsKey(id);
    }

    /**
     * Detect the major version from the meta field
     */
    private int detectVersion(Map<?, ?> map) {
        try {
            Map<?, ?> meta = (Map<?, ?>) map.get("meta");
            if (meta == null) {
                Logger.info("Missing 'meta' field in model: " + modelName + ". Defaulting to version 4.");
                return 4;
            }

            Object versionObj = meta.get("format_version");
            if (versionObj == null) {
                Logger.info("Missing 'format_version' in meta for model: " + modelName + ". Defaulting to version 4.");
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
        rejectedModelIds.clear();
    }

    private List<ParsedTexture> parseTextures(Map<?, ?> map) {
        List<ParsedTexture> parsedTextures = new ArrayList<>();
        List<Map<?, ?>> texturesValues = (ArrayList<Map<?, ?>>) map.get("textures");
        // Texture-less bbmodels have no "textures" entry at all — nothing to parse.
        if (texturesValues == null) return parsedTextures;
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
            textureContents.put("" + parsedTexture.getId(), "freeminecraftmodels:entity/" + modelName + "/" + stripPngExtension(parsedTexture.getFilename()));
        }
        texturesMap.put("textures", textureContents);
        return texturesMap;
    }

    private static String stripPngExtension(String filename) {
        return filename.endsWith(".png") ? filename.substring(0, filename.length() - 4) : filename;
    }
}
