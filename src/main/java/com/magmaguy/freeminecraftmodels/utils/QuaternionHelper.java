package com.magmaguy.freeminecraftmodels.utils;

import org.apache.commons.math3.complex.Quaternion;
import org.bukkit.util.EulerAngle;

public class QuaternionHelper {
    private QuaternionHelper() {
    }

    public static Quaternion eulerToQuaternion(double originalX, double originalY, double originalZ) {
        double yaw = Math.toRadians(originalZ);
        double pitch = Math.toRadians(originalY);
        double roll = Math.toRadians(originalX);

        double cy = Math.cos(yaw * 0.5);
        double sy = Math.sin(yaw * 0.5);
        double cp = Math.cos(pitch * 0.5);
        double sp = Math.sin(pitch * 0.5);
        double cr = Math.cos(roll * 0.5);
        double sr = Math.sin(roll * 0.5);

        double w = cr * cp * cy + sr * sp * sy;
        double x = sr * cp * cy - cr * sp * sy;
        double y = cr * sp * cy + sr * cp * sy;
        double z = cr * cp * sy - sr * sp * cy;

        return new Quaternion(w, x, y, z);
    }

    public static EulerAngle quaternionToEuler(Quaternion q) {
        // Ensure the quaternion is normalized
        q.normalize();

        // Extract the quaternion components
        double w = q.getQ0();
        double x = q.getQ1();
        double y = q.getQ2();
        double z = q.getQ3();

        // Calculate Euler angles
        double pitch = Math.atan2(2.0 * (w * x + y * z), 1.0 - 2.0 * (x * x + y * y));
        double yaw = Math.asin(2.0 * (w * y - z * x));
        double roll = Math.atan2(2.0 * (w * z + x * y), 1.0 - 2.0 * (y * y + z * z));

        // Return as a vector - note that the output here is already in radian, no need for conversion
        return new EulerAngle(pitch, yaw, roll);
    }

}
