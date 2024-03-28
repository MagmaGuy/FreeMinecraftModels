package com.magmaguy.freeminecraftmodels.customentity.core;

import com.magmaguy.freeminecraftmodels.utils.TransformationMatrix;
import lombok.Getter;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

public class AnimatableBone {

    protected final TransformationMatrix localMatrix;
    protected final boolean root;
    private final Vector blueprintModelCenter;
    private final Vector parentBlueprintModelCenter;
    private final Vector blueprintModelPivot;
    private final EulerAngle originalBoneRotation;
    private final TransformationMatrix parentGlobalMatrix;
    @Getter
    //Relative to the world
    private TransformationMatrix globalMatrix;

    protected AnimatableBone(Vector blueprintModelCenter,
                             Vector parentBlueprintModelCenter,
                             Vector blueprintModelPivot,
                             EulerAngle originalBoneRotation,
                             boolean root,
                             TransformationMatrix parentGlobalMatrix) {
        localMatrix = new TransformationMatrix();
        globalMatrix = new TransformationMatrix();
        this.blueprintModelCenter = blueprintModelCenter;
        this.parentBlueprintModelCenter = parentBlueprintModelCenter;
        this.blueprintModelPivot = blueprintModelPivot;
        this.originalBoneRotation = originalBoneRotation;
        this.root = root;
        this.parentGlobalMatrix = parentGlobalMatrix;
    }

    public void updateGlobalTransform() {
        if (parentGlobalMatrix != null)
            TransformationMatrix.multiplyMatrices(parentGlobalMatrix, localMatrix, globalMatrix);
        else {
            globalMatrix = localMatrix;
        }
    }

    public void transform(Vector animationRotation,
                          Vector animationTranslation,
                          float yaw) {
        //Inherit rotation and translation values from parents
        updateLocalTransform(animationRotation, animationTranslation, yaw);
        updateGlobalTransform();
    }

    public void updateLocalTransform(Vector animationRotation,
                                     Vector animationTranslation,
                                     float yaw) {
        localMatrix.resetToIdentityMatrix();
        //Shift to model center
        localMatrix.translate(blueprintModelCenter);

        if (parentBlueprintModelCenter != null) localMatrix.translate(parentBlueprintModelCenter.clone().multiply(-1));

        //Add the pivot point for the rotation - is removed later
        localMatrix.translate((float) -blueprintModelPivot.getX(), (float) -blueprintModelPivot.getY(), (float) -blueprintModelPivot.getZ());

        //Animate
        localMatrix.rotate((float) animationRotation.getX(), (float) animationRotation.getY(), (float) animationRotation.getZ());
        localMatrix.translate((float) animationTranslation.getX(), (float) animationTranslation.getY(), (float) animationTranslation.getZ());

        //Apply the bone's default rotation to the matrix
        localMatrix.rotate(
                (float) originalBoneRotation.getX(),
                (float) originalBoneRotation.getY(),
                (float) originalBoneRotation.getZ());


        //Remove the pivot point, go back to the model center
        localMatrix.translate((float) blueprintModelPivot.getX(), (float) blueprintModelPivot.getY(), (float) blueprintModelPivot.getZ());

        //rotate by yaw amount
        if (root) {
//            float yaw = skeleton.getCurrentLocation().getYaw();
//            if (yaw < 0) yaw = Math.abs(yaw);
//            else yaw = -yaw;
            localMatrix.rotate(0, -(float) Math.toRadians(yaw + 180), 0);
        }

    }
}
