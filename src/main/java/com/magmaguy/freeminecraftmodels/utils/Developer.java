package com.magmaguy.freeminecraftmodels.utils;

import org.bukkit.Bukkit;

public class Developer {
    public static void info(String message) {
        Bukkit.getLogger().info("[FreeMinecraftModels] " + message);
    }
    public static void warn(String message) {
        Bukkit.getLogger().warning("[FreeMinecraftModels] " + message);
    }
    public static void debug(String message) {
        Bukkit.getLogger().warning("[FreeMinecraftModels] DEBUG: " + message);
    }
}
