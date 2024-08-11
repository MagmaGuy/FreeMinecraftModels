package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.util.Logger;

import java.util.List;

public class VersionCommand extends AdvancedCommand {
    public VersionCommand() {
        super(List.of("version"));
        setDescription("Reports the FreeMinecraftModels version");
        setUsage("/fmm version");
    }

    @Override
    public void execute(CommandData commandData) {
        Logger.sendMessage(commandData.getCommandSender(), "This server is running FreeMinecraftModels version " + MetadataHandler.PLUGIN.getDescription().getVersion());
    }
}
