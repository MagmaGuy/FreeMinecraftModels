package com.magmaguy.freeminecraftmodels.utils;

import org.joml.Vector3f;

import java.util.Arrays;

/** Mutable row-major matrix using column vectors and post-multiplied local transforms. */
public class TransformationMatrix {
    private final double[] matrix = new double[16];
    private final double[] product = new double[16];
    private final double[] basis = new double[9];
    private final double[] scale = new double[3];
    private boolean componentsDirty = true;

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
        resultMatrix.componentsDirty = true;
    }

    public void resetToIdentityMatrix() {
        Arrays.fill(matrix, 0);
        matrix[0] = matrix[5] = matrix[10] = matrix[15] = 1;
        componentsDirty = true;
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
        componentsDirty = true;
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

    /** @return [scaleX, scaleY, scaleZ], assigning any reflection to the X axis. */
    public double[] getScale() {
        double[] result = new double[3];
        getScale(result);
        return result;
    }

    /** Writes [scaleX, scaleY, scaleZ] into caller-owned storage; does not retain it. */
    public void getScale(double[] result) {
        if (result.length < 3) throw new IllegalArgumentException("Scale output requires at least three elements");
        updateComponents();
        System.arraycopy(scale, 0, result, 0, 3);
    }

    public void rotateX(double angleRadians) {
        componentsDirty = true;
        double cosine = Math.cos(angleRadians), sine = Math.sin(angleRadians);
        for (int row = 0; row < 16; row += 4) {
            double y = matrix[row + 1], z = matrix[row + 2];
            matrix[row + 1] = y * cosine + z * sine;
            matrix[row + 2] = -y * sine + z * cosine;
        }
    }

    public void rotateY(double angleRadians) {
        componentsDirty = true;
        double cosine = Math.cos(angleRadians), sine = Math.sin(angleRadians);
        for (int row = 0; row < 16; row += 4) {
            double x = matrix[row], z = matrix[row + 2];
            matrix[row] = x * cosine - z * sine;
            matrix[row + 2] = x * sine + z * cosine;
        }
    }

    public void rotateZ(double angleRadians) {
        componentsDirty = true;
        double cosine = Math.cos(angleRadians), sine = Math.sin(angleRadians);
        for (int row = 0; row < 16; row += 4) {
            double x = matrix[row], y = matrix[row + 1];
            matrix[row] = x * cosine + y * sine;
            matrix[row + 1] = -x * sine + y * cosine;
        }
    }

    /** @return the translation: [x, y, z] */
    public double[] getTranslation() {
        double[] result = new double[3];
        getTranslation(result);
        return result;
    }

    /** Writes [x, y, z] into caller-owned storage; does not retain it. */
    public void getTranslation(double[] result) {
        if (result.length < 3) throw new IllegalArgumentException("Translation output requires at least three elements");
        result[0] = matrix[3];
        result[1] = matrix[7];
        result[2] = matrix[11];
    }

    /** @return the rotation in radians: [x, y, z] */
    public double[] getRotation() {
        double[] rotation = new double[3];
        getRotation(rotation);
        return rotation;
    }

    /**
     * Writes rotation for the existing Rz * Ry * Rx plus diagonal-scale packet contract.
     * Orthogonal R*S inputs, including reflections, reconstruct exactly. Collapsed axes
     * use a deterministic completion of the remaining basis. Shear cannot be represented
     * by this packet contract; normalized-column Euler extraction remains an approximation.
     */
    public void getRotation(double[] rotation) {
        if (rotation.length < 3) throw new IllegalArgumentException("Rotation output requires at least three elements");
        updateComponents();
        double horizontal = Math.hypot(basis[0], basis[1]);
        rotation[1] = Math.atan2(-basis[2], horizontal);
        if (horizontal <= 1e-6 * Math.hypot(horizontal, basis[2])) {
            // At gimbal lock, roll is indeterminate; choose zero.
            rotation[0] = Math.atan2(-basis[7], basis[4]);
            rotation[2] = 0;
        } else {
            rotation[0] = Math.atan2(basis[5], basis[8]);
            rotation[2] = Math.atan2(basis[1], basis[0]);
        }
    }

    public void resetRotation() {
        // Preserve translation and the bottom row, matching the existing reset contract.
        matrix[0] = matrix[5] = matrix[10] = 1;
        matrix[1] = matrix[2] = matrix[4] = matrix[6] = matrix[8] = matrix[9] = 0;
        componentsDirty = true;
    }

    private void updateComponents() {
        if (!componentsDirty) return;
        for (int column = 0; column < 3; column++) {
            int base = column * 3;
            basis[base] = matrix[column];
            basis[base + 1] = matrix[4 + column];
            basis[base + 2] = matrix[8 + column];
            scale[column] = normalizeBasis(base);
        }
        double determinant = basis[0] * (basis[4] * basis[8] - basis[5] * basis[7])
                - basis[3] * (basis[1] * basis[8] - basis[2] * basis[7])
                + basis[6] * (basis[1] * basis[5] - basis[2] * basis[4]);
        if (determinant < 0) {
            scale[0] = -scale[0];
            basis[0] = -basis[0]; basis[1] = -basis[1]; basis[2] = -basis[2];
        }
        if (scale[0] == 0 && scale[1] == 0 && scale[2] == 0) {
            basis[0] = basis[4] = basis[8] = 1;
        } else if (scale[0] == 0 && scale[1] == 0) {
            perpendicular(0, 6);
            cross(3, 6, 0);
        } else if (scale[0] == 0 && scale[2] == 0) {
            perpendicular(0, 3);
            cross(6, 0, 3);
        } else if (scale[1] == 0 && scale[2] == 0) {
            perpendicular(3, 0);
            cross(6, 0, 3);
        } else if (scale[0] == 0) cross(0, 3, 6);
        else if (scale[1] == 0) cross(3, 6, 0);
        else if (scale[2] == 0) cross(6, 0, 3);
        componentsDirty = false;
    }

    private double normalizeBasis(int base) {
        double length = Math.hypot(Math.hypot(basis[base], basis[base + 1]), basis[base + 2]);
        if (length != 0) {
            basis[base] /= length;
            basis[base + 1] /= length;
            basis[base + 2] /= length;
        }
        return length;
    }

    private void cross(int destination, int first, int second) {
        double x = basis[first + 1] * basis[second + 2] - basis[first + 2] * basis[second + 1];
        double y = basis[first + 2] * basis[second] - basis[first] * basis[second + 2];
        double z = basis[first] * basis[second + 1] - basis[first + 1] * basis[second];
        basis[destination] = x;
        basis[destination + 1] = y;
        basis[destination + 2] = z;
        if (normalizeBasis(destination) == 0) perpendicular(destination, first);
    }

    private void perpendicular(int destination, int source) {
        // Cross with a canonical axis that is not parallel to the surviving vector.
        double x = basis[source], y = basis[source + 1], z = basis[source + 2];
        if (Math.abs(x) < Math.abs(z)) {
            basis[destination] = 0; basis[destination + 1] = z; basis[destination + 2] = -y;
        } else {
            basis[destination] = -y; basis[destination + 1] = x; basis[destination + 2] = 0;
        }
        normalizeBasis(destination);
    }
}
