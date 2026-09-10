package com.magmaguy.freeminecraftmodels.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

public final class BlocksAtlasGenerator {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FMM_ENTITY_RESOURCE_PREFIX = "freeminecraftmodels:entity/";

    private BlocksAtlasGenerator() {
    }

    public static void materialize(Path resourcePackRoot) throws IOException {
        Path atlasPath = resourcePackRoot.resolve("assets/minecraft/atlases/blocks.json");
        JsonObject atlas;
        try (Reader reader = Files.newBufferedReader(atlasPath, StandardCharsets.UTF_8)) {
            atlas = JsonParser.parseReader(reader).getAsJsonObject();
        }

        JsonArray existingSources = atlas.getAsJsonArray("sources");
        if (existingSources == null) {
            throw new IOException("Blocks atlas is missing its sources array: " + atlasPath);
        }

        Set<String> explicitlyMaterializedResources = explicitSingleResources(existingSources);
        List<String> resources = discoverEntityResources(resourcePackRoot).stream()
                .filter(resource -> !explicitlyMaterializedResources.contains(resource))
                .toList();
        JsonArray materializedSources = new JsonArray();
        boolean insertedEntitySources = false;
        for (JsonElement source : existingSources) {
            if (isUnsafeEntityDirectory(source)) {
                if (!insertedEntitySources) {
                    appendSingleSources(materializedSources, resources);
                    insertedEntitySources = true;
                }
                continue;
            }
            materializedSources.add(source);
        }
        if (!insertedEntitySources) {
            appendSingleSources(materializedSources, resources);
        }

        atlas.add("sources", materializedSources);
        try (Writer writer = Files.newBufferedWriter(atlasPath, StandardCharsets.UTF_8)) {
            GSON.toJson(atlas, writer);
        }
    }

    private static List<String> discoverEntityResources(Path resourcePackRoot) throws IOException {
        Path entityTextures = resourcePackRoot.resolve("assets/freeminecraftmodels/textures/entity");
        if (!Files.isDirectory(entityTextures)) return List.of();

        try (Stream<Path> paths = Files.walk(entityTextures)) {
            return paths
                    .filter(Files::isRegularFile)
                    .map(entityTextures::relativize)
                    .map(Path::toString)
                    .filter(path -> path.endsWith(".png"))
                    .map(path -> path.substring(0, path.length() - ".png".length()))
                    .map(path -> path.replace('\\', '/'))
                    .map(path -> FMM_ENTITY_RESOURCE_PREFIX + path)
                    .sorted()
                    .toList();
        }
    }

    private static Set<String> explicitSingleResources(JsonArray sources) {
        Set<String> resources = new HashSet<>();
        for (JsonElement source : sources) {
            if (!source.isJsonObject()) continue;
            JsonObject sourceObject = source.getAsJsonObject();
            if (isType(stringValue(sourceObject, "type"), "single")) {
                String resource = stringValue(sourceObject, "resource");
                String sprite = stringValue(sourceObject, "sprite");
                if (sprite.isEmpty() || sprite.equals(resource)) resources.add(resource);
            }
        }
        return resources;
    }

    private static boolean isUnsafeEntityDirectory(JsonElement source) {
        if (!source.isJsonObject()) return false;
        JsonObject sourceObject = source.getAsJsonObject();
        String type = stringValue(sourceObject, "type");
        return isType(type, "directory")
                && "entity".equals(stringValue(sourceObject, "source"))
                && "entity/".equals(stringValue(sourceObject, "prefix"));
    }

    private static boolean isType(String type, String expected) {
        return expected.equals(type) || ("minecraft:" + expected).equals(type);
    }

    private static String stringValue(JsonObject object, String property) {
        JsonElement value = object.get(property);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString()
                : "";
    }

    private static void appendSingleSources(JsonArray sources, List<String> resources) {
        for (String resource : resources) {
            JsonObject source = new JsonObject();
            source.addProperty("type", "minecraft:single");
            source.addProperty("resource", resource);
            sources.add(source);
        }
    }
}
