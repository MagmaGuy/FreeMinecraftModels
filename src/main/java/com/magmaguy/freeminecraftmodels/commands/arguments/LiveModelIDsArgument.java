package com.magmaguy.freeminecraftmodels.commands.arguments;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.arguments.ICommandArgument;
import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Tab-completes against the current set of loaded model IDs. Re-reads
 * {@link FileModelConverter#getConvertedFileModels()} on each call so newly
 * loaded models appear without a server restart.
 */
public class LiveModelIDsArgument implements ICommandArgument {
    private final String hint;

    public LiveModelIDsArgument(String hint) {
        this.hint = hint;
    }

    private List<String> ids() {
        return FileModelConverter.getConvertedFileModels().values().stream()
                .map(FileModelConverter::getID)
                .toList();
    }

    @Override
    public String hint() {
        return hint;
    }

    @Override
    public boolean matchesInput(String input) {
        return ids().stream().anyMatch(id -> id.equalsIgnoreCase(input));
    }

    @Override
    public List<String> literals() {
        return ids();
    }

    @Override
    public List<String> getSuggestions(CommandSender sender, String partialInput) {
        String lower = partialInput.toLowerCase();
        return ids().stream()
                .filter(id -> id.toLowerCase().startsWith(lower))
                .toList();
    }

    @Override
    public boolean isLiteral() {
        return false;
    }
}
