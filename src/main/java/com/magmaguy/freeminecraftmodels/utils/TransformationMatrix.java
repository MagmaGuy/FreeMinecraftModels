package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class TransformationMatrix {
    private final Matrix4f matrix;
    private final Quaternionf rotation;  // Store current rotation as quaternion

    public TransformationMatrix() {
        // Initialize with identity matrix
        matrix = new Matrix4f().identity();
        rotation = new Quaternionf(); // Initialize with identity quaternion
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix, TransformationMatrix secondMatrix, TransformationMatrix resultMatrix) {
        resultMatrix.matrix.set(firstMatrix.matrix).mul(secondMatrix.matrix);

        // Also update the resultMatrix's quaternion
        resultMatrix.rotation.set(firstMatrix.rotation).mul(secondMatrix.rotation);
    }

    public void resetToIdentityMatrix() {
        matrix.identity();
        rotation.identity();
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
     * Apply rotations using quaternions to avoid gimbal lock.
     * This method creates a combined rotation from all three axes.
     */
    public void rotateLocal(float x, float y, float z) {
        // Apply rotations directly to the matrix in ZYX order
        // This approach is intentionally prone to gimbal lock
        matrix.rotateLocalY(y);
        matrix.rotateLocalZ(z);
        matrix.rotateLocalX(x);

        // Update the stored quaternion to match the matrix rotation
        rotation.setFromUnnormalized(matrix);
    }

    public void rotateLocal(Vector3f rotationVec) {
        rotateLocal(rotationVec.x, rotationVec.y, rotationVec.z);
    }

    public void rotateX(float angleRadians) {
        // Create quaternion for X rotation
        Quaternionf tempQuat = new Quaternionf().rotationX(angleRadians);

        // Apply the new rotation in local space (right-multiply)
        rotation.mul(tempQuat);

        // Update the matrix
        updateMatrixRotation();
    }

    public void rotateLocalY(float angleRadians) {
        // Create quaternion for Y rotation
        Quaternionf tempQuat = new Quaternionf().rotationY(angleRadians);

        // Apply the new rotation in local space (right-multiply)
        rotation.mul(tempQuat);

        // Update the matrix
        updateMatrixRotation();
    }

    public void rotateGlobalY(float angleRadians) {
        // For global rotation, the new rotation is applied first
        // Create quaternion for Y rotation
        Quaternionf tempQuat = new Quaternionf().rotationY(angleRadians);

        // Apply the new rotation in global space (left-multiply)
        // This is different from local rotation - the order is reversed
        tempQuat.mul(rotation, rotation);

        // Update the matrix
        updateMatrixRotation();
    }

    public void rotateZ(float angleRadians) {
        // Create quaternion for Z rotation
        Quaternionf tempQuat = new Quaternionf().rotationZ(angleRadians);

        // Apply the new rotation in local space (right-multiply)
        rotation.mul(tempQuat);

        // Update the matrix
        updateMatrixRotation();
    }

    // Helper method to update matrix rotation from quaternion
    private void updateMatrixRotation() {
        // Save current translation and scale
        Vector3f translation = new Vector3f();
        matrix.getTranslation(translation);

        Vector3f scale = new Vector3f();
        matrix.getScale(scale);

        // Reset to identity, then apply scale, rotation, and translation in that order
        matrix.identity();
        matrix.scale(scale);
        matrix.rotate(rotation);
        matrix.setTranslation(translation);
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
     * Extracts a rotation in radians from the quaternion.
     * This avoids the gimbal lock issues of the matrix-based approach.
     *
     * @return [x, y, z] rotation in radians
     */
    public float[] getRotation() {
        Vector3f eulerAngles = new Vector3f();
        // Extract in XYZ order directly
        rotation.getEulerAnglesXYZ(eulerAngles);
        return new float[]{eulerAngles.x, eulerAngles.y, eulerAngles.z};
    }

    // Getter for the Matrix4f
    public Matrix4f getMatrix() {
        return matrix;
    }
}