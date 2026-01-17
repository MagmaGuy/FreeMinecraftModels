package com.magmaguy.freeminecraftmodels.dataconverter;

import lombok.Getter;
import lombok.Setter;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Represents a null object element from a Blockbench model.
 * Null objects are IK controllers that define an IK chain and animate it.
 * They store references to:
 * - ik_source: The root bone of the IK chain (where the chain starts)
 * - ik_target: The end effector bone or locator (where the chain tries to reach)
 *
 * When animated, the null object's position offset from its rest position
 * becomes the IK goal that the chain tries to reach.
 */
public class NullObjectBlueprint {
    @Getter
    private final String uuid;
    @Getter
    private final String name;
    @Getter
    private final Vector3f restPosition;
    @Getter
    private final String ikTargetUUID;
    @Getter
    private final String ikSourceUUID;
    @Getter
    private final boolean lockIkTargetRotation;

    @Getter
    @Setter
    private BoneBlueprint parentBone;

    // Resolved references (set after skeleton is built)
    @Getter
    @Setter
    private BoneBlueprint ikSourceBone;
    @Getter
    @Setter
    private LocatorBlueprint ikTargetLocator;
    @Getter
    @Setter
    private BoneBlueprint ikTargetBone;

    /**
     * Creates a NullObjectBlueprint from parsed JSON data.
     *
     * @param nullObjectData The map containing null object data from the bbmodel file
     */
    public NullObjectBlueprint(Map<String, Object> nullObjectData) {
        this.uuid = (String) nullObjectData.get("uuid");
        this.name = (String) nullObjectData.get("name");

        // Parse rest position (the initial position before animation)
        List<Number> positionList = (List<Number>) nullObjectData.get("position");
        if (positionList != null && positionList.size() >= 3) {
            this.restPosition = new Vector3f(
                    positionList.get(0).floatValue(),
                    positionList.get(1).floatValue(),
                    positionList.get(2).floatValue()
            );
        } else {
            this.restPosition = new Vector3f(0, 0, 0);
        }

        // Parse IK target and source UUIDs
        this.ikTargetUUID = (String) nullObjectData.get("ik_target");
        this.ikSourceUUID = (String) nullObjectData.get("ik_source");

        // Parse lock rotation flag
        Object lockRotation = nullObjectData.get("lock_ik_target_rotation");
        this.lockIkTargetRotation = lockRotation != null && (Boolean) lockRotation;
    }

    /**
     * Checks if this null object has valid IK configuration.
     *
     * @return true if both ik_source and ik_target are set
     */
    public boolean hasValidIKConfig() {
        return ikSourceUUID != null && !ikSourceUUID.isEmpty()
                && ikTargetUUID != null && !ikTargetUUID.isEmpty();
    }

    /**
     * Gets the rest position in model space (converted from Blockbench units to game units).
     *
     * @return Position scaled to game units (divided by 16)
     */
    public Vector3f getModelSpaceRestPosition() {
        return new Vector3f(restPosition).mul(1f / 16f);
    }
}
