package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.pets.PetManager;
import com.magmaguy.freeminecraftmodels.pets.PetsMenu;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.util.Logger;

import java.util.List;

/** Opens the player's pets menu, where pets are brought along or sent home. */
public class PetsCommand extends AdvancedCommand {
    public PetsCommand() {
        super(List.of("pets"));
        setUsage("/fmm pets");
        setPermission("freeminecraftmodels.pets");
        setSenderType(SenderType.PLAYER);
        setDescription("Opens your pets menu.");
    }

    @Override
    public void execute(CommandData commandData) {
        if (PetManager.get() == null) {
            Logger.sendMessage(commandData.getCommandSender(), "&cPets are not available on this server.");
            return;
        }
        PetsMenu.open(commandData.getPlayerSender());
    }
}
