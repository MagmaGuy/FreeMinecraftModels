package com.magmaguy.freeminecraftmodels.thirdparty;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class BedrockChecker {
    private BedrockChecker() {
    }

    public static boolean isBedrock(Player player) {
        if (Bukkit.getPluginManager().isPluginEnabled("floodgate"))
            return Floodgate.isBedrock(player);
        else if (Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot"))
            return Geyser.isBedrock(player);
        else return false;
    }

    /**
     * @return true if a Bedrock proxy (Floodgate or Geyser) is installed and enabled, so
     * Bedrock players can actually connect. When false there can be no Bedrock viewers, so
     * the entire per-model Bedrock backend ({@code BedrockModeledEntity}) is dead weight and
     * should not be built or ticked. Case-insensitive so Geyser forks under non-canonical
     * names still match.
     */
    public static boolean isBedrockSupportPresent() {
        for (org.bukkit.plugin.Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (!plugin.isEnabled()) continue;
            String name = plugin.getName();
            if (name.equalsIgnoreCase("floodgate")
                    || name.equalsIgnoreCase("Geyser-Spigot")
                    || name.equalsIgnoreCase("Geyser")) {
                return true;
            }
        }
        return false;
    }
}
