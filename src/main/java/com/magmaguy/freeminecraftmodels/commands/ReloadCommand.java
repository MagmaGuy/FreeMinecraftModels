package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public class ReloadCommand extends AdvancedCommand {
    public ReloadCommand() {
        super(List.of("reload"), "Reloads the plugin", "*", false, "/fmm reload");
    }

    public static void reloadPlugin(CommandSender sender) {
        MetadataHandler.PLUGIN.onDisable();
        MetadataHandler.PLUGIN.onEnable();
        sender.sendMessage("[FreeMinecraftModels] Reloaded!");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        reloadPlugin(sender);
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        return null;
    }
}
