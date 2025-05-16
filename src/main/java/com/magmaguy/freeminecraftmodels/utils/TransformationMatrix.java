package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;

public class TransformationMatrix {
    Matrix4d replacementMatrix = new Matrix4d();
    private double[][] matrix = new double[4][4];

    public TransformationMatrix() {
        // Initialize with identity matrix
        resetToIdentityMatrix();
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix, TransformationMatrix secondMatrix, TransformationMatrix resultMatrix) {
        resultMatrix.replacementMatrix = new Matrix4d(firstMatrix.replacementMatrix).mul(secondMatrix.replacementMatrix);
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
        replacementMatrix.translateLocal(new Vector3d(x, y, z));
    }

    public void scale(double x, double y, double z) {
        TransformationMatrix scaleMatrix = new TransformationMatrix();
        scaleMatrix.matrix[0][0] = x;
        scaleMatrix.matrix[1][1] = y;
        scaleMatrix.matrix[2][2] = z;
        multiplyWith(scaleMatrix);
    }

    /**
     * Rotates the matrix by x y z coordinates. Must be in radian!
     */
    public void rotateLocal(double x, double y, double z) {
        rotateZ(z);
        rotateY(y);
        rotateX(x);
        replacementMatrix.rotateLocalZ(z);
        replacementMatrix.rotateLocalY(y);
        replacementMatrix.rotateLocalX(x);
    }

    public void rotateAnimation(double x, double y, double z) {
        rotateZ(z);
        rotateY(y);
        rotateX(x);
        replacementMatrix.rotateLocalZ(z);
        replacementMatrix.rotateLocalY(y);
        replacementMatrix.rotateLocalX(x);
    }

    /**
     * Extracts the scale factors from the transformation matrix
     *
     * @return [scaleX, scaleY, scaleZ]
     */
    public double[] getScale() {
        double[] scale = new double[3];

        // Extract scale by calculating the magnitude of the basis vectors
        scale[0] = Math.sqrt(
                matrix[0][0] * matrix[0][0] +
                        matrix[1][0] * matrix[1][0] +
                        matrix[2][0] * matrix[2][0]
        );

        scale[1] = Math.sqrt(
                matrix[0][1] * matrix[0][1] +
                        matrix[1][1] * matrix[1][1] +
                        matrix[2][1] * matrix[2][1]
        );

        scale[2] = Math.sqrt(
                matrix[0][2] * matrix[0][2] +
                        matrix[1][2] * matrix[1][2] +
                        matrix[2][2] * matrix[2][2]
        );

        Vector3d jomlScale = new Vector3d();
        replacementMatrix.getScale(jomlScale);
        return scale;
    }

    public void rotateX(double angleRadians) {
        TransformationMatrix rotationMatrix = new TransformationMatrix();
        rotationMatrix.matrix[1][1] = Math.cos(angleRadians);
        rotationMatrix.matrix[1][2] = -Math.sin(angleRadians);
        rotationMatrix.matrix[2][1] = Math.sin(angleRadians);
        rotationMatrix.matrix[2][2] = Math.cos(angleRadians);
        multiplyWith(rotationMatrix);
    }

    public void rotateY(double angleRadians) {
        TransformationMatrix rotationMatrix = new TransformationMatrix();
        rotationMatrix.matrix[0][0] = Math.cos(angleRadians);
        rotationMatrix.matrix[0][2] = Math.sin(angleRadians);
        rotationMatrix.matrix[2][0] = -Math.sin(angleRadians);
        rotationMatrix.matrix[2][2] = Math.cos(angleRadians);
        multiplyWith(rotationMatrix);
    }

    public void rotateZ(double angleRadians) {
        TransformationMatrix rotationMatrix = new TransformationMatrix();
        rotationMatrix.matrix[0][0] = Math.cos(angleRadians);
        rotationMatrix.matrix[0][1] = -Math.sin(angleRadians);
        rotationMatrix.matrix[1][0] = Math.sin(angleRadians);
        rotationMatrix.matrix[1][1] = Math.cos(angleRadians);
        multiplyWith(rotationMatrix);
    }

    private void multiplyWith(TransformationMatrix other) {
        double[][] result = new double[4][4];
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
    public double[] getTranslation() {
        // Extract translation components directly from the matrix
        return new double[]{matrix[0][3], matrix[1][3], matrix[2][3]};
    }

    /**
     * Extracts a rotation in radians
     *
     * @return [x, y, z]
     */
    public double[] getRotation() {
        // Assuming the rotation matrix is "pure" (no scaling) and follows XYZ order
        double[] rotation = new double[3];

        // Yaw (rotation around Y axis)
        rotation[1] = Math.atan2(-matrix[2][0], Math.sqrt(matrix[0][0] * matrix[0][0] + matrix[1][0] * matrix[1][0]));

        // As a special case, if cos(yaw) is close to 0, use an alternative calculation
        if (Math.abs(matrix[2][0]) < 1e-6 && Math.abs(matrix[2][2]) < 1e-6) {
            // Pitch (rotation around X axis)
            rotation[0] = Math.atan2(matrix[1][2], matrix[1][1]);
            // Roll (rotation around Z axis) is indeterminate: set to 0 or use previous value
            rotation[2] = 0;
        } else {
            // Pitch (rotation around X axis)
            rotation[0] = Math.atan2(matrix[2][1], matrix[2][2]);
            // Roll (rotation around Z axis)
            rotation[2] = Math.atan2(matrix[1][0], matrix[0][0]);
        }

        return rotation; // Returns rotations in radians
    }

    public void resetRotation() {
        // Create a new identity matrix to reset rotation
        double[][] identityRotation = {
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