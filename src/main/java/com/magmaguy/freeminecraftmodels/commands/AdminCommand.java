package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.menus.AdminContentMenu;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;

import java.util.List;

public class AdminCommand extends AdvancedCommand {

    public AdminCommand() {
        super(List.of("admin"));
        setDescription("Opens the admin content browser");
        setPermission("freeminecraftmodels.admin");
        setUsage("/fmm admin");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        new AdminContentMenu(commandData.getPlayerSender());
    }
}
