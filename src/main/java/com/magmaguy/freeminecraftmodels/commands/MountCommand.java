package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class MountCommand extends AdvancedCommand {

    public MountCommand() {
        super(List.of("mount"));
        addArgument("model", new com.magmaguy.freeminecraftmodels.commands.arguments.LiveModelIDsArgument("<model>"));
        setDescription("Spawns a rideable model horse");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm mount <model>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String modelID = commandData.getStringArgument("model");

        if (!FileModelConverter.containsModel(modelID)) {
            Logger.sendMessage(player, "\u00a7cModel '" + modelID + "' not found!");
            return;
        }

        // Spawn a horse as the rideable base entity
        Horse horse = (Horse) player.getWorld().spawnEntity(player.getLocation(), EntityType.HORSE);
        horse.setTamed(true);
        horse.setOwner(player);
        horse.setAdult();
        horse.getInventory().setSaddle(new ItemStack(Material.SADDLE));
        horse.setRemoveWhenFarAway(false);
        horse.getAttribute(Attribute.MOVEMENT_SPEED).setBaseValue(0.3);
        horse.getAttribute(Attribute.JUMP_STRENGTH).setBaseValue(0.7);

        // Wrap horse with the custom model
        DynamicEntity dynamicEntity = DynamicEntity.createWithInvisibility(modelID, horse);
        if (dynamicEntity == null) {
            horse.remove();
            Logger.sendMessage(player, "\u00a7cFailed to create model for '" + modelID + "'!");
            return;
        }

        // The player rides the horse directly: a horse only takes steering from a player that is its first
        // passenger, so a seat entity in between would leave the rider unable to move it.
        Bukkit.getScheduler().scheduleSyncDelayedTask(MetadataHandler.PLUGIN, () -> {
            if (!horse.isValid() || !player.isOnline()) return;
            horse.addPassenger(player);
        }, 1);
    }
}
