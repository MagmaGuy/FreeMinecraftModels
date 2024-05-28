package com.magmaguy.freeminecraftmodels.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class CommandManager implements CommandExecutor, TabCompleter {

    public final List<AdvancedCommand> commands = new ArrayList<>();

    public CommandManager(JavaPlugin javaPlugin) {
        javaPlugin.getCommand("freeminecraftmodels").setExecutor(this);
        registerCommands();
    }

    private void registerCommands() {
        registerCommand(new SpawnCommand());
        registerCommand(new ReloadCommand());
        registerCommand(new VersionCommand());
//        registerCommand(new PropCommand());
    }

    public void registerCommand(AdvancedCommand command) {
        commands.add(command);
    }

    public void unregisterCommand(Command command) {
        commands.remove(command);
    }


    private void sendMessage(CommandSender commandSender, String message) {
        commandSender.sendMessage("[FreeMinecraftModels] " + message);
    }

    @Override
    public boolean onCommand(CommandSender commandSender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sendMessage(commandSender, "Valid commands:");
            commands.forEach(command -> commandSender.sendMessage(command.usage));
            return true;
        }

        for (AdvancedCommand command : commands) {
            // We don't want to execute other commands or ones that are disabled
            if (!(command.aliases.contains(args[0]) && command.enabled)) {
                continue;
            }

            if (command.onlyForPlayers && !(commandSender instanceof Player)) {
                // Must be a player
                commandSender.sendMessage("[FreeMinecraftModels] This command must be run as a player!");
                return false;
            }

            if (!((commandSender.hasPermission(command.permission) ||
                    command.permission.equalsIgnoreCase("") ||
                    command.permission.equalsIgnoreCase("freeminecraftmodels.")) &&
                    command.enabled)) {
                // No permissions
                commandSender.sendMessage("[FreeMinecraftModels] You do not have the permission to run this command!");
                return false;
            }

            command.execute(commandSender, args);
            return true;
        }
        // Unknown command message
        commandSender.sendMessage("[FreeMinecraftModels] Unknown command!");
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command cmd, String label, String[] args) {
        // Handle the tab completion if it's a sub-command.
        if (args.length == 1) {
            List<String> result = new ArrayList<>();
            for (AdvancedCommand command : commands) {
                for (String alias : command.aliases) {
                    if (alias.toLowerCase().startsWith(args[0].toLowerCase()) && (
                            command.enabled && (commandSender.hasPermission(command.permission)
                                    || command.permission.equalsIgnoreCase("") || command.permission
                                    .equalsIgnoreCase("freeminecraftmodels.")))) {
                        result.add(alias);
                    }
                }
            }
            return result;
        }

        // Let the sub-command handle the tab completion
        for (AdvancedCommand command : commands) {
            if (command.aliases.contains(args[0]) && (command.enabled && (
                    commandSender.hasPermission(command.permission) || command.permission.equalsIgnoreCase("")
                            || command.permission.equalsIgnoreCase("freeminecraftmodels.")))) {
                return command.onTabComplete(commandSender, cmd, label, args);
            }
        }
        return null;
    }
}
