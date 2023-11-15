package com.magmaguy.freeminecraftmodels.dataconverter;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.bukkit.NamespacedKey;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BoneBlueprint {
    private static final double ARMOR_STAND_HEAD_SIZE_MULTIPLIER = 0.4D;
    private static final double MODEL_SCALE = 4D;
    @Getter
    private static final double ARMOR_STAND_PIVOT_POINT_HEIGHT = 1.43D;
    public static NamespacedKey nameTagKey = new NamespacedKey(MetadataHandler.PLUGIN, "NameTag");
    @Getter
    private final List<BoneBlueprint> boneBlueprintChildren = new ArrayList<>();
    @Getter
    private final List<CubeBlueprint> cubeBlueprintChildren = new ArrayList<>();
    @Getter
    private final String boneName;
    @Getter
    @Setter
    private Integer modelID = null;
    @Getter
    private EulerAngle armorStandHeadRotation = new EulerAngle(0, 0, 0);
    private Vector blockSpaceOrigin = new Vector();
    private Vector modelSpaceOrigin = new Vector();
    @Getter
    private boolean nameTag = false;
    //This is the vector offset from the entity's location that the pivot point of the boneBlueprint should be in, outside of animations
    private final Vector boneOriginOffset;
    @Getter
    private BoneBlueprint parent = null;
    public BoneBlueprint(double projectResolution,
                         Map<String, Object> boneJSON,
                         HashMap<String, Object> values,
                         Map<String, Map<String, Object>> textureReferences,
                         String modelName,
                         BoneBlueprint parent,
                         SkeletonBlueprint skeletonBlueprint) {
        this.boneName = "freeminecraftmodels:" + modelName.toLowerCase() + "/" + ((String) boneJSON.get("name")).toLowerCase();
        this.parent = parent;
        if (boneName.contains("tag_")) {
            nameTag = true;
        }
        setBoneRotation(boneJSON);
        setOrigin(boneJSON);
        //Bones can contain two types of children: bones and cubes
        //Bones exist solely for containing cubes
        //Cubes store the relevant visual data
        ArrayList<?> childrenValues = (ArrayList<?>) boneJSON.get("children");
        for (Object object : childrenValues) {
            if (object instanceof String) {
                //Case for object being a cube
                cubeBlueprintChildren.add(new CubeBlueprint(projectResolution, (Map<String, Object>) values.get(object)));
            } else {
                //Case for object being a boneBlueprint
                boneBlueprintChildren.add(new BoneBlueprint(projectResolution, (Map<String, Object>) object, values, textureReferences, modelName, this, skeletonBlueprint));
            }
        }
        generateBoneResourcePackFile(((String) boneJSON.get("name")).toLowerCase().replace(" ", "_"), textureReferences, modelName);
        skeletonBlueprint.getBoneMap().put((String) boneJSON.get("name"), this);
        boneOriginOffset = getArmorStandOffsetFromModel();
    }

    public Vector getBlockSpaceOrigin() {
        return blockSpaceOrigin.clone();
    }

    public Vector getBoneOriginOffset() {
        return boneOriginOffset.clone();
    }

    private void setBoneRotation(Map<?, ?> boneJSON) {
        Object obj = boneJSON.get("rotation");
        if (obj == null) return;
        List<Double> rotations = (List<Double>) obj;
        //todo: this requires a negative x and y value. I don't know why. But I really need to figure it out.
        armorStandHeadRotation = new EulerAngle(-Math.toRadians(rotations.get(0)), -Math.toRadians(rotations.get(1)), Math.toRadians(rotations.get(2)));
    }

    private void setOrigin(Map<String, Object> boneJSON) {
        Object obj = boneJSON.get("origin");
        if (obj == null) return;
        List<Double> origins = (List<Double>) obj;
        //This is the origin in "real space", meaning it is adjusted to the in-game unit size (16x larger than model space)
        blockSpaceOrigin = new Vector(origins.get(0) / 16d, origins.get(1) / 16d, origins.get(2) / 16d);
        //This in the origin in "model space", meaning it is adjusted to the resource pack / Blockbench unit size (16x smaller than real space)
        //It also adjusts the scaling to fit the head
        modelSpaceOrigin = new Vector(
                origins.get(0) * ARMOR_STAND_HEAD_SIZE_MULTIPLIER * MODEL_SCALE,
                origins.get(1) * ARMOR_STAND_HEAD_SIZE_MULTIPLIER * MODEL_SCALE,
                origins.get(2) * ARMOR_STAND_HEAD_SIZE_MULTIPLIER * MODEL_SCALE);
        boneJSON.put("origin", List.of(modelSpaceOrigin.getX(), modelSpaceOrigin.getY(), modelSpaceOrigin.getZ()));
    }

    private void generateBoneResourcePackFile(String filename, Map<String, Map<String, Object>> textureReferences, String modelName) {
        if (filename.equalsIgnoreCase("hitbox") || filename.equalsIgnoreCase("tag_name") || cubeBlueprintChildren.isEmpty())
            return;
        String modelDirectory = getModelDirectory(modelName);
        Map<String, Object> boneJSON = new HashMap<>(textureReferences);
        Vector offset = findDisplayOffset(modelName);
        //modelSpaceOrigin.subtract(new Vector(offset.getZ(), offset.getY(), offset.getX()));
        writeCubes(boneJSON, offset);
        setDisplay(boneJSON, offset.getX(), offset.getY(), offset.getZ());
        writeFile(modelDirectory, filename, boneJSON);
    }

    private String getModelDirectory(String modelName) {
        return MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar +
                "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar +
                "freeminecraftmodels" + File.separatorChar + "models" + File.separatorChar + modelName;
    }

    private Vector findDisplayOffset(String modelName) {
        //Get the lowest and highest coordinates of the cube
        Double lowestX = null, lowestY = null, lowestZ = null, highestX = null, highestY = null, highestZ = null;
        for (CubeBlueprint cubeBlueprint : cubeBlueprintChildren) {
            if (lowestX == null || cubeBlueprint.getFrom().getX() < lowestX) lowestX = cubeBlueprint.getFrom().getX();
            if (lowestY == null || cubeBlueprint.getFrom().getY() < lowestY) lowestY = cubeBlueprint.getFrom().getY();
            if (lowestZ == null || cubeBlueprint.getFrom().getZ() < lowestZ) lowestZ = cubeBlueprint.getFrom().getZ();
            if (highestX == null || cubeBlueprint.getTo().getX() > highestX) highestX = cubeBlueprint.getTo().getX();
            if (highestY == null || cubeBlueprint.getTo().getY() > highestY) highestY = cubeBlueprint.getTo().getY();
            if (highestZ == null || cubeBlueprint.getTo().getZ() > highestZ) highestZ = cubeBlueprint.getTo().getZ();
        }
        double xSize = highestX - lowestX;
        double ySize = highestY - lowestY;
        double zSize = highestZ - lowestZ;

        //If the cube exceeds 48 in size (remember, this is scaled) then it is too large to be rendered
        if (xSize > 48 || ySize > 48 || zSize > 48) {
            Developer.warn("Model " + modelName + " has a boneBlueprint or set of cubes which exceeds the maximum size! Either make the cubes smaller, less far apart or split them up into multiple bones!");
        }

        //This finds how much the boneBlueprint needs to be shifted around to fit between -16 and +32 to comply with resource pack limitations
        double minecraftMinimumModelStartPoint = -16D;
        double minecraftMaximumModelEndPoint = 32D;
        double xOffset = 0, yOffset = 0, zOffset = 0;
        if (lowestX < minecraftMinimumModelStartPoint)
            xOffset = minecraftMinimumModelStartPoint - lowestX;
        if (lowestY < minecraftMinimumModelStartPoint)
            yOffset = minecraftMinimumModelStartPoint - lowestY;
        if (lowestZ < minecraftMinimumModelStartPoint)
            zOffset = minecraftMinimumModelStartPoint - lowestZ;
        if (highestX > minecraftMaximumModelEndPoint)
            xOffset = minecraftMaximumModelEndPoint - highestX;
        if (highestY > minecraftMaximumModelEndPoint)
            yOffset = minecraftMaximumModelEndPoint - highestY;
        if (highestZ > minecraftMaximumModelEndPoint)
            zOffset = minecraftMaximumModelEndPoint - highestZ;

        return new Vector(xOffset, yOffset, zOffset);
    }

    private void writeCubes(Map<String, Object> boneJSON, Vector offset) {
        List<Object> cubeJSONs = new ArrayList<>();

        //This generates the JSON for each individual cube
        for (CubeBlueprint cubeBlueprint : cubeBlueprintChildren)
            cubeJSONs.add(cubeBlueprint.generateJSON(offset, modelSpaceOrigin));

        boneJSON.put("elements", cubeJSONs);
    }

    private void setDisplay(Map<String, Object> boneJSON, double xOffset, double yOffset, double zOffset) {
        //The hardcoded numbers adjust the item to the center of the armor stand
        //The offsets are from shifting the model around, when relevant, in order to fit the model boundaries
        //The offsets are scaled down
        boneJSON.put("display", Map.of(
                "head", Map.of("translation",
                        List.of(calculateVisualOffset(32, xOffset, modelSpaceOrigin.getX()),
                                calculateVisualOffset(25.5, yOffset, modelSpaceOrigin.getY()),
                                calculateVisualOffset(32, zOffset, modelSpaceOrigin.getZ())),
                        "scale", List.of(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE))));
    }

    /**
     * This is one of the more delicate bits of math.
     *
     * @param constantAxisOffset     This is the offset required to center something on top of the pivot point of an armor stand head.
     * @param axisOffset             This is the offset obtained from shifting the model around in order to fit within MC limitations
     * @param modelSpaceOriginOffset This is the location of the origin; since the armor stand spawns on the boneBlueprint location, it's necessary to shift the display to be there
     * @return
     */
    private double calculateVisualOffset(double constantAxisOffset, double axisOffset, double modelSpaceOriginOffset) {
        return constantAxisOffset - axisOffset * MODEL_SCALE - modelSpaceOriginOffset;
    }

    private void writeFile(String modelDirectory, String filename, Map<String, Object> boneJSON) {
        Gson gson = new Gson();
        try {
            FileUtils.writeStringToFile(new File(modelDirectory + File.separatorChar + filename + ".json"), gson.toJson(boneJSON), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Developer.warn("Failed to write boneBlueprint resource packs for boneBlueprint " + filename + "!");
            throw new RuntimeException(e);
        }
    }

    public Vector getArmorStandOffsetFromModel() {
        return getBlockSpaceOrigin().subtract(new Vector(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0));
    }

}
