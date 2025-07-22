package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.util.Logger;

import java.util.List;

public class StatsCommand extends AdvancedCommand {
    public StatsCommand() {
        super(List.of("stats"));
        setDescription("Shows the stats of FreeMinecraftModels");
        setPermission("freeminecraftmodels.*");
        setDescription("/fmm stats");
    }

    @Override
    public void execute(CommandData commandData) {
        Logger.sendMessage(commandData.getCommandSender(), "Loaded model count (total): " + ModeledEntity.getLoadedModeledEntities().size());
        Logger.sendMessage(commandData.getCommandSender(), "Loaded dynamic entities: " + DynamicEntity.getDynamicEntities().size());
    }
}
