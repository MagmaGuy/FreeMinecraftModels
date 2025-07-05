package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;

import java.util.ArrayList;
import java.util.List;

public class DisguiseCommand extends AdvancedCommand {
    List<String> entityIDs = new ArrayList<>();

    public DisguiseCommand() {
        super(List.of("disguise"));
        setDescription("Disguises a player as a model");
        setPermission("freeminecraftmodels.*");
        setDescription("/fmm disguise <modelID>");
        setSenderType(SenderType.PLAYER);
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));
        addArgument("models", new ListStringCommandArgument(entityIDs, "<modelID>"));
    }

    @Override
    public void execute(CommandData commandData) {
        if (!entityIDs.contains(commandData.getStringArgument("models"))) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }

        PlayerDisguiseEntity.create(
                commandData.getStringArgument("models"),
                commandData.getPlayerSender());
    }
}
