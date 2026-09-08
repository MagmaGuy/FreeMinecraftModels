package com.magmaguy.freeminecraftmodels.thirdparty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import java.lang.reflect.InvocationTargetException;

/** Optional, reload-safe bridge: transport lifecycle and policy remain owned by EliteMobs. */
public final class EliteMobsTransport {
    private EliteMobsTransport() {}

    public static boolean start(Player player, String routeId) {
        var plugin = Bukkit.getPluginManager().getPlugin("EliteMobs");
        if (plugin == null || !plugin.isEnabled()) return false;
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Transport must start on the server thread");
        try {
            Class<?> module = Class.forName("com.magmaguy.elitemobs.transport.TransportModule", true, plugin.getClass().getClassLoader());
            return Boolean.TRUE.equals(module.getMethod("startRoute", Player.class, String.class).invoke(null, player, routeId));
        } catch (ClassNotFoundException | NoSuchMethodException unavailable) {
            player.sendMessage("This transport requires a newer EliteMobs build.");
            return false;
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IllegalStateException("Could not start EliteMobs transport", failure);
        }
    }
}
