package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.commands.arguments.LiveModelIDsArgument;
import com.magmaguy.freeminecraftmodels.customentity.DisguiseManager;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.PlayerCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;

public class DisguiseCommand extends AdvancedCommand {

    public DisguiseCommand() {
        super(List.of("disguise"));
        setDescription("Disguises a player as a model (or another player if a target is given)");
        setPermission("freeminecraftmodels.disguise.self");
        setUsage("/fmm disguise <modelID> [player]");
        // Sender type is ANY so console can run the targeted form; we
        // enforce 'must be a player' in execute() only on the self branch.
        setSenderType(SenderType.ANY);
        addArgument("models", new LiveModelIDsArgument("<modelID>"));
        addOptionalArgument("target", new PlayerCommandArgument());
    }

    @Override
    public void execute(CommandData commandData) {
        // Resolve target + permissions FIRST so unauthorized users don't get
        // model-existence feedback (would leak which model IDs are loaded).
        String[] args = commandData.getArgs();
        Player target;
        if (args.length >= 3 && !args[2].isBlank()) {
            // Targeted form — requires .others permission.
            if (!commandData.getCommandSender().hasPermission("freeminecraftmodels.disguise.others")) {
                Logger.sendMessage(commandData.getCommandSender(),
                        ChatColor.RED + "You lack permission: freeminecraftmodels.disguise.others");
                return;
            }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Player '" + args[2] + "' is not online.");
                return;
            }
        } else {
            // Self form — sender must be a player.
            if (!(commandData.getCommandSender() instanceof Player playerSender)) {
                Logger.sendMessage(commandData.getCommandSender(),
                        "Specify a target player when running this command from console: /fmm disguise <modelID> <player>");
                return;
            }
            target = playerSender;
        }

        String modelID = commandData.getStringArgument("models");
        if (modelID == null || !FileModelConverter.getConvertedFileModels().containsKey(modelID)) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }

        if (!DisguiseManager.disguise(target, modelID)) {
            Logger.sendMessage(commandData.getCommandSender(),
                    "Failed to disguise — model '" + modelID + "' could not be created.");
        }
    }
}
