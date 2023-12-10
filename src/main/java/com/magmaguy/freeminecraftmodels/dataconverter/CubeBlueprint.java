package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.Developer;
import com.magmaguy.freeminecraftmodels.utils.Round;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CubeBlueprint {
    @Getter
    private final Map<String, Object> cubeJSON;
    @Getter
    private Vector to;
    @Getter
    private Vector from;
    @Getter
    private boolean validatedData = false;
    @Getter
    @Setter
    private Vector boneOffset = new Vector();

    public CubeBlueprint(double projectResolution, Map<String, Object> cubeJSON) {
        this.cubeJSON = cubeJSON;
        //Sanitize data from ModelEngine which is not used by Minecraft resource packs
        cubeJSON.remove("rescale");
        cubeJSON.remove("locked");
        cubeJSON.remove("type");
        cubeJSON.remove("uuid");
        cubeJSON.remove("color");
        cubeJSON.remove("autouv");
        cubeJSON.remove("name");
        cubeJSON.remove("box_uv");
        cubeJSON.remove("render_order");
        cubeJSON.remove("allow_mirror_modeling");
        //process face textures
        processFace(projectResolution, (Map<String, Object>) cubeJSON.get("faces"), "north");
        processFace(projectResolution, (Map<String, Object>) cubeJSON.get("faces"), "east");
        processFace(projectResolution, (Map<String, Object>) cubeJSON.get("faces"), "south");
        processFace(projectResolution, (Map<String, Object>) cubeJSON.get("faces"), "west");
        processFace(projectResolution, (Map<String, Object>) cubeJSON.get("faces"), "up");
        processFace(projectResolution, (Map<String, Object>) cubeJSON.get("faces"), "down");

        //The model is scaled up 4x to reach the maximum theoretical size for large models, thus needs to be scaled correctly here
        //Note that how much it is scaled relies on the scaling of the head slot, it's somewhat arbitrary and just
        //works out that this is the right amount to get the right final size.
        ArrayList<Double> fromList = (ArrayList<Double>) cubeJSON.get("from");
        if (fromList == null) return;
        from = new Vector(
                Round.fourDecimalPlaces(fromList.get(0) * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER),
                Round.fourDecimalPlaces(fromList.get(1) * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER),
                Round.fourDecimalPlaces(fromList.get(2) * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER));
        ArrayList<Double> toList = (ArrayList<Double>) cubeJSON.get("to");
        if (toList == null) return;
        to = new Vector(
                Round.fourDecimalPlaces(toList.get(0) * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER),
                Round.fourDecimalPlaces(toList.get(1) * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER),
                Round.fourDecimalPlaces(toList.get(2) * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER));
        validatedData = true;
    }

    private void processFace(double projectResolution, Map<String, Object> map, String faceName) {
        setTextureData(projectResolution, (Map<String, Object>) map.get(faceName));
    }

    private void setTextureData(double projectResolution, Map<String, Object> map) {
        if (map == null || map.get("texture") == null) return;
        Double textureDouble = (Double) map.get("texture");
        int textureValue = (int) Math.round(textureDouble);
        map.put("texture", "#" + textureValue);
        map.put("tintindex", 0);
        map.put("rotation", 0);
        ArrayList<Double> originalUV = (ArrayList<Double>) map.get("uv");
        //For some reason Minecraft really wants images to be 16x16 so here we scale the UV to fit that
        double uvMultiplier = 16 / projectResolution;
        map.put("uv", List.of(
                Round.fourDecimalPlaces(originalUV.get(0) * uvMultiplier),
                Round.fourDecimalPlaces(originalUV.get(1) * uvMultiplier),
                Round.fourDecimalPlaces(originalUV.get(2) * uvMultiplier),
                Round.fourDecimalPlaces(originalUV.get(3) * uvMultiplier)));
    }

    public void shiftPosition() {
        from.subtract(boneOffset);
        to.subtract(boneOffset);
        cubeJSON.put("from", List.of(from.getX(), from.getY(), from.getZ()));
        cubeJSON.put("to", List.of(to.getX(), to.getY(), to.getZ()));
    }

    public void shiftRotation() {
        if (cubeJSON.get("origin") == null) return;
        Map<String, Object> newRotationData = new HashMap<>();

        double scaleFactor = 0.4;

        //Adjust the origin
        double xOrigin, yOrigin, zOrigin;
        List<Double> originData = (ArrayList<Double>) cubeJSON.get("origin");
        xOrigin = originData.get(0) * scaleFactor - boneOffset.getX();
        yOrigin = originData.get(1) * scaleFactor - boneOffset.getY();
        zOrigin = originData.get(2) * scaleFactor - boneOffset.getZ();
        newRotationData.put("origin", List.of(xOrigin, yOrigin, zOrigin));

        double angle = 0;
        String axis = "x";
        if (cubeJSON.get("rotation") != null) {
            List<Double> rotations = (List<Double>) cubeJSON.get("rotation");
            for (int i = rotations.size() - 1; i >= 0; i--) {
                if (rotations.get(i) != 0) {
                    angle = Round.oneDecimalPlace(rotations.get(i));
                    switch (i) {
                        case 0 -> axis = "x";
                        case 1 -> axis = "y";
                        case 2 -> axis = "z";
                        default -> Developer.warn("Unexpected amount of rotation axes!");
                    }
                }
            }
        }

        newRotationData.put("angle", angle);
        newRotationData.put("axis", axis);
        cubeJSON.put("rotation", newRotationData);
        cubeJSON.remove("origin");
    }
}
