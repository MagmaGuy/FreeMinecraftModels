package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public class VersionCommand extends AdvancedCommand {
    public VersionCommand() {
        super(List.of("version"), "Reports the FreeMinecraftModels version", "", false, "/fmm version");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        sender.sendMessage("[FreeMinecraftModels] This server is running FreeMinecraftModels version " + MetadataHandler.PLUGIN.getDescription().getVersion());
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        return List.of();
    }
}
