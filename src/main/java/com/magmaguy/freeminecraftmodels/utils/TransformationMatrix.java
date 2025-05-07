package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class TransformationMatrix {
    Matrix4f replacementMatrix = new Matrix4f();
    private float[][] matrix = new float[4][4];

    public TransformationMatrix() {
        // Initialize with identity matrix
        resetToIdentityMatrix();
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix, TransformationMatrix secondMatrix, TransformationMatrix resultMatrix) {
        resultMatrix.replacementMatrix = new Matrix4f(firstMatrix.replacementMatrix).mul(secondMatrix.replacementMatrix);
        // Assume resultMatrix is already initialized to the correct dimensions (4x4)
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                resultMatrix.matrix[row][col] = 0; // Reset result matrix cell
                for (int i = 0; i < 4; i++) {
                    resultMatrix.matrix[row][col] += firstMatrix.matrix[row][i] * secondMatrix.matrix[i][col];
                }
            }
        }
    }

    public void resetToIdentityMatrix() {
        replacementMatrix.identity();
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                matrix[i][j] = (i == j) ? 1 : 0;
            }
        }
    }

    public void translateLocal(Vector3f vector) {
        translateLocal(vector.get(0), vector.get(1), vector.get(2));
    }

    public void translateLocal(float x, float y, float z) {
        TransformationMatrix translationMatrix = new TransformationMatrix();
        translationMatrix.matrix[0][3] = x;
        translationMatrix.matrix[1][3] = y;
        translationMatrix.matrix[2][3] = z;
        multiplyWith(translationMatrix);
        replacementMatrix.translateLocal(new Vector3f(x, y, z));
//        Logger.warn("translation " + x + ' ' + y + ' ' + z);
    }

    public void scale(float x, float y, float z) {
        TransformationMatrix scaleMatrix = new TransformationMatrix();
        scaleMatrix.matrix[0][0] = x;
        scaleMatrix.matrix[1][1] = y;
        scaleMatrix.matrix[2][2] = z;
        multiplyWith(scaleMatrix);
    }

    /**
     * Rotates the matrix by x y z coordinates. Must be in radian!
     */
    public void rotateLocal(float x, float y, float z) {
        rotateZ(z);
        rotateY(y);
        rotateX(x);
        replacementMatrix.rotateLocalZ(z);
        replacementMatrix.rotateLocalY(y);
        replacementMatrix.rotateLocalX(x);
    }

    public void rotateAnimation(float x, float y, float z) {
        rotateZ(z);
        rotateY(y);
        rotateX(x);
        replacementMatrix.rotateLocalZ(z);
        replacementMatrix.rotateLocalY(y);
        replacementMatrix.rotateLocalX(x);
    }

    public void rotateX(float angleRadians) {
        TransformationMatrix rotationMatrix = new TransformationMatrix();
        rotationMatrix.matrix[1][1] = (float) Math.cos(angleRadians);
        rotationMatrix.matrix[1][2] = -(float) Math.sin(angleRadians);
        rotationMatrix.matrix[2][1] = (float) Math.sin(angleRadians);
        rotationMatrix.matrix[2][2] = (float) Math.cos(angleRadians);
        multiplyWith(rotationMatrix);
    }

    public void rotateY(float angleRadians) {
        TransformationMatrix rotationMatrix = new TransformationMatrix();
        rotationMatrix.matrix[0][0] = (float) Math.cos(angleRadians);
        rotationMatrix.matrix[0][2] = (float) Math.sin(angleRadians);
        rotationMatrix.matrix[2][0] = -(float) Math.sin(angleRadians);
        rotationMatrix.matrix[2][2] = (float) Math.cos(angleRadians);
        multiplyWith(rotationMatrix);
    }

    public void rotateZ(float angleRadians) {
        TransformationMatrix rotationMatrix = new TransformationMatrix();
        rotationMatrix.matrix[0][0] = (float) Math.cos(angleRadians);
        rotationMatrix.matrix[0][1] = -(float) Math.sin(angleRadians);
        rotationMatrix.matrix[1][0] = (float) Math.sin(angleRadians);
        rotationMatrix.matrix[1][1] = (float) Math.cos(angleRadians);
        multiplyWith(rotationMatrix);
    }

    public float[] applyTransformation(float[] point) {
        // Use JOML's vector transformation instead of manual matrix multiplication
        org.joml.Vector4f vec = new org.joml.Vector4f(point[0], point[1], point[2], point[3]);
        replacementMatrix.transform(vec);
        return new float[]{vec.x, vec.y, vec.z, vec.w};
    }

    private void multiplyWith(TransformationMatrix other) {
        float[][] result = new float[4][4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                for (int k = 0; k < 4; k++) {
                    result[i][j] += this.matrix[i][k] * other.matrix[k][j];
                }
            }
        }
        this.matrix = result;
    }

    /**
     * Extracts a xyz position
     *
     * @return [x, y, z]
     */
    public float[] getTranslation() {
        // Extract translation components directly from the matrix
        return new float[]{matrix[0][3], matrix[1][3], matrix[2][3]};
    }

    /**
     * Extracts a rotation in radians
     *
     * @return [x, y, z]
     */
    public float[] getRotation() {
        // Assuming the rotation matrix is "pure" (no scaling) and follows XYZ order
        float[] rotation = new float[3];

        // Yaw (rotation around Y axis)
        rotation[1] = (float) Math.atan2(-matrix[2][0], Math.sqrt(matrix[0][0] * matrix[0][0] + matrix[1][0] * matrix[1][0]));

        // As a special case, if cos(yaw) is close to 0, use an alternative calculation
        if (Math.abs(matrix[2][0]) < 1e-6 && Math.abs(matrix[2][2]) < 1e-6) {
            // Pitch (rotation around X axis)
            rotation[0] = (float) Math.atan2(matrix[1][2], matrix[1][1]);
            // Roll (rotation around Z axis) is indeterminate: set to 0 or use previous value
            rotation[2] = 0;
        } else {
            // Pitch (rotation around X axis)
            rotation[0] = (float) Math.atan2(matrix[2][1], matrix[2][2]);
            // Roll (rotation around Z axis)
            rotation[2] = (float) Math.atan2(matrix[1][0], matrix[0][0]);
        }

        return rotation; // Returns rotations in radians
    }

    public void resetRotation() {
        // Create a new identity matrix to reset rotation
        float[][] identityRotation = {
                {1, 0, 0, 0},
                {0, 1, 0, 0},
                {0, 0, 1, 0},
                {0, 0, 0, 1}
        };

        // Keep the translation values intact
        identityRotation[0][3] = matrix[0][3];
        identityRotation[1][3] = matrix[1][3];
        identityRotation[2][3] = matrix[2][3];

        // Replace the current matrix's rotation part with the identity matrix
        for (int i = 0; i < 3; i++) {
            System.arraycopy(identityRotation[i], 0, matrix[i], 0, 3);
        }

        // Reset the rotation part of replacementMatrix using the JOML library
        replacementMatrix.m00(1).m01(0).m02(0);
        replacementMatrix.m10(0).m11(1).m12(0);
        replacementMatrix.m20(0).m21(0).m22(1);

        // The translation part remains unchanged in replacementMatrix
    }


}