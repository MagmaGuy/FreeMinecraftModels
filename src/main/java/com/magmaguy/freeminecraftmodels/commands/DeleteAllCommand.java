package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Command to delete all modeled entities in the current world.
 */
public class DeleteAllCommand extends AdvancedCommand {

    public DeleteAllCommand() {
        super(List.of("deleteall"));
        setDescription("Delete all loaded modeled entities in your world");
        setPermission("freeminecraftmodels.deleteall");
        setUsage("/fmm deleteall");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();

        int removedCount = 0;

        // Remove all entities in the player's world
        for (ModeledEntity allEntity : ModeledEntityManager.getAllEntities()) {
            allEntity.remove();
            removedCount++;
        }

        // Notify player of the result
        if (removedCount > 0) {
            Logger.sendMessage(player, ChatColor.GREEN + "Successfully deleted "
                    + removedCount + " modeled entities.");
        } else {
            Logger.sendMessage(player, ChatColor.YELLOW + "No modeled entities found to delete.");
        }
    }
}