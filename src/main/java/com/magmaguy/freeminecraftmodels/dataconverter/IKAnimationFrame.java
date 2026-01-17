package com.magmaguy.freeminecraftmodels.dataconverter;

/**
 * Stores the IK goal offset for a single frame of animation.
 * This represents the position offset of a null object from its rest position.
 */
public class IKAnimationFrame {
    // Goal position offset in model space (Blockbench units / 16)
    public float goalX = 0;
    public float goalY = 0;
    public float goalZ = 0;

    public IKAnimationFrame() {
    }

    public IKAnimationFrame(float x, float y, float z) {
        this.goalX = x;
        this.goalY = y;
        this.goalZ = z;
    }
}
