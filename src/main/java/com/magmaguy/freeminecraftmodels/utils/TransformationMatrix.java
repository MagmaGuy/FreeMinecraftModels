package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class TransformationMatrix {
    private final Matrix4f matrix;

    public TransformationMatrix() {
        // Initialize with identity matrix
        matrix = new Matrix4f().identity();
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix, TransformationMatrix secondMatrix, TransformationMatrix resultMatrix) {
        resultMatrix.matrix.set(firstMatrix.matrix).mul(secondMatrix.matrix);
    }

    public void resetToIdentityMatrix() {
        matrix.identity();
    }

    public void translateLocal(Vector3f vector) {
        translateLocal(vector.x, vector.y, vector.z);
    }

    public void translateLocal(float x, float y, float z) {
        matrix.translateLocal(x, y, z);
    }

    public void translateGlobal(float x, float y, float z) {
        matrix.translate(x, y, z);
    }

    public void scale(float x) {
        matrix.scaleLocal(x, x, x);
    }

    public void scale(float x, float y, float z) {
        matrix.scaleLocal(x, y, z);
    }

    /**
     * Apply rotations in ZYX order using quaternions for better handling of combined rotations.
     * This is the standard "Yaw-Pitch-Roll" order used in many 3D applications.
     */
    public void rotateLocal(float x, float y, float z) {
        matrix.rotateLocalY(y);
        matrix.rotateLocalZ(z);
        matrix.rotateLocalX(x);
    }

    public void rotateLocal(Vector3f rotation) {
        rotateLocal(rotation.x, rotation.y, rotation.z);
    }

    public void rotateX(float angleRadians) {
        matrix.rotateLocalX(angleRadians);
    }

    public void rotateLocalY(float angleRadians) {
        matrix.rotateLocalY(angleRadians);
    }

    public void rotateGlobalY(float angleRadians) {
        matrix.rotateY(angleRadians);
    }

    public void rotateZ(float angleRadians) {
        matrix.rotateLocalZ(angleRadians);
    }

    public float[] applyTransformation(float[] point) {
        Vector4f vector = new Vector4f(
                point[0],
                point[1],
                point[2],
                point.length > 3 ? point[3] : 1.0f
        );
        matrix.transform(vector);
        return new float[]{vector.x, vector.y, vector.z, vector.w};
    }

    /**
     * Extracts a xyz position
     *
     * @return [x, y, z]
     */
    public float[] getTranslation() {
        Vector3f translation = new Vector3f();
        matrix.getTranslation(translation);
        return new float[]{translation.x, translation.y, translation.z};
    }

    /**
     * Extracts a rotation in radians with a more robust method
     * that handles multiple rotation axes correctly.
     *
     * @return [x, y, z] rotation in radians
     */
    public float[] getRotation() {
        float[] rotation = new float[3];

        // Yaw (rotation around Y axis)
        rotation[1] = (float) Math.atan2(-matrix.m20(), Math.sqrt(matrix.m00() * matrix.m00() + matrix.m10() * matrix.m10()));

        // As a special case, if cos(yaw) is close to 0, use an alternative calculation
        if (Math.abs(matrix.m20()) < 1e-6 && Math.abs(matrix.m22()) < 1e-6) {
            // Pitch (rotation around X axis)
            rotation[0] = (float) Math.atan2(matrix.m12(), matrix.m11());
            // Roll (rotation around Z axis) is indeterminate: set to 0 or use previous value
            rotation[2] = 0;
        } else {
            // Pitch (rotation around X axis)
            rotation[0] = (float) Math.atan2(matrix.m21(), matrix.m22());
            // Roll (rotation around Z axis)
            rotation[2] = (float) Math.atan2(matrix.m10(), matrix.m00());
        }

        return rotation; // Returns rotations in radians
    }

    public void resetRotation() {
        // Store the current translation
        Vector3f translation = new Vector3f();
        matrix.getTranslation(translation);

        // Reset to identity and restore translation
        matrix.identity().setTranslation(translation);
    }

    // Getter for the Matrix4f
    public Matrix4f getMatrix() {
        return matrix;
    }
}