package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.freeminecraftmodels.customentity.PlayerDisguiseEntity;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.util.Logger;

import java.util.Collection;
import java.util.List;

public class DisguiseListCommand extends AdvancedCommand {

    public DisguiseListCommand() {
        super(List.of("disguiselist"));
        setDescription("Lists currently disguised players");
        setPermission("freeminecraftmodels.disguise.others");
        setUsage("/fmm disguiselist");
        setSenderType(SenderType.ANY);
    }

    @Override
    public void execute(CommandData commandData) {
        Collection<PlayerDisguiseEntity> all = DisguiseManager.getAll();
        if (all.isEmpty()) {
            Logger.sendMessage(commandData.getCommandSender(), "No players are currently disguised.");
            return;
        }
        Logger.sendMessage(commandData.getCommandSender(),
                "Currently disguised players (" + all.size() + "):");
        for (PlayerDisguiseEntity entity : all) {
            Logger.sendMessage(commandData.getCommandSender(),
                    "  - " + entity.getDisguisedPlayer().getName() + " as " + entity.getEntityID());
        }
    }
}
