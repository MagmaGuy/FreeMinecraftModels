package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.freeminecraftmodels.utils.Developer;
import com.magmaguy.freeminecraftmodels.utils.Round;
import lombok.Getter;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class HitboxBlueprint {
    private final String modelName;
    @Getter
    private Vector modelOffset = new Vector();
    private BoneBlueprint parent = null;
    @Getter
    private double width;
    @Getter
    private double height;

    public HitboxBlueprint(Map<String, Object> boneJSON, HashMap<String, Object> values, String modelName, BoneBlueprint parent) {
        this.parent = parent;
        this.modelName = modelName;
        ArrayList<?> childrenValues = (ArrayList<?>) boneJSON.get("children");
        if (childrenValues.size() > 1) {
            Developer.warn("Model " + modelName + " has more than one value defining a hitbox! Only the first cube will be used to define the hitbox.");
        }
        if (childrenValues.isEmpty()) {
            Developer.warn("Model " + modelName + " has a hitbox bone but no hitbox cube! This means the hitbox won't be able to generate correctly!");
            return;
        }
        if (childrenValues.get(0) instanceof String) {
            parseCube((Map<String, Object>) values.get(childrenValues.get(0)));
        } else {
            Developer.warn("Model " + modelName + " has an invalid hitbox! The hitbox bone should only have one cube in it defining its boundaries.");
        }

    }

    public Vector getModelOffset() {
        return modelOffset.clone();
    }

    private void parseCube(Map<String, Object> cubeJSON) {
        double scaleFactor = .16D / 2.5;
        ArrayList<Double> fromList = (ArrayList<Double>) cubeJSON.get("from");
        Vector from = new Vector(Round.fourDecimalPlaces(fromList.get(0) * scaleFactor), Round.fourDecimalPlaces(fromList.get(1) * scaleFactor), Round.fourDecimalPlaces(fromList.get(2) * scaleFactor));
        ArrayList<Double> toList = (ArrayList<Double>) cubeJSON.get("to");
        Vector to = new Vector(Round.fourDecimalPlaces(toList.get(0) * scaleFactor), Round.fourDecimalPlaces(toList.get(1) * scaleFactor), Round.fourDecimalPlaces(toList.get(2) * scaleFactor));
        width = Math.abs(to.getX() - from.getX());
        if (Math.abs(Math.abs(to.getZ() - from.getZ()) - width) > .1)
            Developer.warn("Model " + modelName + " has a different X and Z value for the hitbox! x=" + width + " / z=" + Math.abs(to.getZ() - from.getZ()) + " ! Only the X value will be used for the hitbox.");
        height = Math.abs(from.getY() - to.getY());
        //modelOffset = new Vector(from.getX() + width / 2D, from.getY() + height / 2D, from.getZ() + width / 2D);
        modelOffset = new Vector(0, 0, 0);
    }
}
