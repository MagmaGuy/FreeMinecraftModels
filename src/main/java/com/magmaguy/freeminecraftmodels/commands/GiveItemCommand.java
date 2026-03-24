package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class GiveItemCommand extends AdvancedCommand {

    public GiveItemCommand() {
        super(List.of("giveitem"));
        List<String> itemIds = new ArrayList<>(ItemScriptManager.getItemDefinitions().keySet());
        addArgument("item", new ListStringCommandArgument(itemIds, "<item>"));
        setDescription("Gives a custom FMM item to the player");
        setPermission("freeminecraftmodels.admin");
        setUsage("/fmm giveitem <item>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String itemId = commandData.getStringArgument("item");

        com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields config =
                ItemScriptManager.getItemDefinitions().get(itemId);
        if (config == null) {
            Logger.sendMessage(player, "&cUnknown custom item: " + itemId);
            return;
        }
        ItemStack item = com.magmaguy.freeminecraftmodels.utils.ModelItemFactory.createCustomItem(itemId, config);

        player.getInventory().addItem(item);
        Logger.sendMessage(player, "&aGave custom item: " + itemId);
    }
}
