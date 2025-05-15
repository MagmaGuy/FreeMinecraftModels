package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfigFields;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class PropCommand extends AdvancedCommand {
    List<String> entityIDs = new ArrayList<>();

    public PropCommand() {
        super(List.of("prop"));
        addLiteral("create");
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));
        addArgument("model", new ListStringCommandArgument(entityIDs, "<model>"));
        setDescription("Creates a prop's configuration file, allowing you to place a model as a permanent prop!");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm prop create <modelFilename>");
    }

    public static void CreateProp(Player commandSender, String propFilename) {
        PropsConfigFields newProp = PropsConfig.getPropsConfigs().get(propFilename);
        if (newProp == null) {
            newProp = PropsConfig.addPropConfigurationFile(propFilename);
            Logger.sendMessage(commandSender.getPlayer(), "Created new prop config file at ~/plugins/FreeMinecraftModels/props/" + propFilename + ".yml");
        }
        newProp.permanentlyAddLocation(commandSender.getLocation());
    }

    @Override
    public void execute(CommandData commandData) {
        String modelFilename = commandData.getStringArgument("model");

        if (!entityIDs.contains(modelFilename)) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }

        CreateProp(commandData.getPlayerSender(), modelFilename);
        Logger.sendMessage(commandData.getCommandSender(), "Successfully added prop!");
    }
}
