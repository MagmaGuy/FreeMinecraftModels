package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class GiveItemCommand extends AdvancedCommand {

    public GiveItemCommand() {
        super(List.of("giveitem"));
        addArgument("item", new ListStringCommandArgument("<item>") {
            @Override public boolean matchesInput(String input) {
                return ItemScriptManager.getItemDefinitions().containsKey(input.toLowerCase(java.util.Locale.ROOT));
            }
            @Override public List<String> literals() {
                return ItemScriptManager.getItemDefinitions().keySet().stream().sorted().toList();
            }
            @Override public List<String> getSuggestions(CommandSender sender, String partialInput) {
                String prefix = partialInput.toLowerCase(java.util.Locale.ROOT);
                return literals().stream().filter(id -> id.startsWith(prefix)).toList();
            }
        });
        setDescription("Gives a custom FMM item to the player");
        setPermission("freeminecraftmodels.admin");
        setUsage("/fmm giveitem <item>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String itemId = commandData.getStringArgument("item").toLowerCase(java.util.Locale.ROOT);

        com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields config =
                ItemScriptManager.getItemDefinitions().get(itemId);
        if (config == null) {
            Logger.sendMessage(player, "&cUnknown custom item: " + itemId);
            return;
        }
        ItemStack item;
        try { item = com.magmaguy.freeminecraftmodels.utils.ModelItemFactory.createCustomItem(itemId, config); }
        catch (IllegalArgumentException | IllegalStateException invalid) {
            Logger.sendMessage(player, "&cCannot create " + itemId + ": " + invalid.getMessage());
            return;
        }

        player.getInventory().addItem(item);
        Logger.sendMessage(player, "&aGave custom item: " + itemId);
    }
}
