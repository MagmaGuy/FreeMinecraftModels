package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Vector3f;

import java.util.Arrays;

/** Mutable row-major matrix using column vectors and post-multiplied local transforms. */
public class TransformationMatrix {
    private final double[] matrix = new double[16];
    private final double[] product = new double[16];

    public TransformationMatrix() {
        resetToIdentityMatrix();
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix, TransformationMatrix secondMatrix, TransformationMatrix resultMatrix) {
        double[] first = firstMatrix.matrix;
        double[] second = secondMatrix.matrix;
        // Retained scratch storage also permits either input to be the destination.
        double[] result = resultMatrix.product;
        for (int row = 0; row < 16; row += 4) {
            for (int col = 0; col < 4; col++) {
                result[row + col] = first[row] * second[col]
                        + first[row + 1] * second[4 + col]
                        + first[row + 2] * second[8 + col]
                        + first[row + 3] * second[12 + col];
            }
        }
        System.arraycopy(result, 0, resultMatrix.matrix, 0, 16);
    }

    public void resetToIdentityMatrix() {
        Arrays.fill(matrix, 0);
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1;
    }

    public void translateLocal(Vector3f vector) {
        translateLocal(vector.x(), vector.y(), vector.z());
    }

    public void translateLocal(float x, float y, float z) {
        for (int row = 0; row < 16; row += 4) {
            matrix[row + 3] = matrix[row] * x + matrix[row + 1] * y
                    + matrix[row + 2] * z + matrix[row + 3];
        }
    }

    public void scale(double x, double y, double z) {
        for (int row = 0; row < 16; row += 4) {
            matrix[row] *= x;
            matrix[row + 1] *= y;
            matrix[row + 2] *= z;
        }
    }

    /** Rotates in Rz * Ry * Rx order. Angles are in radians. */
    public void rotateLocal(double x, double y, double z) {
        rotateZ(z);
        rotateY(y);
        rotateX(x);
    }

    public void rotateAnimation(double x, double y, double z) {
        rotateLocal(x, y, z);
    }

    /** @return the magnitudes of the three basis vectors: [scaleX, scaleY, scaleZ] */
    public double[] getScale() {
        return new double[]{
                Math.sqrt(matrix[0] * matrix[0] + matrix[4] * matrix[4] + matrix[8] * matrix[8]),
                Math.sqrt(matrix[1] * matrix[1] + matrix[5] * matrix[5] + matrix[9] * matrix[9]),
                Math.sqrt(matrix[2] * matrix[2] + matrix[6] * matrix[6] + matrix[10] * matrix[10])
        };
    }

    public void rotateX(double angleRadians) {
        double cosine = Math.cos(angleRadians), sine = Math.sin(angleRadians);
        for (int row = 0; row < 16; row += 4) {
            double y = matrix[row + 1], z = matrix[row + 2];
            matrix[row + 1] = y * cosine + z * sine;
            matrix[row + 2] = -y * sine + z * cosine;
        }
    }

    public void rotateY(double angleRadians) {
        double cosine = Math.cos(angleRadians), sine = Math.sin(angleRadians);
        for (int row = 0; row < 16; row += 4) {
            double x = matrix[row], z = matrix[row + 2];
            matrix[row] = x * cosine - z * sine;
            matrix[row + 2] = x * sine + z * cosine;
        }
    }

    public void rotateZ(double angleRadians) {
        double cosine = Math.cos(angleRadians), sine = Math.sin(angleRadians);
        for (int row = 0; row < 16; row += 4) {
            double x = matrix[row], y = matrix[row + 1];
            matrix[row] = x * cosine + y * sine;
            matrix[row + 1] = -x * sine + y * cosine;
        }
    }

    /** @return the translation: [x, y, z] */
    public double[] getTranslation() {
        return new double[]{matrix[3], matrix[7], matrix[11]};
    }

    /** @return the rotation in radians: [x, y, z] */
    public double[] getRotation() {
        // Invert the Rz * Ry * Rx order used by rotateLocal and rotateAnimation.
        double[] rotation = new double[3];
        double horizontal = Math.hypot(matrix[0], matrix[4]);
        rotation[1] = Math.atan2(-matrix[8], horizontal);
        if (horizontal <= 1e-6 * Math.hypot(horizontal, matrix[8])) {
            // At gimbal lock, roll is indeterminate; choose zero.
            rotation[0] = Math.atan2(-matrix[6], matrix[5]);
            rotation[2] = 0;
        } else {
            rotation[0] = Math.atan2(matrix[9], matrix[10]);
            rotation[2] = Math.atan2(matrix[4], matrix[0]);
        }
        return rotation;
    }

    public void resetRotation() {
        // Preserve translation and the bottom row, matching the existing reset contract.
        matrix[0] = matrix[5] = matrix[10] = 1;
        matrix[1] = matrix[2] = matrix[4] = matrix[6] = matrix[8] = matrix[9] = 0;
    }
}
