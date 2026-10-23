package com.magmaguy.freeminecraftmodels.bedrock;

import org.joml.Matrix3d;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Bedrock lean turns the model in the world exactly as the Java body tilt does, whatever way it faces, and the
 * body offset goes out in the model's own pixels. Bedrock's side is modelled as Blockbench shows a Bedrock model: a
 * bone rotation (x, y, z) is Rz(z) · Ry(−y) · Rx(−x) on Blockbench axes, and a model facing yaw φ has those axes
 * turned by 180° − φ about up.
 */
class BedrockBodyLeanTest {
    private static final List<String> LEAN = BedrockEntityBundleExporter.LEAN_PROPERTIES;
    private static final float[] YAWS = {0F, 37.5F, 90F, -170F, 270F};

    @Test
    void anUprightModelSendsNothing() {
        assertNull(new BedrockBodyLean().update(null, 30F, 0D, 1D));
    }

    @Test
    void cruisingTipsTheTopForward() {
        // Top first along its heading. A positive x pitches a Bedrock bone's front down, as look_at_target does with
        // a head; turning the top forward is the same turn.
        for (float yaw : YAWS) {
            Vector3d heading = heading(yaw);
            Quaterniond tilt = new Quaterniond().rotationAxis(Math.toRadians(90), heading.z, 0, -heading.x);
            assertLean(new BedrockBodyLean().update(tilt, yaw, 0D, 1D), 9000, 0, 0, "yaw " + yaw);
        }
    }

    @Test
    void rollingOntoItsRightSideIsANegativeZ() {
        // A positive turn about its front rolls its top toward its right, as a front-first bank into a right turn.
        for (float yaw : YAWS) {
            Vector3d heading = heading(yaw);
            Quaterniond tilt = new Quaterniond().rotationAxis(Math.toRadians(30), heading.x, 0, heading.z);
            assertLean(new BedrockBodyLean().update(tilt, yaw, 0D, 1D), 0, 0, -3000, "yaw " + yaw);
        }
    }

    @Test
    void theLeanMatchesTheJavaTiltForAnyTurnAndFacing() {
        List<Quaterniond> tilts = new ArrayList<>(List.of(
                // Upside down over a diagonal: the middle Euler turn is a quarter turn.
                new Quaterniond().rotationAxis(Math.PI, Math.sqrt(0.5), 0, Math.sqrt(0.5)),
                new Quaterniond().rotationAxis(Math.PI, 0, 0, 1),
                new Quaterniond().rotationAxis(Math.toRadians(90), 0, 1, 0),
                new Quaterniond().rotationAxis(Math.toRadians(135), 0.3, -0.8, 0.52).normalize()));
        Random random = new Random(7);
        for (int i = 0; i < 200; i++) {
            Vector3d axis = new Vector3d(random.nextGaussian(), random.nextGaussian(), random.nextGaussian()).normalize();
            tilts.add(new Quaterniond().rotationAxis(random.nextDouble() * Math.PI, axis.x, axis.y, axis.z));
        }
        for (Quaterniond tilt : tilts) {
            for (float yaw : YAWS) {
                Map<String, Object> lean = new BedrockBodyLean().update(tilt, yaw, 0D, 1D);
                assertNotNull(lean);
                Matrix3d turn = bedrockWorldTurn(degrees(lean, 0), degrees(lean, 1), degrees(lean, 2), yaw);
                for (Vector3d axis : List.of(new Vector3d(1, 0, 0), new Vector3d(0, 1, 0), new Vector3d(0, 0, 1))) {
                    Vector3d java = tilt.transform(new Vector3d(axis));
                    Vector3d bedrock = turn.transform(new Vector3d(axis));
                    assertEquals(0D, java.distance(bedrock), 1.0E-3, "tilt " + tilt + " at yaw " + yaw + ": " + lean);
                }
            }
        }
    }

