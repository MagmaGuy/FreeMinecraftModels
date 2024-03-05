package com.magmaguy.freeminecraftmodels.thirdparty;

import com.magmaguy.freeminecraftmodels.config.DefaultConfig;
import com.magmaguy.freeminecraftmodels.utils.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;

public class Floodgate {
    private Floodgate() {
    }

    public static boolean isBedrock(Player player) {
        if (Bukkit.getPluginManager().isPluginEnabled("Floodgate"))
            return FloodgateApi.getInstance().isFloodgatePlayer(player.getUniqueId());
        return false;
    }

    public static boolean armorstandsArePossible() {
        if (Bukkit.getPluginManager().isPluginEnabled("Floodgate")) return true;
        if (!DefaultConfig.useDisplayEntitiesWhenPossible) return true;
        return VersionChecker.serverVersionOlderThan(19, 4);
    }
}
