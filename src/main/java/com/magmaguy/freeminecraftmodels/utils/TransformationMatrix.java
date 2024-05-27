package com.magmaguy.freeminecraftmodels.utils;

import com.magmaguy.freeminecraftmodels.dataconverter.BoneBlueprint;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class TransformationMatrix {
    private Matrix4f matrix = new Matrix4f();

    public TransformationMatrix() {
        resetToIdentity();
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix,
                                        TransformationMatrix secondMatrix,
                                        TransformationMatrix resultMatrix,
                                        Vector3f pivot,
                                        BoneBlueprint boneBlueprint) {
        resultMatrix.matrix = new Matrix4f(firstMatrix.matrix)
                .translate(pivot.negate())
                .mul(secondMatrix.matrix)
                .translate(pivot.negate());

//        resultMatrix.matrix.mul(firstMatrix.matrix);
    }

    public void resetToIdentity() {
        matrix.identity();
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

    public void rotateDefaultPosition(float x, float y, float z, Vector3f pivotPoint) {
        Quaternionf defaultRotation = new Quaternionf();
        matrix.translate(pivotPoint.negate()); // Move to pivot

//        defaultRotation.rotateXYZ(x, y, z);
//        defaultRotation.rotateX(x).rotateY(y).rotateZ(z).normalize(); //NO
//        defaultRotation.rotateX(x).rotateZ(z).rotateY(y); //NO
//        defaultRotation.rotateZ(z).rotateX(x).rotateY(y); //NO

        //most probably the right rotation orders
        defaultRotation.rotateLocalX(x).rotateLocalY(y).rotateLocalZ(z).normalize();
//        defaultRotation.rotateY(y).rotateX(x).rotateZ(z).normalize();
//        defaultRotation.rotateZ(z).rotateY(y).rotateX(x).normalize(); //UNLIKELY
        matrix.rotate(defaultRotation);
        matrix.translate(pivotPoint.negate()); // Correctly move back from pivot
    }

    public void rotateY(float y) {
        matrix.rotateY(y);
    }

    public void animationRotation(float x, float y, float z, Vector3f pivotPoint) {
        // Translate matrix to pivot, apply rotation, and translate back
        matrix.translate(pivotPoint.negate()); // Move to pivot
        Quaternionf localRotation = new Quaternionf().rotateXYZ(x, y, z).normalize();
        matrix.rotate(localRotation);
        matrix.translate(pivotPoint.negate()); // Correctly move back from pivot
    }

    public float[] getTranslation() {
        Vector3f translation = new Vector3f();
        matrix.getTranslation(translation);
        return new float[]{translation.x, translation.y, translation.z};
    }

    public Vector3f getTranslationVector() {
        Vector3f translation = new Vector3f();
        return matrix.getTranslation(translation);
    }

    public float[] getRotation() {
        Vector3f eulerAngles = matrix.getEulerAnglesXYZ(new Vector3f());
        return new float[]{eulerAngles.x, eulerAngles.y, eulerAngles.z};
    }

    public Vector3f getExperimentalRotation() {
        return new Matrix4f(matrix).getNormalizedRotation(new Quaternionf()).rotateLocalY((float) Math.PI).getEulerAnglesXYZ(new Vector3f());
    }

}