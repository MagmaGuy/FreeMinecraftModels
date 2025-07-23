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

        List<ParsedTexture> parsedTextures = parseTextures(map);

        //This parses the blocks
        List<Map> elementValues = (ArrayList<Map>) map.get("elements");
        for (Map element : elementValues) {
            values.put((String) element.get("uuid"), element);
        }

        //This creates the bones and skeleton
        List outlinerValues = (ArrayList) map.get("outliner");
        for (int i = 0; i < outlinerValues.size(); i++) {
            if (!(outlinerValues.get(i) instanceof Map<?, ?> element)) {
                //Bukkit.getLogger().warning("WTF format for model name " + modelName + ": " + outlinerValues.get(i));
                //I don't really know why Blockbench does this
                continue;
            } else {
                outliner.put((String) element.get("uuid"), element);
            }
        }

        ID = modelName;
        skeletonBlueprint = new SkeletonBlueprint(parsedTextures, outlinerValues, values, generateFileTextures(parsedTextures), modelName, null);//todo: pass path

        List animationList = (ArrayList) map.get("animations");
        if (animationList != null)
            animationsBlueprint = new AnimationsBlueprint(animationList, modelName, skeletonBlueprint);
        convertedFileModels.put(modelName, this);//todo: id needs to be more unique, add folder directory into it
    }

    public static void shutdown() {
        convertedFileModels.clear();
    }

    private List<ParsedTexture> parseTextures(Map<?, ?> map) {
        List<ParsedTexture> parsedTextures = new ArrayList<>();
        //This parses the textures, extracts them to the correct directory and stores their values for the bone texture references
        List<Map<?, ?>> texturesValues = (ArrayList<Map<?, ?>>) map.get("textures");
        for (int i = 0; i < texturesValues.size(); i++) {
            ParsedTexture parsedTexture = new ParsedTexture(texturesValues.get(i), modelName, i);
            if (parsedTexture.isValid()) parsedTextures.add(new ParsedTexture(texturesValues.get(i), modelName, i));
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
