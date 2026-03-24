package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.menus.CraftableItemsMenu;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;

import java.util.List;

public class FreeMinecraftModelsCommand extends AdvancedCommand {

    public FreeMinecraftModelsCommand() {
        super(List.of());
        addArgument("action", new ListStringCommandArgument(
                List.of("visualize", "setup", "initialize", "downloadall", "updatecontent"),
                "<action>"));
        setPermission("freeminecraftmodels.*");
        setDescription("Shares basic info about FreeMinecraftModels and points to the setup flow.");
        setUsage("/fmm");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        if (commandData.getCommandSender() instanceof Player player) {
            if (player.hasPermission("freeminecraftmodels.menu")) {
                new CraftableItemsMenu(player);
                return;
            }
        }
        Logger.sendMessage(commandData.getCommandSender(), "FreeMinecraftModels is a plugin that allows you to use Minecraft models in your world.");
        Logger.sendMessage(commandData.getCommandSender(), "Use &2/fmm setup &fto browse Nightbreak-managed model packs.");
        Logger.sendMessage(commandData.getCommandSender(), "Use &2/fmm initialize &ffor the first-time setup flow, or &2/fmm downloadall &fto install all available content.");
        Logger.sendMessage(commandData.getCommandSender(), "FreeMinecraftModels works especially well alongside ResourcePackManager, which can distribute the generated pack automatically.");
    }

}
