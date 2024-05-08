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
        Vector3f rot = new Vector3f(x, y, z);
        Vector3f euler = new Vector3f();
        matrix.getNormalizedRotation(new Quaternionf()).getEulerAnglesYXZ(euler);
        rot.rotateX(euler.get(0));
        rot.rotateZ(euler.get(2));
        rot.rotateY(euler.get(1));
//        rot.rotate(matrix.getNormalizedRotation(new Quaternionf()));

        // Translate to pivot point
        matrix.translate(pivotPoint.negate());

        // Apply local rotation
        Quaternionf localRotation = new Quaternionf().rotateXYZ(rot.x, rot.y, rot.z);

        Matrix4f localRotationMatrix = new Matrix4f().rotate(localRotation);
        matrix.mul(localRotationMatrix);

        // Translate back from pivot point
        matrix.translate(pivotPoint.negate());
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

}