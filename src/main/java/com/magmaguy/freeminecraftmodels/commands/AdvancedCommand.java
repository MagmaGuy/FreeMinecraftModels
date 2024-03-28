package com.magmaguy.freeminecraftmodels.commands;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.List;

public abstract class AdvancedCommand {
    public final List<String> aliases;
    public final String description;
    public final String permission;
    public final boolean onlyForPlayers;
    public final boolean enabled = true;
    public final String usage;

    public AdvancedCommand(List<String> aliases, String description, String permission, boolean onlyForPlayers, String usage) {
        this.aliases = aliases;
        this.description = description;
        this.permission = permission;
        this.onlyForPlayers = onlyForPlayers;
        this.usage = usage;
    }

    public abstract void execute(CommandSender sender, String[] arguments);

    public abstract List<String> onTabComplete(CommandSender commandSender, org.bukkit.command.Command command, String label, String[] args);

    protected List<String> trimSuggestions(List<String> suggestions, String input) {
        if (input.isEmpty()) return suggestions;
        List<String> newList = new ArrayList<>();
        for (String suggestion : suggestions)
            if (suggestion.contains(input))
                newList.add(suggestion);
        return newList;
    }

}
