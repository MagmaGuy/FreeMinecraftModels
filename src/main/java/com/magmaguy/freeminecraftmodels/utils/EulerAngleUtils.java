package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.util.EulerAngle;
import org.jetbrains.annotations.NotNull;

public class EulerAngleUtils {
    private EulerAngleUtils() {
    }

    public static EulerAngle add(EulerAngle eulerAngle1, EulerAngle eulerAngle2) {
        return eulerAngle1.add(eulerAngle2.getX(), eulerAngle2.getY(), eulerAngle2.getZ());
    }

    public static EulerAngle add(EulerAngle eulerAngle1, EulerAngle eulerAngle2,EulerAngle eulerAngle3) {
        return add(add(eulerAngle1, eulerAngle2), eulerAngle3);
    }

    public static boolean isZero(@NotNull EulerAngle eulerAngle){
        return eulerAngle.getX() == 0 && eulerAngle.getY() == 0 && eulerAngle.getZ() == 0;
    }
}
