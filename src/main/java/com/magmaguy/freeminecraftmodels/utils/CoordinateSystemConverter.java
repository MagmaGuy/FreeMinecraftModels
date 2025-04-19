package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.Location;
import org.bukkit.util.EulerAngle;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Handles transformations between Blockbench and Minecraft coordinate systems.
 * <p>
 * This class provides a comprehensive solution for the coordinate system mismatch
 * between Blockbench models and Minecraft display entities.
 */
public class CoordinateSystemConverter {

    // Debug flag - enable for detailed logging and in-game visualization
    private static boolean DEBUG_MODE = false;

    /**
     * Enables or disables debug mode
     */
    public static void setDebugMode(boolean debugMode) {
        DEBUG_MODE = debugMode;
    }

    /**
     * Converts a position from Blockbench to Minecraft coordinate system
     *
     * @param position The position in Blockbench coordinates
     * @return The position in Minecraft coordinates
     */
    public static Vector3f convertPosition(Vector3f position) {
        // Create copy to avoid modifying the input
        Vector3f result = new Vector3f(position);

        // Apply the 180° Y rotation for north/south facing difference
        // For positions, this typically means inverting X and Z
//        result.x = -result.x;
//        result.z = -result.z;

        logConversion("Position", position, result);

        return result;
    }

    /**
     * Converts a rotation from Blockbench to Minecraft coordinate system
     *
     * @param rotation     The rotation in Blockbench coordinates (in radians)
     * @param entityType   The type of entity this rotation will be applied to
     * @param rotationType Whether this is a default pose rotation or animation rotation
     * @return The rotation in Minecraft coordinates (in radians)
     */
    public static Vector3f convertRotation(Vector3f rotation, EntityType entityType, RotationType rotationType) {
        // Create a quaternion representing the rotation
        Quaternionf rotQuat = eulerToQuaternion(rotation);

        // Create the 180° Y-rotation quaternion for the facing direction difference
        Quaternionf facingQuat = new Quaternionf().rotationY((float) Math.PI);

        // Create the result quaternion
        Quaternionf resultQuat;

        // Handle different coordinate system conversion based on entity type AND rotation type
        if (entityType == EntityType.ARMOR_STAND) {
            if (rotationType == RotationType.DEFAULT_POSE) {
                // Default pose conversion for armor stands
                Quaternionf invertQuat = new Quaternionf().rotationXYZ(
                        -rotation.x,
                        rotation.y,
                        -rotation.z
                );

                // Combine the transformations
                resultQuat = new Quaternionf(facingQuat).mul(invertQuat);
            } else {
                // Animation rotation conversion for armor stands
                Quaternionf invertQuat = new Quaternionf().rotationXYZ(
                        -rotation.x,
                        -rotation.y,  // Note: Invert Y for animations
                        -rotation.z
                );

                // Combine the transformations
                resultQuat = new Quaternionf(facingQuat).mul(invertQuat);
            }
        } else {
            // For display entities
            if (rotationType == RotationType.DEFAULT_POSE) {
                // Default pose conversion for display entities
                resultQuat = new Quaternionf(facingQuat).mul(rotQuat);
            } else {
                // Animation rotation conversion for display entities
                // For animations, we need to invert Y rotation (different from default poses)
                Quaternionf animRotQuat = new Quaternionf().rotationXYZ(
                        rotation.x,
                        -rotation.y,  // Invert Y for animations
                        rotation.z
                );
                resultQuat = new Quaternionf(facingQuat).mul(animRotQuat);
            }
        }

        // Convert back to Euler angles
        Vector3f result = quaternionToEuler(resultQuat);

        logConversion(
                rotationType == RotationType.DEFAULT_POSE ? "Default Rotation" : "Animation Rotation",
                rotation,
                result
        );

        return result;
    }

    /**
     * Legacy method for backward compatibility
     *
     * @deprecated Use the version with RotationType parameter instead
     */
    public static Vector3f convertRotation(Vector3f rotation, EntityType entityType) {
        // Default to DEFAULT_POSE type when not specified
        return convertRotation(rotation, entityType, RotationType.DEFAULT_POSE);
    }

    /**
     * Converts a scale from Blockbench to Minecraft coordinate system
     *
     * @param scale The scale in Blockbench
     * @return The scale in Minecraft
     */
    public static Vector3f convertScale(Vector3f scale) {
        // Create copy to avoid modifying the input
        Vector3f result = new Vector3f(scale);

        // Scale conversion may or may not need adjustments,
        // but include the method for completeness and future flexibility

        logConversion("Scale", scale, result);

        return result;
    }

