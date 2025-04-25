package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Vector3f;

/**
 * Handles transformations between Blockbench and Minecraft coordinate systems.
 * <p>
 * This class provides a comprehensive solution for the coordinate system mismatch
 * between Blockbench models and Minecraft display entities.
 */
public class CoordinateSystemConverter {

    public static Vector3f convertBlockbenchAnimationToMinecraftRotation(Vector3f rotVec) {
        return new Vector3f(
                rotVec.x,  // Invert X rotation
                rotVec.y,  // Invert Y rotation
                rotVec.z    // Keep Z as is
        );
    }
}