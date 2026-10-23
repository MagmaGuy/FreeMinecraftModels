package com.magmaguy.freeminecraftmodels.bedrock;

import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shows a model's body tilt and body offset on its Bedrock carrier. The exported lean bone, parent of every root
 * bone and pivoted at the middle of the model's cubes, takes the lean as its rotation and the offset as its position
 * (see {@link BedrockEntityBundleExporter#LEAN_BONE}). Bedrock moves a bone after turning it about its pivot, so the
 * model turns about its own middle and is then raised or lowered, as on Java.
 * <p>
 * The tilt is a world-space rotation, but a bone turns in the model's own frame, which Bedrock then turns to the
 * carrier's yaw: a model facing yaw φ has its Blockbench axes turned by 180° − φ about up, its front (−z) toward its
 * heading. So the tilt is first taken into that frame, then split into the Z·Y·X Euler angles a Bedrock bone rotation
 * applies, with the x and y signs Bedrock uses (the export writes rotations as (−x, −y, z) for the same reason). The
 * angles go out in hundredths of a degree and the offset in hundredths of a model pixel, which the carrier's scale
 * then scales as it does the rest of the model. Both are integer properties: the RSPM Geyser bridge registers every
 * property a pack's controllers query as an integer.
 * <p>
 * One instance belongs to one carrier and is only stepped by its model tick. What the carrier already shows sends
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
    private int sentOffset;

    /** Whether the carrier was last sent a lean or an offset, so one that ends still has to be undone. */
    boolean isPlaced() {
        return leaning || sentOffset != 0;
    }

    /**
     * The properties that bring the carrier to this tilt and offset, or null when it already shows them.
     *
     * @param tilt   the model's world-space body tilt, or null while it stands upright
     * @param yaw    the yaw the carrier faces, in degrees (Minecraft yaw: 0 is south, 90 west); unread while upright
     * @param offset blocks the model is drawn above its entity's feet, at its current scale
     * @param scale  the model's current scale, which the carrier draws it at
     */
    Map<String, Object> update(Quaterniondc tilt, float yaw, double offset, double scale) {
        Map<String, Object> changes = new LinkedHashMap<>();
        int pixels = offsetHundredths(offset, scale);
        if (pixels != sentOffset) {
            sentOffset = pixels;
            changes.put(BedrockEntityBundleExporter.BODY_OFFSET_PROPERTY, pixels);
        }
        if (tilt == null) {
            if (leaning) {
                leaning = false;
                sent.identity();
                putLean(changes, 0, 0, 0);
            }
        } else {
            modelFrame(tilt, yaw, local);
            if (!leaning || degreesBetween(sent, local) >= RESEND_DEGREES) {
                leaning = true;
                sent.set(local);
                boneRotation(local, angles);
                putLean(changes, hundredths(angles.x), hundredths(angles.y), hundredths(angles.z));
            }
        }
        return changes.isEmpty() ? null : changes;
    }

    /** The offset in hundredths of a model pixel: blocks at this scale, back at scale 1, times 16. */
    static int offsetHundredths(double offset, double scale) {
        return scale > 0D ? hundredths(offset / scale * 16D) : 0;
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

    private static void putLean(Map<String, Object> changes, int x, int y, int z) {
        List<String> names = BedrockEntityBundleExporter.LEAN_PROPERTIES;
        changes.put(names.get(0), x);
        changes.put(names.get(1), y);
        changes.put(names.get(2), z);
    }

    private static int hundredths(double value) {
        return (int) Math.round(value * 100D);
    }

    private static double degreesBetween(Quaterniondc a, Quaterniondc b) {
        double dot = Math.abs(a.x() * b.x() + a.y() * b.y() + a.z() * b.z() + a.w() * b.w());
        return Math.toDegrees(2D * Math.acos(Math.min(1D, dot)));
    }
}
