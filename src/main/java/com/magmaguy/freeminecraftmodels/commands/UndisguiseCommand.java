package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.PlayerCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;

public class UndisguiseCommand extends AdvancedCommand {

    public UndisguiseCommand() {
        super(List.of("undisguise"));
        setDescription("Undisguises a player currently disguised as a model");
        setPermission("freeminecraftmodels.disguise.self");
        setUsage("/fmm undisguise [player]");
        setSenderType(SenderType.ANY);
        addOptionalArgument("target", new PlayerCommandArgument());
    }

    @Override
    public void execute(CommandData commandData) {
        String[] args = commandData.getArgs();
        Player target;
        if (args.length >= 2 && !args[1].isBlank()) {
            if (!commandData.getCommandSender().hasPermission("freeminecraftmodels.disguise.others")) {
                Logger.sendMessage(commandData.getCommandSender(),
                        ChatColor.RED + "You lack permission: freeminecraftmodels.disguise.others");
                return;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Player '" + args[1] + "' is not online.");
                return;
            }
        } else {
            if (!(commandData.getCommandSender() instanceof Player playerSender)) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Specify a target player when running this command from console: /fmm undisguise <player>");
                return;
            }
            target = playerSender;
        }

        DisguiseManager.undisguise(target);
    }
}
