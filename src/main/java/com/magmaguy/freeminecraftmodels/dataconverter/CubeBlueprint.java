package com.magmaguy.freeminecraftmodels.dataconverter;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.Round;
import com.magmaguy.magmacore.util.VersionChecker;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CubeBlueprint {
    @Getter
    private final Map<String, Object> cubeJSON;
    @Getter
    private Vector3f to;
    @Getter
    private Vector3f from;
    @Getter
    private boolean validatedData = false;
    @Getter
    @Setter
    private Vector3f boneOffset = new Vector3f();
    //Fun bug, if a single face does not have a texture but the rest of the cube does it breaks Minecraft.
    //Null means uninitialized. False means initialized with no texture. True means initialized with a texture.
    private Boolean textureDataExists = null;
    private String modelName;
    private double resolutionWidth;
    private double resolutionHeight;

    public CubeBlueprint(List<ParsedTexture> parsedTextures, Map<String, Object> cubeJSON, String modelName, double resolutionWidth, double resolutionHeight) {
        this.cubeJSON = cubeJSON;
        this.modelName = modelName;
        this.resolutionWidth = resolutionWidth;
        this.resolutionHeight = resolutionHeight;

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
        cubeJSON.remove("light_emission");
        // NOTE: don't remove "inflate" here; we will read & bake it in, then remove it.

        // process face textures ...
        processFace(parsedTextures, (Map<String, Object>) cubeJSON.get("faces"), "north", modelName);
        processFace(parsedTextures, (Map<String, Object>) cubeJSON.get("faces"), "east", modelName);
        processFace(parsedTextures, (Map<String, Object>) cubeJSON.get("faces"), "south", modelName);
        processFace(parsedTextures, (Map<String, Object>) cubeJSON.get("faces"), "west", modelName);
        processFace(parsedTextures, (Map<String, Object>) cubeJSON.get("faces"), "up", modelName);
        processFace(parsedTextures, (Map<String, Object>) cubeJSON.get("faces"), "down", modelName);

        ArrayList<Double> fromList = (ArrayList<Double>) cubeJSON.get("from");
        if (fromList == null) {
            Logger.warn("Model " + modelName + " has a cube with no from position. This is not allowed. The model will appear with the debug black and purple cube texture until fixed.");
            return;
        }
        from = new Vector3f(
                Round.fourDecimalPlaces(fromList.get(0).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER),
                Round.fourDecimalPlaces(fromList.get(1).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER),
                Round.fourDecimalPlaces(fromList.get(2).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER));

        ArrayList<Double> toList = (ArrayList<Double>) cubeJSON.get("to");
        from = new Vector3f(
                fromList.get(0).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER,
                fromList.get(1).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER,
                fromList.get(2).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER);
        to = new Vector3f(
                toList.get(0).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER,
                toList.get(1).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER,
                toList.get(2).floatValue() * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER);

        // 🔹 NEW: bake in inflate (if present)
        applyInflate();

        validatedData = true;
    }

    /**
     * Applies Blockbench-style "inflate": expands/contracts the cube equally on all sides.
     * Positive values grow the cube; negative values shrink it. The value is in model units,
     * so we scale it by the same head multiplier used for coordinates.
     */
    private void applyInflate() {
        Object inflateObj = cubeJSON.get("inflate");
        if (inflateObj == null) return;

        double inflateRaw = ((Number) inflateObj).doubleValue();
        if (Math.abs(inflateRaw) < 1e-9) { // effectively zero
            cubeJSON.remove("inflate");
            return;
        }

        // Scale inflate by the same multiplier as from/to
        float inflate = Round.fourDecimalPlaces((float) (inflateRaw * BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER));

        // Expand/contract uniformly on all axes
        Vector3f newFrom = new Vector3f(from).sub(inflate, inflate, inflate);
        Vector3f newTo = new Vector3f(to).add(inflate, inflate, inflate);

        // Guard against inversion if negative inflate over-shrinks the box
        if (newFrom.x > newTo.x || newFrom.y > newTo.y || newFrom.z > newTo.z) {
            Logger.warn("Inflate on model " + modelName + " is too negative and would invert the cube. "
                    + "Clamping to maintain valid geometry.");
            // Clamp each axis independently
            float eps = 0.0001f;
            if (newFrom.x > newTo.x) {
                float mid = (from.x + to.x) * 0.5f;
                newFrom.x = mid - eps;
                newTo.x = mid + eps;
            }
            if (newFrom.y > newTo.y) {
                float mid = (from.y + to.y) * 0.5f;
                newFrom.y = mid - eps;
                newTo.y = mid + eps;
            }
            if (newFrom.z > newTo.z) {
                float mid = (from.z + to.z) * 0.5f;
                newFrom.z = mid - eps;
                newTo.z = mid + eps;
            }
        }

        from.set(newFrom);
        to.set(newTo);

        // Reflect into JSON immediately
        cubeJSON.put("from", List.of(
                Round.fourDecimalPlaces(from.get(0)),
                Round.fourDecimalPlaces(from.get(1)),
                Round.fourDecimalPlaces(from.get(2))));
        cubeJSON.put("to", List.of(
                Round.fourDecimalPlaces(to.get(0)),
                Round.fourDecimalPlaces(to.get(1)),
                Round.fourDecimalPlaces(to.get(2))));

        // We've baked it into geometry; drop the key so downstream doesn't try to use it.
        cubeJSON.remove("inflate");
    }


    private void processFace(List<ParsedTexture> parsedTextures, Map<String, Object> map, String faceName, String modelName) {
        setTextureData(parsedTextures, (Map<String, Object>) map.get(faceName), modelName);
    }

    private void setTextureData(List<ParsedTexture> parsedTextures, Map<String, Object> map, String modelName) {
        if (map == null || map.get("texture") == null) {
            if (textureDataExists != null && textureDataExists)
                Logger.warn("A cube in the model " + modelName + " has a face which does not have a texture while the rest of the cube has a texture. Minecraft does not allow this. Go through every cube in that model and make sure they all either have or do not have textures on all faces, but don't mix having and not having textures for the same cube. The model will appear with the debug black and purple cube texture until fixed.");
            textureDataExists = false;
            return;
        }
        if (textureDataExists != null && !textureDataExists)
            Logger.warn("A cube in the model " + modelName + " has a face which does not have a texture while the rest of the cube has a texture. Minecraft does not allow this. Go through every cube in that model and make sure they all either have or do not have textures on all faces, but don't mix having and not having textures for the same cube. The model will appear with the debug black and purple cube texture until fixed.");
        textureDataExists = true;
        Double textureDouble = (Double) map.get("texture");
        int textureValue = (int) Math.round(textureDouble);
        map.put("texture", "#" + textureValue);
        map.put("tintindex", 0);
        if (map.get("rotation") == null)
            map.put("rotation", 0);
        else
            map.put("rotation", ((Double) map.get("rotation")).floatValue());
        ArrayList<Double> originalUV = (ArrayList<Double>) map.get("uv");
        // UV coordinates in bbmodel are in the space defined by resolution, not actual texture size
        // Minecraft expects UVs in 16x16 space, so we scale from resolution space to 16x16
        double uvWidthMultiplier = 16.0 / resolutionWidth;
        double uvHeightMultiplier = 16.0 / resolutionHeight;
        map.put("uv", List.of(
                Round.fourDecimalPlaces(originalUV.get(0) * uvWidthMultiplier),
                Round.fourDecimalPlaces(originalUV.get(1) * uvHeightMultiplier),
                Round.fourDecimalPlaces(originalUV.get(2) * uvWidthMultiplier),
                Round.fourDecimalPlaces(originalUV.get(3) * uvHeightMultiplier)));
    }

    public void shiftPosition() {
        from.sub(boneOffset);
        to.sub(boneOffset);
        cubeJSON.put("from", List.of(from.get(0), from.get(1), from.get(2)));
        cubeJSON.put("to", List.of(to.get(0), to.get(1), to.get(2)));
    }

    public void shiftRotation() {
        if (cubeJSON.get("origin") == null) return;
        Map<String, Object> newRotationData = new HashMap<>();

        double scaleFactor = BoneBlueprint.ARMOR_STAND_HEAD_SIZE_MULTIPLIER;

        //Adjust the origin
        double xOrigin, yOrigin, zOrigin;
        List<Double> originData = (ArrayList<Double>) cubeJSON.get("origin");
        xOrigin = originData.get(0) * scaleFactor - boneOffset.get(0);
        yOrigin = originData.get(1) * scaleFactor - boneOffset.get(1);
        zOrigin = originData.get(2) * scaleFactor - boneOffset.get(2);

        double angle = 0;
        String axis = "x";

        if (cubeJSON.get("rotation") != null) {
            List<Double> rotations = (List<Double>) cubeJSON.get("rotation");
            for (int i = rotations.size() - 1; i >= 0; i--) {
                if (rotations.get(i) != 0) {
                    angle = Round.fourDecimalPlaces(rotations.get(i));
                    switch (i) {
                        case 0 -> axis = "x";
                        case 1 -> axis = "y";
                        case 2 -> axis = "z";
                        default -> Logger.warn("Unexpected amount of rotation axes!");
                    }
                }
            }
        }

        // Decompose rotation into base transform + allowed remainder
        RotationDecomposition decomp = decomposeRotation(angle);

        if (decomp.baseRotation != 0) {
            // Transform the geometry for the base rotation
            transformCubeGeometry(decomp.baseRotation, axis, xOrigin, yOrigin, zOrigin);
            // Use the remainder as the actual rotation
            newRotationData.put("angle", decomp.remainder);
            newRotationData.put("axis", axis);
        } else {
            // Use normal rotation for already allowed angles
            newRotationData.put("angle", angle);
            newRotationData.put("axis", axis);
        }

        newRotationData.put("origin", List.of(xOrigin, yOrigin, zOrigin));
        cubeJSON.put("rotation", newRotationData);
        cubeJSON.remove("origin");
    }

    private RotationDecomposition decomposeRotation(double angle) {
        RotationDecomposition result = new RotationDecomposition();

        // Normalize angle to -180 to 180 range
        while (angle > 180) angle -= 360;
        while (angle < -180) angle += 360;

        // MC 1.21.6+ supports arbitrary rotations from -45 to 45 degrees
        boolean supportsArbitraryRotations = !VersionChecker.serverVersionOlderThan(21, 6);

        // Define allowed Minecraft rotations (discrete values for older versions)
        double[] allowedRotations = {-45, -22.5, 0, 22.5, 45};

        // Try each base rotation and see if remainder is allowed
        double[] baseRotations = {0, 90, -90, 180, -180};

        for (double base : baseRotations) {
            double remainder = angle - base;
            // Normalize remainder
            while (remainder > 180) remainder -= 360;
            while (remainder < -180) remainder += 360;

            if (supportsArbitraryRotations) {
                // MC 1.21.6+: Accept any remainder within ±45 degrees
                if (remainder >= -45 && remainder <= 45) {
                    result.baseRotation = base;
                    result.remainder = remainder;
                    return result;
                }
            } else {
                // Older versions: Check if remainder is an allowed rotation (with small tolerance)
                for (double allowed : allowedRotations) {
                    if (Math.abs(remainder - allowed) < 0.01) {
                        result.baseRotation = base;
                        result.remainder = allowed;
                        return result;
                    }
                }
            }
        }

        // If no valid decomposition found, just use the angle as-is
        // (this shouldn't happen for valid 22.5 degree increments)
        Logger.warn("Could not decompose rotation angle " + angle + " into base + allowed remainder for model name " + modelName);
        result.baseRotation = 0;
        result.remainder = angle;
        return result;
    }

    private static class RotationDecomposition {
        double baseRotation;  // 0, 90, -90, 180, or -180
        double remainder;     // -45 to 45 (arbitrary on MC 1.21.6+, discrete 22.5° increments on older versions)
    }

    /**
     * Transforms cube geometry to handle rotations not natively supported by Minecraft.
     * This allows us to support all rotations in 22.5° increments:
     * -180°, -157.5°, -135°, -112.5°, -90°, -67.5°, -45°, -22.5°, 0°, 22.5°, 45°, 67.5°, 90°, 112.5°, 135°, 157.5°, 180°
     *
     * Minecraft natively supports: -45°, -22.5°, 0°, 22.5°, 45°
     * We transform the geometry for: -180°, -90°, 90°, 180°
     * All other angles are achieved by combining a base transformation with a native rotation.
     *
     * Examples:
     * - 67.5° = 90° (geometry transform) + -22.5° (native rotation)
     * - 135° = 90° (geometry transform) + 45° (native rotation)
     * - -112.5° = -90° (geometry transform) + -22.5° (native rotation)
     * - -157.5° = -180° (geometry transform) + 22.5° (native rotation)
     */
    private void transformCubeGeometry(double angle, String axis, double originX, double originY, double originZ) {
        // Get current coordinates
        float fromX = from.x, fromY = from.y, fromZ = from.z;
        float toX = to.x, toY = to.y, toZ = to.z;

        // Transform coordinates around origin
        Vector3f newFrom = new Vector3f();
        Vector3f newTo = new Vector3f();

        // Translate to origin
        fromX -= originX; fromY -= originY; fromZ -= originZ;
        toX -= originX; toY -= originY; toZ -= originZ;

        // Apply rotation transformation
        switch (axis.toLowerCase()) {
            case "x" -> {
                if (Math.abs(angle - 90) < 0.01) {
                    // 90 degrees around X: y' = -z, z' = y
                    newFrom.set(fromX, -fromZ, fromY);
                    newTo.set(toX, -toZ, toY);
                } else if (Math.abs(angle + 90) < 0.01) {
                    // -90 degrees around X: y' = z, z' = -y
                    newFrom.set(fromX, fromZ, -fromY);
                    newTo.set(toX, toZ, -toY);
                } else if (Math.abs(angle - 180) < 0.01 || Math.abs(angle + 180) < 0.01) {
                    // 180 degrees around X: y' = -y, z' = -z
                    newFrom.set(fromX, -fromY, -fromZ);
                    newTo.set(toX, -toY, -toZ);
                }
            }
            case "y" -> {
                if (Math.abs(angle - 90) < 0.01) {
                    // 90 degrees around Y: x' = z, z' = -x
                    newFrom.set(fromZ, fromY, -fromX);
                    newTo.set(toZ, toY, -toX);
                } else if (Math.abs(angle + 90) < 0.01) {
                    // -90 degrees around Y: x' = -z, z' = x
                    newFrom.set(-fromZ, fromY, fromX);
                    newTo.set(-toZ, toY, toX);
                } else if (Math.abs(angle - 180) < 0.01 || Math.abs(angle + 180) < 0.01) {
                    // 180 degrees around Y: x' = -x, z' = -z
                    newFrom.set(-fromX, fromY, -fromZ);
                    newTo.set(-toX, toY, -toZ);
                }
            }
            case "z" -> {
                if (Math.abs(angle - 90) < 0.01) {
                    // 90 degrees around Z: x' = -y, y' = x
                    newFrom.set(-fromY, fromX, fromZ);
                    newTo.set(-toY, toX, toZ);
                } else if (Math.abs(angle + 90) < 0.01) {
                    // -90 degrees around Z: x' = y, y' = -x
                    newFrom.set(fromY, -fromX, fromZ);
                    newTo.set(toY, -toX, toZ);
                } else if (Math.abs(angle - 180) < 0.01 || Math.abs(angle + 180) < 0.01) {
                    // 180 degrees around Z: x' = -x, y' = -y
                    newFrom.set(-fromX, -fromY, fromZ);
                    newTo.set(-toX, -toY, toZ);
                }
            }
        }

        // Translate back from origin
        newFrom.add((float)originX, (float)originY, (float)originZ);
        newTo.add((float)originX, (float)originY, (float)originZ);

        // Ensure from is always the minimum corner and to is the maximum
        from.set(
                Math.min(newFrom.x, newTo.x),
                Math.min(newFrom.y, newTo.y),
                Math.min(newFrom.z, newTo.z)
        );
        to.set(
                Math.max(newFrom.x, newTo.x),
                Math.max(newFrom.y, newTo.y),
                Math.max(newFrom.z, newTo.z)
        );

        // Update the JSON data
        cubeJSON.put("from", List.of(from.get(0), from.get(1), from.get(2)));
        cubeJSON.put("to", List.of(to.get(0), to.get(1), to.get(2)));

        // Remap faces according to the rotation
        remapFaces(angle, axis);
    }

    private void remapFaces(double angle, String axis) {
        Map<String, Object> faces = (Map<String, Object>) cubeJSON.get("faces");
        if (faces == null) return;

        Map<String, Object> originalFaces = new HashMap<>(faces);

        switch (axis.toLowerCase()) {
            case "x" -> {
                if (Math.abs(angle - 90) < 0.01) {
                    // 90 degrees around X
                    faces.put("north", originalFaces.get("down"));
                    faces.put("up", originalFaces.get("north"));
                    faces.put("south", originalFaces.get("up"));
                    faces.put("down", originalFaces.get("south"));
                    // East and West remain the same but might need UV rotation
                    rotateFaceUV(faces, "east", 90);
                    rotateFaceUV(faces, "west", -90);
                } else if (Math.abs(angle + 90) < 0.01) {
                    // -90 degrees around X
                    faces.put("north", originalFaces.get("up"));
                    faces.put("down", originalFaces.get("north"));
                    faces.put("south", originalFaces.get("down"));
                    faces.put("up", originalFaces.get("south"));
                    // East and West remain the same but might need UV rotation
                    rotateFaceUV(faces, "east", -90);
                    rotateFaceUV(faces, "west", 90);
                } else if (Math.abs(angle - 180) < 0.01 || Math.abs(angle + 180) < 0.01) {
                    // 180 degrees around X
                    faces.put("north", originalFaces.get("south"));
                    faces.put("south", originalFaces.get("north"));
                    faces.put("up", originalFaces.get("down"));
                    faces.put("down", originalFaces.get("up"));
                    // East and West get flipped
                    rotateFaceUV(faces, "east", 180);
                    rotateFaceUV(faces, "west", 180);
                }
            }
            case "y" -> {
                if (Math.abs(angle - 90) < 0.01) {
                    // 90 degrees around Y
                    faces.put("north", originalFaces.get("west"));
                    faces.put("east", originalFaces.get("north"));
                    faces.put("south", originalFaces.get("east"));
                    faces.put("west", originalFaces.get("south"));
                    // Up and Down remain but need rotation
                    rotateFaceUV(faces, "up", 90);
                    rotateFaceUV(faces, "down", -90);
                } else if (Math.abs(angle + 90) < 0.01) {
                    // -90 degrees around Y
                    faces.put("north", originalFaces.get("east"));
                    faces.put("west", originalFaces.get("north"));
                    faces.put("south", originalFaces.get("west"));
                    faces.put("east", originalFaces.get("south"));
                    // Up and Down remain but need rotation
                    rotateFaceUV(faces, "up", -90);
                    rotateFaceUV(faces, "down", 90);
                } else if (Math.abs(angle - 180) < 0.01 || Math.abs(angle + 180) < 0.01) {
                    // 180 degrees around Y
                    faces.put("north", originalFaces.get("south"));
                    faces.put("south", originalFaces.get("north"));
                    faces.put("east", originalFaces.get("west"));
                    faces.put("west", originalFaces.get("east"));
                    // Up and Down need 180 rotation
                    rotateFaceUV(faces, "up", 180);
                    rotateFaceUV(faces, "down", 180);
                }
            }
            case "z" -> {
                if (Math.abs(angle - 90) < 0.01) {
                    // 90 degrees around Z
                    faces.put("up", originalFaces.get("west"));
                    faces.put("east", originalFaces.get("up"));
                    faces.put("down", originalFaces.get("east"));
                    faces.put("west", originalFaces.get("down"));
                    // North and South remain but need rotation
                    rotateFaceUV(faces, "north", 90);
                    rotateFaceUV(faces, "south", -90);
                } else if (Math.abs(angle + 90) < 0.01) {
                    // -90 degrees around Z
                    faces.put("up", originalFaces.get("east"));
                    faces.put("west", originalFaces.get("up"));
                    faces.put("down", originalFaces.get("west"));
                    faces.put("east", originalFaces.get("down"));
                    // North and South remain but need rotation
                    rotateFaceUV(faces, "north", -90);
                    rotateFaceUV(faces, "south", 90);
                } else if (Math.abs(angle - 180) < 0.01 || Math.abs(angle + 180) < 0.01) {
                    // 180 degrees around Z
                    faces.put("up", originalFaces.get("down"));
                    faces.put("down", originalFaces.get("up"));
                    faces.put("east", originalFaces.get("west"));
                    faces.put("west", originalFaces.get("east"));
                    // North and South need 180 rotation
                    rotateFaceUV(faces, "north", 180);
                    rotateFaceUV(faces, "south", 180);
                }
            }
        }
    }

    //todo: this doesn't actually work correctly for +90 -90 rotations, maybe 180 as well
    private void rotateFaceUV(Map<String, Object> faces, String faceName, double rotation) {
        Map<String, Object> face = (Map<String, Object>) faces.get(faceName);
        if (face == null) return;

        // Get current UV coordinates
        List<Double> uv = (List<Double>) face.get("uv");
        if (uv == null || uv.size() != 4) return;

        double u1 = uv.get(0), v1 = uv.get(1);
        double u2 = uv.get(2), v2 = uv.get(3);

        // Normalize rotation to 0-360
        int normalizedRotation = ((int)rotation % 360 + 360) % 360;

        // Rotate UV coordinates
        List<Double> newUV;
        switch (normalizedRotation) {
            case 90 -> {
                // Rotate 90 degrees clockwise
                newUV = List.of(u1, v2, u2, v1);
                face.put("rotation", 90);
            }
            case 180 -> {
                // Rotate 180 degrees
                newUV = List.of(u2, v2, u1, v1);
                face.put("rotation", 180);
            }
            case 270 -> {
                // Rotate 270 degrees clockwise (90 counter-clockwise)
                newUV = List.of(u2, v1, u1, v2);
                face.put("rotation", 270);
            }
            default -> {
                // No rotation
                newUV = uv;
            }
        }

        face.put("uv", newUV);
    }
}