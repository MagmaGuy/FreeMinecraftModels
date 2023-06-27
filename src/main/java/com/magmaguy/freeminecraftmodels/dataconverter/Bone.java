package com.magmaguy.freeminecraftmodels.dataconverter;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Bone {
    @Getter
    private final List<Bone> boneChildren = new ArrayList<>();
    @Getter
    private final List<Cube> cubeChildren = new ArrayList<>();
    @Getter
    private final String boneName;
    @Getter
    @Setter
    private int modelID;

    public Bone(double projectResolution,
                Map<?, ?> boneJSON,
                HashMap<String, Object> values,
                Map<String, Map<String, Object>> textureReferences,
                String modelName) {
        this.boneName = "freeminecraftmodels:" + modelName.toLowerCase() + "/" + ((String) boneJSON.get("name")).toLowerCase();
        //Bones can contain two types of children: bones and cubes
        //Bones exist solely for containing cubes
        //Cubes store the relevant visual data
        ArrayList<?> childrenValues = (ArrayList<?>) boneJSON.get("children");
        for (Object object : childrenValues) {
            if (object instanceof String) {
                //Case for object being a cube
                cubeChildren.add(new Cube(projectResolution, (Map<String, Object>) values.get(object)));
            } else {
                //Case for object being a bone
                boneChildren.add(new Bone(projectResolution, (Map<?, ?>) object, values, textureReferences, modelName));
            }
        }
        generateBoneResourcePackFile((String) boneJSON.get("name"), textureReferences, modelName);
    }

    private void generateBoneResourcePackFile(String filename, Map<String, Map<String, Object>> textureReferences, String modelName) {
        if (filename.equalsIgnoreCase("hitbox") || filename.equalsIgnoreCase("tag_name") || cubeChildren.isEmpty())
            return;
        String modelDirectory = MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar +
                "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar +
                "freeminecraftmodels" + File.separatorChar + "models" + File.separatorChar + modelName;
        Map<String, Object> boneJSON = new HashMap<>(textureReferences);

        Gson gson = new Gson();
        List<Object> cubeJSONs = new ArrayList<>();

        Double lowestX = null, lowestY = null, lowestZ = null, highestX = null, highestY = null, highestZ = null;
        for (Cube cube : cubeChildren) {
            if (lowestX == null || cube.getFrom().getX() < lowestX) lowestX = cube.getFrom().getX();
            if (lowestY == null || cube.getFrom().getY() < lowestY) lowestY = cube.getFrom().getY();
            if (lowestZ == null || cube.getFrom().getZ() < lowestZ) lowestZ = cube.getFrom().getZ();
            if (highestX == null || cube.getTo().getX() > highestX) highestX = cube.getTo().getX();
            if (highestY == null || cube.getTo().getY() > highestY) highestY = cube.getTo().getY();
            if (highestZ == null || cube.getTo().getZ() > highestZ) highestZ = cube.getTo().getZ();
        }
        double xSize = highestX - lowestX;
        double ySize = highestY - lowestY;
        double zSize = highestZ - lowestZ;

        if (xSize > 48 || ySize > 48 || zSize > 48) {
            Developer.warn("Model " + modelName + " has a bone or set of cubes which exceeds the maximum size! Either make the cubes smaller, less far apart or split them up into multiple bones!");
        }

        int minecraftMinimumModelStartPoint = -16;
        int minecraftMaximumModelEndPoint = 32;
        double xOffset = 0, yOffset = 0, zOffset = 0;
        if (lowestX < minecraftMinimumModelStartPoint)
            xOffset = minecraftMinimumModelStartPoint - lowestX;
        if (lowestY < minecraftMinimumModelStartPoint)
            yOffset = minecraftMinimumModelStartPoint - lowestY;
        if (lowestZ < minecraftMinimumModelStartPoint)
            zOffset = minecraftMinimumModelStartPoint - lowestZ;
        if (highestX > minecraftMaximumModelEndPoint)
            xOffset = minecraftMaximumModelEndPoint - highestX;
        if (highestY > minecraftMaximumModelEndPoint) {
            yOffset = minecraftMaximumModelEndPoint - highestY;
            Developer.debug("highest y: " + highestY + " y offset: " + yOffset);
        }
        if (highestZ > minecraftMaximumModelEndPoint)
            zOffset = minecraftMaximumModelEndPoint - highestZ;

        for (Cube cube : cubeChildren)
            cubeJSONs.add(cube.generateJSON(xOffset, yOffset, zOffset));

        boneJSON.put("elements", cubeJSONs);

        //Scale it up 4x to max out the possible MC size!
        double scale = 4;
        //The hardcoded numbers adjust the item to the center of the armor stand
        //The offsets are from shifting the model around, when relevant, in order to fit the model boundaries
        //The offsets are scaled down
        boneJSON.put("display", Map.of(
                "head", Map.of("translation", List.of(32.0 - xOffset * scale, -11.27 - yOffset * scale, 32.0 - zOffset * scale),
                        "scale", List.of(scale, scale, scale))));
        try {
            FileUtils.writeStringToFile(new File(modelDirectory + File.separatorChar + filename + ".json"), gson.toJson(boneJSON), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Developer.warn("Failed to write bone resource packs for bone " + filename + "!");
            throw new RuntimeException(e);
        }
    }
}