    @Test
    void anUnchangedLeanSendsNothing() {
        BedrockBodyLean lean = new BedrockBodyLean();
        Quaterniond tilt = new Quaterniond().rotationX(Math.toRadians(40));
        assertNotNull(lean.update(tilt, 10F, 0D, 1D));
        assertNull(lean.update(tilt, 10F, 0D, 1D));
        assertNull(lean.update(new Quaterniond(tilt).rotateX(Math.toRadians(0.2)), 10F, 0D, 1D), "under half a degree");
        Quaterniond further = new Quaterniond(tilt).rotateX(Math.toRadians(1));
        assertNotNull(lean.update(further, 10F, 0D, 1D));
        assertNotNull(lean.update(further, 25F, 0D, 1D), "the same world lean is another turn in a model that faces elsewhere");
        assertTrue(lean.isPlaced());
        assertLean(lean.update(null, 25F, 0D, 1D), 0, 0, 0, "standing up sends an upright lean once");
        assertFalse(lean.isPlaced());
        assertNull(lean.update(null, 25F, 0D, 1D));
    }

    @Test
    void anOffsetGoesOutInHundredthsOfAModelPixel() {
        assertEquals(-800, BedrockBodyLean.offsetHundredths(-0.5D, 1D));
        assertEquals(-400, BedrockBodyLean.offsetHundredths(-0.5D, 2D), "the carrier's scale doubles it again");
        assertEquals(0, BedrockBodyLean.offsetHundredths(-0.5D, 0D));
    }

    @Test
    void anOffsetIsSentWhenItChangesAndClearedWhenItEnds() {
        BedrockBodyLean lean = new BedrockBodyLean();
        assertEquals(Map.of(BedrockEntityBundleExporter.BODY_OFFSET_PROPERTY, -1456),
                lean.update(null, Float.NaN, -0.91D, 1D), "a jellyfish hung on a 0.6-block body");
        assertTrue(lean.isPlaced());
        assertNull(lean.update(null, Float.NaN, -0.91D, 1D));
        assertNull(lean.update(null, Float.NaN, -1.82D, 2D), "the same offset at twice the scale");
        Map<String, Object> leaning = lean.update(new Quaterniond().rotationX(Math.toRadians(40)), 0F, -1.82D, 2D);
        assertEquals(LEAN, List.copyOf(leaning.keySet()), "a lean alone leaves the offset as it was");
        lean.update(null, Float.NaN, -1.82D, 2D);
        assertEquals(Map.of(BedrockEntityBundleExporter.BODY_OFFSET_PROPERTY, 0), lean.update(null, Float.NaN, 0D, 2D));
        assertFalse(lean.isPlaced());
        assertNull(lean.update(null, Float.NaN, 0D, 2D));
    }

    /** The world turn Bedrock gives a model that faces this yaw for this lean bone rotation. */
    private static Matrix3d bedrockWorldTurn(double x, double y, double z, float yaw) {
        Matrix3d bone = new Matrix3d().rotationZ(Math.toRadians(z)).rotateY(Math.toRadians(-y)).rotateX(Math.toRadians(-x));
        Matrix3d facing = new Matrix3d().rotationY(Math.toRadians(180D - yaw));
        return new Matrix3d(facing).mul(bone).mul(new Matrix3d(facing).transpose());
    }

    private static Vector3d heading(float yaw) {
        return new Vector3d(-Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
    }

    private static double degrees(Map<String, Object> lean, int axis) {
        return ((Integer) lean.get(LEAN.get(axis))) / 100D;
    }

    private static void assertLean(Map<String, Object> lean, int x, int y, int z, String message) {
        assertNotNull(lean, message);
        assertEquals(LEAN, List.copyOf(lean.keySet()), message);
        assertEquals(x, (Integer) lean.get(LEAN.get(0)), 1, message);
        assertEquals(y, (Integer) lean.get(LEAN.get(1)), 1, message);
        assertEquals(z, (Integer) lean.get(LEAN.get(2)), 1, message);
    }
}
