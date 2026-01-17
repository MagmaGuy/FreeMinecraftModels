package com.magmaguy.freeminecraftmodels.dataconverter;

import lombok.Getter;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Represents a locator element from a Blockbench model.
 * Locators are reference points in 3D space used as anchor points for IK targets.
 * They don't render but define positions that IK chains try to reach.
 */
public class LocatorBlueprint {
    @Getter
    private final String uuid;
    @Getter
    private final String name;
    @Getter
    private final Vector3f position;
    @Getter
    private final Vector3f rotation;
    @Getter
    private BoneBlueprint parentBone;

    /**
     * Creates a LocatorBlueprint from parsed JSON data.
     *
     * @param locatorData The map containing locator data from the bbmodel file
     */
    public LocatorBlueprint(Map<String, Object> locatorData) {
        this.uuid = (String) locatorData.get("uuid");
        this.name = (String) locatorData.get("name");

        // Parse position
        List<Number> positionList = (List<Number>) locatorData.get("position");
        if (positionList != null && positionList.size() >= 3) {
            this.position = new Vector3f(
                    positionList.get(0).floatValue(),
                    positionList.get(1).floatValue(),
                    positionList.get(2).floatValue()
            );
        } else {
            this.position = new Vector3f(0, 0, 0);
        }

        // Parse rotation (optional)
        List<Number> rotationList = (List<Number>) locatorData.get("rotation");
        if (rotationList != null && rotationList.size() >= 3) {
            this.rotation = new Vector3f(
                    (float) Math.toRadians(rotationList.get(0).floatValue()),
                    (float) Math.toRadians(rotationList.get(1).floatValue()),
                    (float) Math.toRadians(rotationList.get(2).floatValue())
            );
        } else {
            this.rotation = new Vector3f(0, 0, 0);
        }
    }

    /**
     * Sets the parent bone that contains this locator.
     * Called during skeleton building when the locator is found in a bone's children.
     *
     * @param parentBone The bone that contains this locator
     */
    public void setParentBone(BoneBlueprint parentBone) {
        this.parentBone = parentBone;
    }

    /**
     * Gets the position in model space (converted from Blockbench units to game units).
     *
     * @return Position scaled to game units (divided by 16)
     */
    public Vector3f getModelSpacePosition() {
        return new Vector3f(position).mul(1f / 16f);
    }
}
