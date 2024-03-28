package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;

public class SpawnCommand extends AdvancedCommand {

    List<String> spawnTypes = List.of("static", "dynamic");
    List<String> entityIDs = new ArrayList<>();

    public SpawnCommand() {
        super(List.of("spawn"), "Spawns a custom models", "*", true, "/fmm spawn <static/dynamic> <modelID>");
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));
    }

    private boolean spawnCommand(CommandSender commandSender, String[] args) {
        Player player = (Player) commandSender;
        if (!entityIDs.contains(args[2])) {
            commandSender.sendMessage("[FreeMinecraftModels] Invalid entity ID!");
            return false;
        }
        RayTraceResult rayTraceResult = player.rayTraceBlocks(300);
        if (rayTraceResult == null) {
            player.sendMessage("[FMM] You need to be looking at the ground to spawn a mob!");
            return false;
        }
        Location location = rayTraceResult.getHitBlock().getLocation().add(0.5, 1, 0.5);
        location.setPitch(0);
        location.setYaw(180);
        if (args[1].equalsIgnoreCase("static"))
            StaticEntity.create(args[2], location);
        else if (args[1].equalsIgnoreCase("dynamic"))
            DynamicEntity.create(args[2], (LivingEntity) location.getWorld().spawnEntity(location, EntityType.PIG));
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] arguments) {
        spawnCommand(sender, arguments);
    }

    public List<String> onTabComplete(CommandSender sender,
                                      Command command,
                                      String alias,
                                      String[] args) {
        if (args.length == 2) return trimSuggestions(spawnTypes, args[1]);
        else if (args.length == 3) return trimSuggestions(entityIDs, args[2]);
        return null;
    }

}
