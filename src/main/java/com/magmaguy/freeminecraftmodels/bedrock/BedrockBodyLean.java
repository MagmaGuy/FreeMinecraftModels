package com.magmaguy.freeminecraftmodels.bedrock;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows a model's body tilt on its Bedrock carrier. The exported lean bone, parent of every root bone and pivoted
 * where Java turns the tilt, takes the lean as its rotation (see {@link BedrockEntityBundleExporter#LEAN_BONE}).
 * <p>
 * The tilt is a world-space rotation, but a bone turns in the model's own frame, which Bedrock then turns to the
 * carrier's yaw: a model facing yaw φ has its Blockbench axes turned by 180° − φ about up, its front (−z) toward its
 * heading. So the tilt is first taken into that frame, then split into the Z·Y·X Euler angles a Bedrock bone rotation
 * applies, with the x and y signs Bedrock uses (the export writes rotations as (−x, −y, z) for the same reason). The
 * angles go out in hundredths of a degree as integer properties: the RSPM Geyser bridge registers every property a
 * pack's controllers query as an integer.
 * <p>
 * One instance belongs to one carrier and is only stepped by its model tick. A lean the carrier already shows sends
 * nothing.
 */
final class BedrockBodyLean {
    // A lean that turned less than this, in degrees, since the last one sent is not worth an update.
    private static final double RESEND_DEGREES = 0.5D;
    private static final double GIMBAL_LOCK = 1D - 1.0E-9D;

    private final Quaterniond sent = new Quaterniond();
    private final Quaterniond local = new Quaterniond();
    private final Vector3d angles = new Vector3d();
    private boolean leaning;

    /** Whether the carrier was last sent a lean, so a tilt that ends still has to stand it upright. */
    boolean isLeaning() {
        return leaning;
    }

    /**
     * The properties that bring the carrier to this tilt, or null when it already shows it.
     *
     * @param tilt the model's world-space body tilt, or null while it stands upright
     * @param yaw  the yaw the carrier faces, in degrees (Minecraft yaw: 0 is south, 90 west)
     */
    Map<String, Object> update(Quaterniondc tilt, float yaw) {
        if (tilt == null) {
            if (!leaning) return null;
            leaning = false;
            sent.identity();
            return properties(0, 0, 0);
        }
        modelFrame(tilt, yaw, local);
        if (leaning && degreesBetween(sent, local) < RESEND_DEGREES) return null;
        leaning = true;
        sent.set(local);
        boneRotation(local, angles);
        return properties(hundredths(angles.x), hundredths(angles.y), hundredths(angles.z));
    }

    /** The tilt as the model's own frame sees it: Ry(φ − 180°) · tilt · Ry(180° − φ). */
    static Quaterniond modelFrame(Quaterniondc tilt, float yaw, Quaterniond result) {
        double facing = Math.toRadians(180D - yaw);
        return result.rotationY(-facing).mul(tilt).rotateY(facing);
    }

    /**
     * The Bedrock bone rotation, in degrees, that turns the model's frame by this rotation. Bedrock reads a rotation
     * (x, y, z) as Rz(z) · Ry(−y) · Rx(−x) on Blockbench axes. When the middle turn is a quarter turn, the first and
     * last turn about the same axis, so x takes both.
     */
    static Vector3d boneRotation(Quaterniondc rotation, Vector3d result) {
        double x = rotation.x(), y = rotation.y(), z = rotation.z(), w = rotation.w();
        double m00 = 1D - 2D * (y * y + z * z);
        double m10 = 2D * (x * y + w * z);
        double m11 = 1D - 2D * (x * x + z * z);
        double m12 = 2D * (y * z - w * x);
        double m20 = 2D * (x * z - w * y);
        double m21 = 2D * (y * z + w * x);
        double m22 = 1D - 2D * (x * x + y * y);
        double aroundX, aroundY, aroundZ;
        if (Math.abs(m20) < GIMBAL_LOCK) {
            aroundY = Math.asin(-m20);
            aroundX = Math.atan2(m21, m22);
            aroundZ = Math.atan2(m10, m00);
        } else {
            aroundY = m20 < 0D ? Math.PI / 2D : -Math.PI / 2D;
            aroundX = Math.atan2(-m12, m11);
            aroundZ = 0D;
        }
        return result.set(-Math.toDegrees(aroundX), -Math.toDegrees(aroundY), Math.toDegrees(aroundZ));
    }

    private static Map<String, Object> properties(int x, int y, int z) {
        List<String> names = BedrockEntityBundleExporter.LEAN_PROPERTIES;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(names.get(0), x);
        properties.put(names.get(1), y);
        properties.put(names.get(2), z);
        return properties;
    }

    private static int hundredths(double degrees) {
        return (int) Math.round(degrees * 100D);
    }

    private static double degreesBetween(Quaterniondc a, Quaterniondc b) {
        double dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return Math.toDegrees(2D * Math.acos(Math.min(1D, dot)));
    }
}
