package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.Bukkit;
import org.bukkit.util.EulerAngle;
import org.bukkit.util.Vector;

import java.util.logging.Logger;

/**
 * This class is used to log messages to the console.
 */
public class Developer {
    /**
     * The logger used to log messages to the console.
     */
    private static final Logger LOGGER = Bukkit.getLogger();

    /**
     * Private constructor to prevent instantiation.
     */
    private Developer() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * Logs an info message to the console.
     *
     * @param message The message to log.
     */
    public static void info(String message) {
        LOGGER.info("[FreeMinecraftModels] " + message);
    }

    /**
     * Logs a warning message to the console.
     *
     * @param message The message to log.
     */
    public static void warn(String message) {
        LOGGER.warning("[FreeMinecraftModels] " + message);
    }

    /**
     * Logs a debug message to the console.
     *
     * @param message The message to log.
     */
    public static void debug(String message) {
        LOGGER.warning("[FreeMinecraftModels] DEBUG: " + message);
    }

    public static String vectorToString(Vector vector) {
        return "Vector{" + vector.getX() + "," + vector.getY() + "," + vector.getZ() + "}";
    }

    public static String eulerAngleToString(EulerAngle eulerAngle) {
        return "Angle{" + eulerAngle.getX() + "," + eulerAngle.getY() + "," + eulerAngle.getZ() + "}";
    }
}
