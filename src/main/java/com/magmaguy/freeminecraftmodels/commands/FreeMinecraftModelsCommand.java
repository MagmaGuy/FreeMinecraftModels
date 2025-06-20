package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;

import java.util.List;

public class FreeMinecraftModelsCommand extends AdvancedCommand {

    public FreeMinecraftModelsCommand() {
        super(List.of());
        addArgument("action", new ListStringCommandArgument(
                List.of("visualize"),
                "<action>"));
        setPermission("freeminecraftmodels.*");
        setDescription("Just shares some basic info about FreeMinecraftModels.");
        setUsage("/fmm");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Logger.sendMessage(commandData.getCommandSender(), "FreeMinecraftModels is a plugin that allows you to use Minecraft models in your world.");
        Logger.sendMessage(commandData.getCommandSender(), "It is primarily used as a library by other plugins, and does not require using commands.");
        Logger.sendMessage(commandData.getCommandSender(), "FreeMinecraftModels best used alongside ResourcePackManager, another plugin made by MagmaGuy, which can automatically manage your resource packs.");
        Logger.sendMessage(commandData.getCommandSender(), "If you want to try this plugin out, you can try EliteMobs which uses it to give custom models to some bosses.");
    }

}