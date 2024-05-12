package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class TransformationMatrix {
    private final Quaternionf rotation = new Quaternionf();
    private Matrix4f matrix = new Matrix4f();

    public TransformationMatrix() {
        resetToIdentity();
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix, TransformationMatrix secondMatrix, TransformationMatrix resultMatrix) {
        resultMatrix.matrix = new Matrix4f(firstMatrix.matrix);
        resultMatrix.matrix.mul(secondMatrix.matrix);
    }

    public void resetToIdentity() {
        matrix.identity();
        rotation.identity();
    }

    public void translate(Vector3f vector) {
        matrix.translate(vector);
    }

    public void translateLocal(Vector3f vector) {
        matrix.translateLocal(vector);
    }

    public void scale(float x, float y, float z) {
        matrix.scale(x, y, z);
    }

    public void rotate(float x, float y, float z) {
        rotation.rotateXYZ(x, y, z);
        applyRotation();
    }

    public void applyRotation() {
        Matrix4f rotationMatrix = new Matrix4f().rotation(rotation);
        matrix.mul(rotationMatrix);
        rotation.identity(); // Reset the quaternion
    }


    public void rotateY(float y) {
        rotation.rotateY(y);
        applyRotation();
    }

    public void rotateLocal(float x, float y, float z, Vector3f pivotPoint) {
        // Translate matrix to pivot, apply rotation, and translate back
        matrix.translate(pivotPoint.negate()); // Move to pivot
        Quaternionf localRotation = new Quaternionf().rotateXYZ(x, y, z);
        Matrix4f localRotationMatrix = new Matrix4f().rotate(localRotation);
        matrix.mul(localRotationMatrix);
        matrix.translate(pivotPoint.negate()); // Correctly move back from pivot
    }

    public float[] getTranslation() {
        Vector3f translation = new Vector3f();
        matrix.getTranslation(translation);
        return new float[]{translation.x, translation.y, translation.z};
    }

    public float[] getRotation() {
        Vector3f eulerAngles = matrix.getEulerAnglesZYX(new Vector3f());
//        Bukkit.getLogger().info("rotation " + eulerAngles.toString());
        return new float[]{eulerAngles.x, eulerAngles.y, eulerAngles.z};

    }

    public Vector3f getExperimentalRotation() {
        return new Matrix4f(matrix).invert().getEulerAnglesZYX(new Vector3f());
//        return matrix.getEulerAnglesXYZ(new Vector3f());
    }

}