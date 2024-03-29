package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.util.Vector;

public class TransformationMatrix {
    private float[][] matrix = new float[4][4];

    public TransformationMatrix() {
        // Initialize with identity matrix
        resetToIdentityMatrix();
    }

    public static void multiplyMatrices(TransformationMatrix firstMatrix, TransformationMatrix secondMatrix, TransformationMatrix resultMatrix) {
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
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                matrix[i][j] = (i == j) ? 1 : 0;
            }
        }
    }

    public void translate(Vector vector) {
        translate((float) vector.getX(), (float) vector.getY(), (float) vector.getZ());
    }

    public void translate(float x, float y, float z) {
        TransformationMatrix translationMatrix = new TransformationMatrix();
        translationMatrix.matrix[0][3] = x;
        translationMatrix.matrix[1][3] = y;
        translationMatrix.matrix[2][3] = z;
        multiplyWith(translationMatrix);
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
    public void rotate(float x, float y, float z) {
        rotateZ(z);
        rotateY(y);
        rotateX(x);
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
        float[] result = new float[4];
        for (int i = 0; i < 4; i++) {
            for (int j = 0; j < 4; j++) {
                result[i] += matrix[i][j] * point[j];
            }
        }
        return result;
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

}
