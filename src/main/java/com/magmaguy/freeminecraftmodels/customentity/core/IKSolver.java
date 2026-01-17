package com.magmaguy.freeminecraftmodels.customentity.core;

import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Implements the FABRIK (Forward And Backward Reaching Inverse Kinematics) algorithm.
 * FABRIK is a fast, iterative solver that works by:
 * 1. Reaching backward from target to base
 * 2. Reaching forward from base to target
 * 3. Repeating until convergence or max iterations
 *
 * This implementation follows Blockbench's approach using the FIK library concepts.
 */
public class IKSolver {
    private static final int MAX_ITERATIONS = 10;
    private static final float TOLERANCE = 0.001f;

    /**
     * Solves IK for a bone chain using the FABRIK algorithm.
     *
     * @param positions   Array of joint positions (size = bones + 1, includes end effector)
     * @param target      Target position for the end effector
     * @param boneLengths Array of bone lengths
     * @return true if the solver converged, false if max iterations reached
     */
    public static boolean solveFABRIK(Vector3f[] positions, Vector3f target, float[] boneLengths) {
        int n = positions.length - 1; // Number of bones
        if (n <= 0) return true;

        // Store the base position (root is fixed)
        Vector3f base = new Vector3f(positions[0]);

        // Check if target is reachable
        float totalLength = 0;
        for (float length : boneLengths) {
            totalLength += length;
        }

        float distanceToTarget = base.distance(target);

        // If target is unreachable, stretch toward it
        if (distanceToTarget > totalLength) {
            // Stretch the chain toward the target
            Vector3f direction = new Vector3f(target).sub(base).normalize();
            positions[0].set(base);
            for (int i = 0; i < n; i++) {
                positions[i + 1].set(positions[i]).add(new Vector3f(direction).mul(boneLengths[i]));
            }
            return false; // Didn't reach target, but chain is stretched toward it
        }

        // FABRIK iterations
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            // Check if close enough to target
            if (positions[n].distance(target) < TOLERANCE) {
                return true;
            }

            // BACKWARD REACHING (from end effector to base)
            positions[n].set(target);
            for (int i = n - 1; i >= 0; i--) {
                Vector3f direction = new Vector3f(positions[i]).sub(positions[i + 1]);
                float length = direction.length();
                if (length > 0) {
                    direction.normalize();
                }
                positions[i].set(positions[i + 1]).add(direction.mul(boneLengths[i]));
            }

            // FORWARD REACHING (from base to end effector)
            positions[0].set(base);
            for (int i = 0; i < n; i++) {
                Vector3f direction = new Vector3f(positions[i + 1]).sub(positions[i]);
                float length = direction.length();
                if (length > 0) {
                    direction.normalize();
                }
                positions[i + 1].set(positions[i]).add(direction.mul(boneLengths[i]));
            }
        }

        return positions[n].distance(target) < TOLERANCE;
    }

    /**
     * Calculates the rotation needed to point from one position to another.
     * Returns Euler angles (in radians) for the rotation.
     *
     * @param from      Starting position
     * @param to        Target position
     * @param restDir   The rest/default direction of the bone (usually forward, e.g., 0,0,1)
     * @return Vector3f containing rotation in radians (pitch, yaw, roll)
     */
    public static Vector3f calculateBoneRotation(Vector3f from, Vector3f to, Vector3f restDir) {
        Vector3f direction = new Vector3f(to).sub(from);
        float length = direction.length();
        if (length < 0.0001f) {
            return new Vector3f(0, 0, 0);
        }
        direction.normalize();

        // Calculate quaternion rotation from rest direction to target direction
        Quaternionf rotation = new Quaternionf();
        rotation.rotationTo(restDir, direction);

        // Convert quaternion to Euler angles
        return quaternionToEuler(rotation);
    }

    /**
     * Converts a quaternion to Euler angles (XYZ order).
     *
     * @param q The quaternion to convert
     * @return Vector3f containing Euler angles in radians
     */
    public static Vector3f quaternionToEuler(Quaternionf q) {
        Vector3f euler = new Vector3f();
        q.getEulerAnglesXYZ(euler);
        return euler;
    }

    /**
     * Calculates the rotation delta between two bone configurations.
     * Uses setFromUnitVectors approach like Blockbench.
     *
     * @param oldDir Previous bone direction (normalized)
     * @param newDir New bone direction (normalized)
     * @return Euler angles representing the rotation delta
     */
    public static Vector3f calculateRotationDelta(Vector3f oldDir, Vector3f newDir) {
        Quaternionf rotation = new Quaternionf();
        rotation.rotationTo(oldDir, newDir);
        return quaternionToEuler(rotation);
    }
}
