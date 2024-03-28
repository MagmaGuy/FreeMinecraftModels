package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.utils.TransformationMatrix;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

public class DisplayEntityBone extends AnimatableBone {
    protected DisplayEntityBone(Vector blueprintModelCenter,
                                Vector parentBlueprintModelCenter,
                                Vector blueprintModelPivot,
                                EulerAngle originalBoneRotation,
                                boolean root,
                                TransformationMatrix parentGlobalMatrix) {
        super(blueprintModelCenter,
                parentBlueprintModelCenter,
                blueprintModelPivot,
                originalBoneRotation,
                root,
                parentGlobalMatrix);
    }
}
