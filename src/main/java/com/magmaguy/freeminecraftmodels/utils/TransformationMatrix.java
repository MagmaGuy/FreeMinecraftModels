package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Matrix4f;
import org.joml.Vector3f;

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
     * Apply rotations in XYZ order
     */
    public void rotateLocal(float x, float y, float z) {
        // Apply rotations in XYZ order
        matrix.rotateLocalX(x);


        matrix.rotateLocalY(y);

        matrix.rotateLocalZ(z);


        // Optional debug output for rotation values
        // System.out.println("Applied rotation: X=" + Math.toDegrees(x) +
        //                  ", Y=" + Math.toDegrees(y) +
        //                  ", Z=" + Math.toDegrees(z));
    }

    public void rotateLocal(Vector3f rotationVec) {
        rotateLocal(rotationVec.x, rotationVec.y, rotationVec.z);
    }

    /**
     * Extracts rotation in radians from the matrix directly.
     * Return format is [x, y, z]
     */
    public float[] getRotation() {
        Vector3f eulerAngles = new Vector3f();
        matrix.getEulerAnglesXYZ(eulerAngles);
        return new float[]{eulerAngles.x, eulerAngles.y, eulerAngles.z};
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

    // Simple direct rotation methods
    public void rotateX(float angleRadians) {
        matrix.rotateX(angleRadians);
    }

    public void rotateY(float angleRadians) {
        matrix.rotateY(angleRadians);
    }

    public void rotateLocalY(float angleRadians) {
        matrix.rotateLocalY(angleRadians);
    }

    public void rotateZ(float angleRadians) {
        matrix.rotateZ(angleRadians);
    }

    // Getter for the Matrix4f
    public Matrix4f getMatrix() {
        return matrix;
    }

}