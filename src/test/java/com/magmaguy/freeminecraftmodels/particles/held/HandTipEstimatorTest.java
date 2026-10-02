package com.magmaguy.freeminecraftmodels.particles.held;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HandTipEstimatorTest {
    private static final Vector3d FEET = new Vector3d(100, 64, -20);
    private static final Vector3d ITEM_CENTER = new Vector3d(8, 8, 8);

    private static Vector3d tip(float bodyYaw, boolean sneaking, boolean leftArm, HandTipEstimator.ItemTransform transform,
                                Vector3d itemPoint) {
        Vector3d position = new Vector3d();
        HandTipEstimator.estimate(FEET, bodyYaw, sneaking, leftArm, 1, transform, itemPoint, position, new Quaterniond());
        return position.sub(FEET);
    }

    @Test
    void rightHandItemCenterMatchesTheVanillaChain() {
        Vector3d offset = tip(0, false, false, null, ITEM_CENTER);
        assertEquals(-0.351563, offset.x, 1e-5);
        assertEquals(0.768953, offset.y, 1e-5);
        assertEquals(0.292517, offset.z, 1e-5);
    }

    @Test
    void leftHandMirrorsTheRightHand() {
        Vector3d right = tip(0, false, false, null, ITEM_CENTER);
        Vector3d left = tip(0, false, true, null, ITEM_CENTER);
        assertEquals(-right.x, left.x, 1e-9);
        assertEquals(right.y, left.y, 1e-9);
        assertEquals(right.z, left.z, 1e-9);
    }

    @Test
    void bodyYawTurnsTheHandAroundTheFeet() {
        Vector3d south = tip(0, false, false, null, ITEM_CENTER);
        Vector3d west = tip(90, false, false, null, ITEM_CENTER);
        assertEquals(Math.hypot(south.x, south.z), Math.hypot(west.x, west.z), 1e-9);
        assertEquals(south.y, west.y, 1e-9);
        // Facing west (-X), the right hand is to the north (-Z) and the item is ahead (-X).
        assertTrue(west.z < 0 && west.x < 0, "offset " + west);
    }

    @Test
    void sneakingLowersTheHand() {
        assertTrue(tip(0, true, false, null, ITEM_CENTER).y < tip(0, false, false, null, ITEM_CENTER).y);
    }

    @Test
    void displayTransformMovesTheItem() {
        HandTipEstimator.ItemTransform raised = new HandTipEstimator.ItemTransform(
                new Vector3d(0, 0, 0), new Vector3d(0, 16, 0), new Vector3d(1, 1, 1));
        Vector3d plain = tip(0, false, false, null, ITEM_CENTER);
        Vector3d moved = tip(0, false, false, raised, ITEM_CENTER);
        assertEquals(1, plain.distance(moved) / 0.9375, 1e-6, "16 pixels of item translation is one block before player scale");
    }

    @Test
    void wandTipIsFurtherFromTheHandThanTheItemCenter() {
        Vector3d hand = tip(0, false, false, null, ITEM_CENTER);
        Vector3d tip = tip(0, false, false, null, new Vector3d(8, 14, 24));
        assertTrue(tip.distance(hand) > 0.5, "distance " + tip.distance(hand));
    }

    @Test
    void freeModelLocatorsShiftIntoItemSpace() {
        Vector3d itemSpace = HandTipEstimator.itemSpace(new Vector3d(0, 14, 16));
        assertEquals(new Vector3d(8, 14, 24), itemSpace);
    }
}
