package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.events.ResourcePackGenerationEvent;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand extends AdvancedCommand {
    public ReloadCommand() {
        super(List.of("reload"));
        setDescription("Reloads the plugin");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm reload");
    }

    public static void reloadPlugin(CommandSender sender) {
        MetadataHandler.PLUGIN.onDisable();
        MetadataHandler.PLUGIN.onEnable();
        if (Bukkit.getPluginManager().isPluginEnabled("ResourcePackManager"))
            Bukkit.getPluginManager().callEvent(new ResourcePackGenerationEvent());
        Logger.sendMessage(sender, "Reloaded!");
    }

    @Override
    public void execute(CommandData commandData) {
        reloadPlugin(commandData.getCommandSender());
    }
}
