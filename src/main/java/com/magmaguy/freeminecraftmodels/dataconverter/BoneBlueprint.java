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
    //This multiplier converts units from the bbmodel units to the resource pack units, needed because the model is scaled up
    // by 4x to bypass the Minecraft limitations and
    protected static final double ARMOR_STAND_HEAD_SIZE_MULTIPLIER = 0.4D;
    //This sets the scale of the model for the resource pack
    private static final double MODEL_SCALE = 4D;
    @Getter
    private static final double ARMOR_STAND_PIVOT_POINT_HEIGHT = 1.438D;
    //This multiplier converts resource pack units back to bbmodel units
    private static final double ARMOR_STAND_SCALING_RECIPROCAL = 2.5;
    public static NamespacedKey nameTagKey = new NamespacedKey(MetadataHandler.PLUGIN, "NameTag");
    @Getter
    private final List<BoneBlueprint> boneBlueprintChildren = new ArrayList<>();
    @Getter
    private final List<CubeBlueprint> cubeBlueprintChildren = new ArrayList<>();
    @Getter
    private final String boneName;
    @Getter
    private final String originalModelName;
    @Getter
    private final String originalBoneName;
    //This is the vector offset from the entity's location that the pivot point of the boneBlueprint should be in, outside of animations
    private Vector armorStandOffsetFromModel;
    @Getter
    private final Vector displayEntityModelSpaceOriginOffset = new Vector();
    @Getter
    @Setter
    private Integer modelID = null;
    @Getter
    private EulerAngle armorStandHeadRotation = new EulerAngle(0, 0, 0);
    private Vector displayEntityStandOffsetFromModel;

    private Vector blockSpaceOrigin = new Vector();
    @Getter
    private EulerAngle displayEntityBoneRotation = new EulerAngle(0, 0, 0);
    @Getter
    private Vector armorStandModelSpaceOriginOffset = new Vector();
    @Getter
    private boolean nameTag = false;
    @Getter
    private BoneBlueprint parent = null;
    @Getter
    private boolean isDisplayModel = true;
    private Object boneRotation;
    private Vector cubeOffset = new Vector();
    @Getter
    private Vector originsForDisplayEntityLocationShiftBasedOnRotation = new Vector();

    public BoneBlueprint(double projectResolution, Map<String, Object> boneJSON, HashMap<String, Object> values, Map<String, Map<String, Object>> textureReferences, String modelName, BoneBlueprint parent, SkeletonBlueprint skeletonBlueprint) {
        this.originalBoneName = (String) boneJSON.get("name");
        this.boneName = "freeminecraftmodels:" + modelName.toLowerCase() + "/" + originalBoneName.toLowerCase();
        this.originalModelName = modelName;
        this.parent = parent;
        if (originalBoneName.startsWith("tag_")) nameTag = true;
        //Some bones should not be displayed because they just hold metadata
        if (originalBoneName.startsWith("b_")) isDisplayModel = false;
        //Initialize child data
        processChildren(boneJSON, modelName, projectResolution, values, textureReferences, skeletonBlueprint);
        processBoneValues(boneJSON);
        adjustCubes();
        String filename = originalBoneName.toLowerCase().replace(" ", "_");
        generateAndWriteCubes(filename, textureReferences, modelName);
        //Add bone to the map
        skeletonBlueprint.getBoneMap().put(originalBoneName, this);
    }

    private void adjustCubes() {
        if (cubeBlueprintChildren.isEmpty()) return;
        //Get the lowest and highest coordinates of the cube, shifted by the origin, adjusted by the scaling of the resource pack to fit the rest of the cube data
        Double lowestX = null, lowestY = null, lowestZ = null, highestX = null, highestY = null, highestZ = null;
        for (CubeBlueprint cubeBlueprint : cubeBlueprintChildren) {
            if (lowestX == null || cubeBlueprint.getFrom().getX() < lowestX) lowestX = cubeBlueprint.getFrom().getX();
            if (lowestY == null || cubeBlueprint.getFrom().getY() < lowestY) lowestY = cubeBlueprint.getFrom().getY();
            if (lowestZ == null || cubeBlueprint.getFrom().getZ() < lowestZ) lowestZ = cubeBlueprint.getFrom().getZ();
            if (highestX == null || cubeBlueprint.getTo().getX() > highestX) highestX = cubeBlueprint.getTo().getX();
            if (highestY == null || cubeBlueprint.getTo().getY() > highestY) highestY = cubeBlueprint.getTo().getY();
            if (highestZ == null || cubeBlueprint.getTo().getZ() > highestZ) highestZ = cubeBlueprint.getTo().getZ();
        }

        //Get the size of the entire bone (not necessarily just one cube), in the scaled down version
        double xSize = Math.abs(highestX - lowestX);
        double ySize = Math.abs(highestY - lowestY);
        double zSize = Math.abs(highestZ - lowestZ);

        //If the bone exceeds (16+32)*4 in size (remember, this is scaled) then it is too large to be rendered
        //This is because the lowest value is -16, the highest is +32 and the model is scaled up by 4x
        if (xSize > 48 * MODEL_SCALE || ySize > 48 * MODEL_SCALE || zSize > 48 * MODEL_SCALE) {
            Developer.warn("Model " + originalModelName + " has a boneBlueprint or set of cubes which exceeds the maximum size! Either make the cubes smaller, less far apart or split them up into multiple bones!");
        }

        //Find new lowest point in the cube, and shift it by the amount of the origin - currently in normal resource pack units
        //Note: 8 is the midpoint between -16 and +32, which are the limits for a resource pack
        //Important: this is normal resource pack units, not scaled down units
        double newLowestX = 8 - xSize / 2D;
        double newLowestY = 8 - ySize / 2D;
        double newLowestZ = 8 - zSize / 2D;

        //The difference between the lowest point - in normal resource pack units
        double xOffset = lowestX - newLowestX;
        double yOffset = lowestY - newLowestY;
        double zOffset = lowestZ - newLowestZ;

        //Get offset based on the cube shift, this is technically in normal resource pack units as the shifts need to be done in that unit size
        cubeOffset = new Vector(xOffset, yOffset, zOffset);

        //Uniformly shift the cubes and finalize writing their JSON data
        for (CubeBlueprint cubeBlueprint : cubeBlueprintChildren) {
            cubeBlueprint.setBoneOffset(cubeOffset);
            cubeBlueprint.shiftPosition();
            cubeBlueprint.shiftRotation();
        }

        //This should shift the origin so it is adequately centered on 8,8,8
        cubeOffset.add(new Vector(8, 8, 8));

        //todo this is cursed, and needs to be cleaned up
        cubeOffset.multiply(MODEL_SCALE * -1);

        //Leather armor seems to be placed 4 blocks too high on an armor stand, this centers it
        armorStandModelSpaceOriginOffset.subtract(new Vector(0, -4 * 1.6, 0));

        armorStandModelSpaceOriginOffset.add(cubeOffset);

        displayEntityModelSpaceOriginOffset.add(cubeOffset);
    }

    private void processBoneValues(Map<String, Object> boneJSON) {
        setOrigin(boneJSON);
        calculateArmorStandOffsetFromModel();
        calculateDisplayEntityOffsetFromModel();
        if (cubeBlueprintChildren.isEmpty()) return;
        setBoneRotation(boneJSON);
    }

    private void processChildren(Map<String, Object> boneJSON, String modelName, double projectResolution, HashMap<String, Object> values, Map<String, Map<String, Object>> textureReferences, SkeletonBlueprint skeletonBlueprint) {
        //Bones can contain two types of children: bones and cubes
        //Bones exist solely for containing cubes
        //Cubes store the relevant visual data
        ArrayList<?> childrenValues = (ArrayList<?>) boneJSON.get("children");
        for (Object object : childrenValues) {
            if (object instanceof String) {
                //Case for object being a cube
                CubeBlueprint cubeBlueprint = new CubeBlueprint(projectResolution, (Map<String, Object>) values.get(object));
                if (cubeBlueprint.isValidatedData()) cubeBlueprintChildren.add(cubeBlueprint);
                else Developer.warn("Model " + modelName + " has an invalid configuration for its cubes!");
            } else {
                //Case for object being a boneBlueprint
                boneBlueprintChildren.add(new BoneBlueprint(projectResolution, (Map<String, Object>) object, values, textureReferences, modelName, this, skeletonBlueprint));
            }
        }
    }

    public Vector getBlockSpaceOrigin() {
        return blockSpaceOrigin.clone();
    }

    /**
     * This centers the model correctly
     */
    public Vector getArmorStandOffsetFromModel() {
        return new Vector(0, -BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0);
    }

    /**
     * This centers the model correctly
     */
    public Vector getDisplayModelOffsetFromModel() {
        return new Vector(0, 0.5, 0);
    }

    private void setBoneRotation(Map<?, ?> boneJSON) {
        boneRotation = boneJSON.get("rotation");
        if (boneRotation == null) return;
        List<Double> rotations = (List<Double>) boneRotation;
        //todo: this requires a negative x and y value. I don't know why. But I really need to figure it out.
        armorStandHeadRotation = new EulerAngle(-Math.toRadians(rotations.get(0)), -Math.toRadians(rotations.get(1)), Math.toRadians(rotations.get(2)));
        displayEntityBoneRotation = new EulerAngle(Math.toRadians(rotations.get(0)), Math.toRadians(rotations.get(1)), Math.toRadians(rotations.get(2)));
        originsForDisplayEntityLocationShiftBasedOnRotation = new Vector(rotations.get(0), -rotations.get(1), rotations.get(2));
    }

    private void setOrigin(Map<String, Object> boneJSON) {
        Object obj = boneJSON.get("origin");
        if (obj == null) return;
        List<Double> origins = (List<Double>) obj;
        //This is the origin in "real space", meaning it is adjusted to the in-game unit size (16x larger than model space)
        blockSpaceOrigin = new Vector(
                origins.get(0) / 16d,
                origins.get(1) / 16d,
                origins.get(2) / 16d);
        //This in the origin in "model space", meaning it is adjusted to the resource pack / Blockbench unit size (16x smaller than real space)
        //It also adjusts the scaling to fit the head
        armorStandModelSpaceOriginOffset = new Vector(
                origins.get(0) * 1.6,
                origins.get(1) * 1.6,
                origins.get(2) * 1.6);
    }

    private void generateAndWriteCubes(String filename, Map<String, Map<String, Object>> textureReferences, String modelName) {
        if (filename.equalsIgnoreCase("hitbox") || filename.equalsIgnoreCase("tag_name") || cubeBlueprintChildren.isEmpty())
            return;
        Map<String, Object> textureReferencesClone = new HashMap<>(textureReferences);
        setDisplay(textureReferencesClone);
        writeCubes(textureReferencesClone);
        writeFile(modelName, filename, textureReferencesClone);
    }

    private String getModelDirectory(String modelName) {
        return MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "models" + File.separatorChar + modelName;
    }

    private void writeCubes(Map<String, Object> textureReferencesClone) {
        List<Object> cubeJSONs = new ArrayList<>();

        //This generates the JSON for each individual cubes
        for (CubeBlueprint cubeBlueprint : cubeBlueprintChildren)
            cubeJSONs.add(cubeBlueprint.getCubeJSON());

        textureReferencesClone.put("elements", cubeJSONs);
    }

    /**
     * Centers the model in the armor stand
     *
     * @param textureReferencesClone Map to modify
     */
    private void setDisplay(Map<String, Object> textureReferencesClone) {
        textureReferencesClone.put("display", Map.of(
                "head", Map.of(
                        "translation", List.of(
                                -armorStandModelSpaceOriginOffset.getX(),
                                -armorStandModelSpaceOriginOffset.getY(),
                                -armorStandModelSpaceOriginOffset.getZ()),
                        "scale", List.of(MODEL_SCALE, MODEL_SCALE, MODEL_SCALE))
        ));
    }

    private void writeFile(String modelName, String filename, Map<String, Object> boneJSON) {
        String modelDirectory = getModelDirectory(modelName);
        Gson gson = new Gson();
        try {
            FileUtils.writeStringToFile(new File(modelDirectory + File.separatorChar + filename + ".json"), gson.toJson(boneJSON), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Developer.warn("Failed to write boneBlueprint resource packs for boneBlueprint " + filename + "!");
            throw new RuntimeException(e);
        }
    }

    private void calculateArmorStandOffsetFromModel() {
        armorStandOffsetFromModel = getBlockSpaceOrigin().subtract(new Vector(0, BoneBlueprint.getARMOR_STAND_PIVOT_POINT_HEIGHT(), 0));
    }

    private void calculateDisplayEntityOffsetFromModel() {
        displayEntityStandOffsetFromModel = getBlockSpaceOrigin().add(new Vector(0, 0.5, 0));
    }

}
