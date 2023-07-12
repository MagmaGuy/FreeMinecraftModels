package com.magmaguy.freeminecraftmodels;

import org.bukkit.command.CommandSender;

public class ReloadHandler {
    public static void reload(CommandSender commandSender){
        MetadataHandler.PLUGIN.onDisable();
        MetadataHandler.PLUGIN.onEnable();
        commandSender.sendMessage("[FreeMinecraftModels] Reloaded!");
    }
}
