package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;

import java.util.ArrayList;
import java.util.List;

public class PropCommand extends AdvancedCommand {

    public PropCommand() {
        super(List.of("prop"));
        addLiteral("create");
        //todo: this should be its own category of model so not adding all models here
        addArgument("models", new ListStringCommandArgument(new ArrayList<>(), "<modelID>"));
        setDescription("Creates a prop's configuration file, allowing you to place a model as a permanent prop!");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm prop create <modelFilename>");
    }


    @Override
    public void execute(CommandData commandData) {
        String modelFilename = commandData.getStringArgument("model");
        if (!FileModelConverter.getConvertedFileModels().containsKey(modelFilename)) {
            Logger.sendMessage(commandData.getCommandSender(), "The model file does not exist. Pick a filename that exists in the models folder of FreeMinecraftModels");
            return;
        }
        PropsConfig.CreateProp(commandData.getCommandSender(), modelFilename);
        Logger.sendMessage(commandData.getCommandSender(), "Successfully added configuration file! You can start placing your prop now.");

    }
}
