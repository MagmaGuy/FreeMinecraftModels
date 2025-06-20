package com.magmaguy.freeminecraftmodels.thirdparty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class BedrockChecker {
    private BedrockChecker() {
    }

    public static boolean isBedrock(Player player) {
//        Logger.debug("Checking if player is bedrock: " + player.getName() + " floodgate is loaded: " + Bukkit.getPluginManager().isPluginEnabled("floodgate"));
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate"))
            return Floodgate.isBedrock(player);
        else if (Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot"))
            return Geyser.isBedrock(player);
        else return false;
    }
}