    /**
     * Creates a transformation matrix that converts from Blockbench to Minecraft
     * coordinate system.
     *
     * @return A transformation matrix for coordinate conversion
     */
    public static Matrix4f createBlockbenchToMinecraftMatrix() {
        Matrix4f matrix = new Matrix4f().identity();

        // Apply the 180° Y rotation for the facing direction difference
        matrix.rotateY((float) Math.PI);

        // Apply any additional transformations needed
        // This may include scale or position adjustments

        return matrix;
    }

    /**
     * Applies coordinate system conversion to a 4x4 transformation matrix
     *
     * @param matrix The matrix in Blockbench coordinate system
     * @return The matrix in Minecraft coordinate system
     */
    public static Matrix4f convertMatrix(Matrix4f matrix) {
        // Create conversion matrix
        Matrix4f conversionMatrix = createBlockbenchToMinecraftMatrix();

        // Create result matrix
        Matrix4f result = new Matrix4f();

        // Apply conversion (careful with matrix multiplication order)
        // The order depends on whether this is a local or global transformation
        result.set(conversionMatrix).mul(matrix);

        return result;
    }

    /**
     * Creates a Bukkit EulerAngle for use with entities
     *
     * @param rotation The rotation in Minecraft coordinates (radians)
     * @return A Bukkit EulerAngle for entity rotation
     */
    public static EulerAngle createEulerAngle(Vector3f rotation) {
        return new EulerAngle(rotation.x, rotation.y, rotation.z);
    }

    /**
     * Converts from a bone transformation to an armor stand rotation
     *
     * @param boneTranslation The bone's translation
     * @param boneRotation    The bone's rotation
     * @param boneScale       The bone's scale
     * @param pivot           The bone's pivot point
     * @return An EulerAngle suitable for an armor stand
     */
    public static EulerAngle getBoneRotationForArmorStand(
            Vector3f boneTranslation,
            Vector3f boneRotation,
            Vector3f boneScale,
            Vector3f pivot) {

        // Convert vectors to Minecraft coordinate system
        Vector3f convertedTranslation = convertPosition(boneTranslation);
        Vector3f convertedRotation = convertRotation(boneRotation, EntityType.ARMOR_STAND);
        Vector3f convertedScale = convertScale(boneScale);
        Vector3f convertedPivot = convertPosition(pivot);

        // Create the final EulerAngle
        return createEulerAngle(convertedRotation);
    }

    /**
     * Converts from a bone transformation to a display entity rotation
     *
     * @param boneTranslation The bone's translation
     * @param boneRotation    The bone's rotation
     * @param boneScale       The bone's scale
     * @param pivot           The bone's pivot point
     * @return An EulerAngle suitable for a display entity
     */
    public static EulerAngle getBoneRotationForDisplayEntity(
            Vector3f boneTranslation,
            Vector3f boneRotation,
            Vector3f boneScale,
            Vector3f pivot) {

        // Convert vectors to Minecraft coordinate system
        Vector3f convertedTranslation = convertPosition(boneTranslation);
        Vector3f convertedRotation = convertRotation(boneRotation, EntityType.DISPLAY_ENTITY);
        Vector3f convertedScale = convertScale(boneScale);
        Vector3f convertedPivot = convertPosition(pivot);

        // Create the final EulerAngle
        return createEulerAngle(convertedRotation);
    }

    /**
     * Converts a Blockbench bone location to a Minecraft entity location
     *
     * @param boneLocation The bone's location in Blockbench coordinates
     * @param world        The Minecraft world
     * @return A Minecraft Location
     */
    public static Location convertToMinecraftLocation(Vector3f boneLocation, org.bukkit.World world) {
        // Convert to Minecraft coordinates
        Vector3f converted = convertPosition(boneLocation);

        // Create Minecraft Location
        return new Location(world, converted.x, converted.y, converted.z);
    }

    /**
     * Converts Euler angles to a quaternion with the correct rotation order
     */
    private static Quaternionf eulerToQuaternion(Vector3f euler) {
        return new Quaternionf().rotationXYZ(euler.x, euler.y, euler.z);
    }

    /**
     * Converts a quaternion back to Euler angles
     */
    private static Vector3f quaternionToEuler(Quaternionf quat) {
        Vector3f euler = new Vector3f();
        quat.getEulerAnglesXYZ(euler);
        return euler;
    }

