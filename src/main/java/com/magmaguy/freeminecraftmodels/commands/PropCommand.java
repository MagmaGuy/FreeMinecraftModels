package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;

public class PropCommand extends AdvancedCommand {

    public PropCommand() {
        super(List.of("prop", "create"), "Creates a prop's configuration file, allowing you to place a model as a permanent prop!", "*", false, "/fmm prop create <modelFilename>");
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (arguments.length < 3) {
            sender.sendMessage("[FreeMinecraftModels] Invalid command usage. Correct usage: /fmm prop create <modelFilename>");
            return;
        }
        if (!arguments[0].equalsIgnoreCase("prop")) {
            return;
        }
        if (!arguments[1].equalsIgnoreCase("create")) {
            return;
        }
        String modelFilename = arguments[2];
        if (!FileModelConverter.getConvertedFileModels().containsKey(modelFilename)) {
            sender.sendMessage("[FreeMinecraftModels] The model file does not exist. Pick a filename that exists in the models folder of FreeMinecraftModels");
            return;
        }
        PropsConfig.CreateProp(sender, modelFilename);
        sender.sendMessage("[FreeMinecraftModels] Successfully added configuration file! You can start placing your prop now.");
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        return List.of();
    }
}
