package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class ImportsFolder {
    public static void initializeConfig() {
        File file = ConfigurationEngine.directoryCreator("imports");

        if (!file.exists()) {
            Developer.warn("Failed to create imports directory!");
            return;
        }

        if (!file.isDirectory()) {
            Developer.warn("Directory imports was not a directory!");
            return;
        }

        for (File childFile : file.listFiles()) {
            //First off: Is it a bbmodel file?
            if (childFile.getName().contains(".bbmodel")) {
                //Reading time
                Gson readerGson = new Gson();

                Reader reader;
                // create a reader
                try {
                    reader = Files.newBufferedReader(Paths.get(childFile.getPath()));
                } catch (Exception ex) {
                    Developer.warn("Failed to read file " + childFile.getAbsolutePath());
                    return;
                }

                // convert JSON file to map
                Map<?, ?> jsonMap = readerGson.fromJson(reader, Map.class);

                //Writing time
                Gson writerGson = new Gson();
                //The objective here is to get every map that is actually used, and avoid every map that is not.
                HashMap<String, Object> minifiedMap = new HashMap<>();

                minifiedMap.put("resolution", jsonMap.get("resolution"));
                minifiedMap.put("elements", jsonMap.get("elements"));
                minifiedMap.put("outliner", jsonMap.get("outliner"));
                ArrayList<Map> minifiedTextures = new ArrayList<>();
                ((ArrayList) jsonMap.get("textures")).forEach(innerMap ->
                        minifiedTextures.add(
                                Map.of(
                                        "source", ((LinkedTreeMap) innerMap).get("source"),
                                        "id", ((LinkedTreeMap) innerMap).get("id"),
                                        "name", ((String)((LinkedTreeMap) innerMap).get("name")))));
                minifiedMap.put("textures", minifiedTextures);

                try {
                    FileUtils.writeStringToFile(
                            new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "models"
                                    + File.separatorChar + childFile.getName().replace(".bbmodel", ".fmmodel")),
                            writerGson.toJson(minifiedMap), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    Developer.warn("Failed to generate the minified file!");
                    throw new RuntimeException(e);
                }
                //todo: zip imports

            }
        }
    }
}
