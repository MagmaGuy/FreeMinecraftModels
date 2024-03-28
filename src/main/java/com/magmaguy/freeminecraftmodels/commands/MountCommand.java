package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class MountCommand extends AdvancedCommand {

    List<String> entityIDs = new ArrayList<>();

    public MountCommand() {
        super(List.of("mount"), "Mounts a model (experimental!)", "*", true, "/fmm mount <modelID>");
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        if (!entityIDs.contains(arguments[1])) {
            sender.sendMessage("[FreeMinecraftModels] Invalid entity ID!");
            return;
        }

        DynamicEntity dynamicEntity = DynamicEntity.create(arguments[0], (LivingEntity) ((Player) sender).getWorld().spawnEntity(((Player) sender).getLocation(), EntityType.HORSE));

        Bukkit.getScheduler().scheduleSyncDelayedTask(MetadataHandler.PLUGIN, () -> {
            ((Horse) dynamicEntity.getLivingEntity()).setTamed(true);
            ((Horse) dynamicEntity.getLivingEntity()).setOwner(((Player) sender));
            ((Horse) dynamicEntity.getLivingEntity()).getInventory().setSaddle(new ItemStack(Material.SADDLE));
            dynamicEntity.getLivingEntity().addPassenger(((Player) sender));
        }, 5);
    }

    @Override
    public List<String> onTabComplete(CommandSender commandSender, Command command, String label, String[] args) {
        if (args.length == 2) return trimSuggestions(entityIDs, args[1]);
        return null;
    }
}