    /**
     * Helper method to log conversion details when debug mode is enabled
     */
    private static void logConversion(String type, Vector3f original, Vector3f converted) {
        if (!DEBUG_MODE) return;

        System.out.println(type + " Conversion:");
        System.out.println("  Blockbench: " + original);
        if (type.equals("Rotation")) {
            System.out.println("  Blockbench (deg): " +
                    toDegrees(original.x) + ", " +
                    toDegrees(original.y) + ", " +
                    toDegrees(original.z));
            System.out.println("  Minecraft (deg): " +
                    toDegrees(converted.x) + ", " +
                    toDegrees(converted.y) + ", " +
                    toDegrees(converted.z));
        }
        System.out.println("  Minecraft: " + converted);
    }

    /**
     * Convert degrees to radians
     */
    public static float toRadians(float degrees) {
        return (float) Math.toRadians(degrees);
    }

    /**
     * Convert radians to degrees
     */
    public static float toDegrees(float radians) {
        return (float) Math.toDegrees(radians);
    }

    /**
     * Test the conversion with a sample bone
     * Call this from a command to validate conversions
     */
    public static void runConversionTest() {
        // Create test vectors
        Vector3f position = new Vector3f(1, 2, 3);
        Vector3f rotation = new Vector3f(
                toRadians(30),  // 30° X rotation
                toRadians(45),  // 45° Y rotation
                toRadians(60)   // 60° Z rotation
        );
        Vector3f scale = new Vector3f(1, 1, 1);
        Vector3f pivot = new Vector3f(0, 0, 0);

        // Force debug for this test
        boolean originalDebug = DEBUG_MODE;
        DEBUG_MODE = true;

        // Run conversions
        Vector3f convertedPosition = convertPosition(position);

        // Test default pose rotations
        Vector3f convertedDefaultRotationDisplay = convertRotation(
                rotation,
                EntityType.DISPLAY_ENTITY,
                RotationType.DEFAULT_POSE
        );

        Vector3f convertedDefaultRotationArmorStand = convertRotation(
                rotation,
                EntityType.ARMOR_STAND,
                RotationType.DEFAULT_POSE
        );

        // Test animation rotations
        Vector3f convertedAnimRotationDisplay = convertRotation(
                rotation,
                EntityType.DISPLAY_ENTITY,
                RotationType.ANIMATION
        );

        Vector3f convertedAnimRotationArmorStand = convertRotation(
                rotation,
                EntityType.ARMOR_STAND,
                RotationType.ANIMATION
        );

        Vector3f convertedScale = convertScale(scale);

        // Print summary
        System.out.println("=== CONVERSION TEST SUMMARY ===");
        System.out.println("Position: " + convertedPosition);

        System.out.println("\n--- DEFAULT POSE ROTATIONS ---");
        System.out.println("Display Entity Default Rotation (deg): " +
                toDegrees(convertedDefaultRotationDisplay.x) + ", " +
                toDegrees(convertedDefaultRotationDisplay.y) + ", " +
                toDegrees(convertedDefaultRotationDisplay.z));
        System.out.println("Armor Stand Default Rotation (deg): " +
                toDegrees(convertedDefaultRotationArmorStand.x) + ", " +
                toDegrees(convertedDefaultRotationArmorStand.y) + ", " +
                toDegrees(convertedDefaultRotationArmorStand.z));

        System.out.println("\n--- ANIMATION ROTATIONS ---");
        System.out.println("Display Entity Animation Rotation (deg): " +
                toDegrees(convertedAnimRotationDisplay.x) + ", " +
                toDegrees(convertedAnimRotationDisplay.y) + ", " +
                toDegrees(convertedAnimRotationDisplay.z));
        System.out.println("Armor Stand Animation Rotation (deg): " +
                toDegrees(convertedAnimRotationArmorStand.x) + ", " +
                toDegrees(convertedAnimRotationArmorStand.y) + ", " +
                toDegrees(convertedAnimRotationArmorStand.z));

        // Restore debug setting
        DEBUG_MODE = originalDebug;
    }

    /**
     * Entity types that might need different transformation handling
     */
    public enum EntityType {
        DISPLAY_ENTITY,
        ARMOR_STAND
    }

    /**
     * Rotation types for different contexts in Blockbench
     * Blockbench uses different coordinate systems for default pose vs animations
     */
    public enum RotationType {
        DEFAULT_POSE,  // For initial/default model rotations
        ANIMATION      // For animation keyframes
    }
}