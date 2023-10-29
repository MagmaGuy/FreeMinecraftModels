package com.magmaguy.freeminecraftmodels.dataconverter;

import com.google.gson.Gson;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.entities.ModelArmorStand;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.io.FileUtils;
import org.apache.commons.math3.complex.Quaternion;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Bone {
    private static final double armorStandheadSizeMultiplier = 0.4D;
    private static final double modelScale = 4D;
    private static final double armorStandPivotPointHeight = 1.53D;
    public static NamespacedKey nameTagKey = new NamespacedKey(MetadataHandler.PLUGIN, "NameTag");
    @Getter
    private final List<Bone> boneChildren = new ArrayList<>();
    @Getter
    private final List<Cube> cubeChildren = new ArrayList<>();
    @Getter
    private final String boneName;
    private final Bone parent;
    private final double yOffsetFromArmorStandHeight = 28d;
    @Getter
    @Setter
    private Integer modelID = null;
    private EulerAngle armorStandHeadRotation = new EulerAngle(0, 0, 0);
    @Getter
    private Vector blockSpaceOrigin = new Vector();
    private Vector modelSpaceOrigin = new Vector();
    @Getter
    private boolean nameTag = false;
    //This is used as the anchor in the world for animating position movements, while anchoring them to a specific coordinate.
    //If the underlying entity moves then this point moves, otherwise it stays the same as translation animations play out.
    private Location entityBoneOriginUnanimated = null;
    @Getter
    private ArmorStand armorStand = null;
    @Getter
    private EulerAngle tickRotation = null;
    @Getter
    private Vector tickPosition = new Vector(0, 0, 0);

    public Bone(double projectResolution,
                Map<String, Object> boneJSON,
                HashMap<String, Object> values,
                Map<String, Map<String, Object>> textureReferences,
                String modelName,
                Bone parent,
                Skeleton skeleton) {
        this.boneName = "freeminecraftmodels:" + modelName.toLowerCase() + "/" + ((String) boneJSON.get("name")).toLowerCase();
        if (boneName.contains("tag_")) {
            nameTag = true;
        }
        this.parent = parent;
        setBoneRotation(boneJSON);
        setOrigin(boneJSON);
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
                boneChildren.add(new Bone(projectResolution, (Map<String, Object>) object, values, textureReferences, modelName, this, skeleton));
            }
        }
        generateBoneResourcePackFile(((String) boneJSON.get("name")).toLowerCase().replace(" ", "_"), textureReferences, modelName);
        skeleton.getBoneMap().put((String) boneJSON.get("name"), this);
    }

    /**
     * Returns a clone of the current origin point of the armor stand, in real coordinates
     *
     * @return
     */
    public Location getEntityBoneOriginUnanimated() {
        return entityBoneOriginUnanimated.clone();
    }

    private void setBoneRotation(Map<?, ?> boneJSON) {
        Object obj = boneJSON.get("rotation");
        if (obj == null) return;
        List<Double> rotations = (List<Double>) obj;
        armorStandHeadRotation = new EulerAngle(-Math.toRadians(rotations.get(0)), -Math.toRadians(rotations.get(1)), Math.toRadians(rotations.get(2)));
    }

    public void rotateTo(double newX, double newY, double newZ) {
        if (armorStand == null) return;
        if (tickRotation != null) {
            tickRotation = tickRotation.add(Math.toRadians(newX), Math.toRadians(newY), Math.toRadians(newZ));
        } else {
            tickRotation = new EulerAngle(Math.toRadians(newX), Math.toRadians(newY), Math.toRadians(newZ));
        }
        boneChildren.forEach(boneChild -> boneChild.rotateTo(newX, newY, newZ));
    }

    public void translateTo(double x, double y, double z) {
        if (armorStand == null) return;
        if (tickPosition == null) tickPosition = new Vector(x, y, z);
        else tickPosition.add(new Vector(x, y, z));
        // armorStand.teleport(entityBoneOrigin.clone().add(tickPosition));
        boneChildren.forEach(boneChild -> boneChild.translateTo(x, y, z));
    }

    public void transform() {
        if (tickRotation != null) armorStand.setHeadPose(tickRotation);
        if (tickRotation != null || !tickPosition.isZero()) {
//            //Get the translation transform of the parent as a vector to be added to the current armorstand location
//            Vector animationTranslationOffset = parent.getArmorStand().getLocation().subtract(parent.getEntityBoneOriginUnanimated()).toVector();
//            //Get the translation caused by the rotation of the parent
//            EulerAngle parentRotation = parent.getTickRotation();
//            if (parentRotation != null){
//                Vector fromParentToChild = getEntityBoneOriginUnanimated().subtract(parent.getEntityBoneOriginUnanimated()).toVector();
//                //todo: rotate fromParentToChild by parentRotation
//                // Convert EulerAngle to Quaternion for rotation
//                // Convert EulerAngle to Quaternion manually
//                double yaw = parentRotation.getX();
//                double pitch = parentRotation.getY();
//                double roll = parentRotation.getZ();
//
//                double cy = Math.cos(yaw * 0.5);
//                double sy = Math.sin(yaw * 0.5);
//                double cp = Math.cos(pitch * 0.5);
//                double sp = Math.sin(pitch * 0.5);
//                double cr = Math.cos(roll * 0.5);
//                double sr = Math.sin(roll * 0.5);
//
//                double w = cy * cp * cr + sy * sp * sr;
//                double x = cy * cp * sr - sy * sp * cr;
//                double y = sy * cp * sr + cy * sp * cr;
//                double z = sy * cp * cr - cy * sp * sr;
//
//                Quaternion parentQuaternion = new Quaternion(w, x, y, z);
//
//                // Create a Quaternion from the fromParentToChild vector
//                Quaternion vectorQuaternion = new Quaternion(0, fromParentToChild.getX(), fromParentToChild.getY(), fromParentToChild.getZ());
//
//                // Rotate the vectorQuaternion by parentQuaternion
//                Quaternion rotatedVector = parentQuaternion.multiply(vectorQuaternion).multiply(parentQuaternion.getConjugate());
//
//                // Extract the rotated vector from the resulting Quaternion
//                Vector rotatedVectorResult = new Vector(rotatedVector.getQ0(), rotatedVector.getQ1(), rotatedVector.getQ2());
//
//                // Update fromParentToChild with the rotated vector
//                fromParentToChild = rotatedVectorResult;
//
//                animationTranslationOffset.add(fromParentToChild);
//            }

            armorStand.teleport(getEntityBoneOriginUnanimated().add(tickPosition));
        }

        boneChildren.forEach(Bone::transform);
        tickRotation = null;
        if (!tickPosition.isZero()) tickPosition = new Vector(0, 0, 0);
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
                origins.get(0) * armorStandheadSizeMultiplier * modelScale,
                origins.get(1) * armorStandheadSizeMultiplier * modelScale,
                origins.get(2) * armorStandheadSizeMultiplier * modelScale);
        boneJSON.put("origin", List.of(modelSpaceOrigin.getX(), modelSpaceOrigin.getY(), modelSpaceOrigin.getZ()));
    }

    public ArmorStand generateDisplay(Location location) {
        armorStand = ModelArmorStand.generate(location.clone().add(blockSpaceOrigin).subtract(0, armorStandPivotPointHeight, 0), this);
        armorStand.setHeadPose(armorStandHeadRotation);
        entityBoneOriginUnanimated = armorStand.getLocation();
        if (this.isNameTag()) {
            armorStand.getPersistentDataContainer().set(nameTagKey, PersistentDataType.BYTE, (byte) 0);
        }
        return armorStand;
    }

    private void generateBoneResourcePackFile(String filename, Map<String, Map<String, Object>> textureReferences, String modelName) {
        if (filename.equalsIgnoreCase("hitbox") || filename.equalsIgnoreCase("tag_name") || cubeChildren.isEmpty())
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

        //If the cube exceeds 48 in size (remember, this is scaled) then it is too large to be rendered
        if (xSize > 48 || ySize > 48 || zSize > 48) {
            Developer.warn("Model " + modelName + " has a bone or set of cubes which exceeds the maximum size! Either make the cubes smaller, less far apart or split them up into multiple bones!");
        }

        //This finds how much the bone needs to be shifted around to fit between -16 and +32 to comply with resource pack limitations
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
        for (Cube cube : cubeChildren)
            cubeJSONs.add(cube.generateJSON(offset, modelSpaceOrigin));

        boneJSON.put("elements", cubeJSONs);
    }

    private void setDisplay(Map<String, Object> boneJSON, double xOffset, double yOffset, double zOffset) {
        //The hardcoded numbers adjust the item to the center of the armor stand
        //The offsets are from shifting the model around, when relevant, in order to fit the model boundaries
        //The offsets are scaled down
        boneJSON.put("display", Map.of(
                "head", Map.of("translation",
                        List.of(calculateVisualOffset(32, xOffset, modelSpaceOrigin.getX()),
                                calculateVisualOffset(yOffsetFromArmorStandHeight, yOffset, modelSpaceOrigin.getY()),
                                calculateVisualOffset(32, zOffset, modelSpaceOrigin.getZ())),
                        "scale", List.of(modelScale, modelScale, modelScale))));
    }

    /**
     * This is one of the more delicate bits of math.
     *
     * @param constantAxisOffset     This is the offset required to center something on top of the pivot point of an armor stand head.
     * @param axisOffset             This is the offset obtained from shifting the model around in order to fit within MC limitations
     * @param modelSpaceOriginOffset This is the location of the origin; since the armor stand spawns on the bone location, it's necessary to shift the display to be there
     * @return
     */
    private double calculateVisualOffset(double constantAxisOffset, double axisOffset, double modelSpaceOriginOffset) {
        return constantAxisOffset - axisOffset * modelScale - modelSpaceOriginOffset;
    }

    private void writeFile(String modelDirectory, String filename, Map<String, Object> boneJSON) {
        Gson gson = new Gson();
        try {
            FileUtils.writeStringToFile(new File(modelDirectory + File.separatorChar + filename + ".json"), gson.toJson(boneJSON), StandardCharsets.UTF_8);
        } catch (Exception e) {
            Developer.warn("Failed to write bone resource packs for bone " + filename + "!");
            throw new RuntimeException(e);
        }
    }

}
