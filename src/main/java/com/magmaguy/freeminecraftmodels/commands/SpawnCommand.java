package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
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
        super(List.of("spawn"));
        addArgument("type", List.of("STATIC", "DYNAMIC"));
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));
        addArgument("model", entityIDs);
        setDescription("Spawns a custom models");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm spawn <static/dynamic> <modelID>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        if (!entityIDs.contains(commandData.getStringArgument("model"))) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }
        RayTraceResult rayTraceResult = player.rayTraceBlocks(300);
        if (rayTraceResult == null) {
            Logger.sendMessage(commandData.getCommandSender(), "You need to be looking at the ground to spawn a mob!");
            return;
        }
        Location location = rayTraceResult.getHitBlock().getLocation().add(0.5, 1, 0.5);
        location.setPitch(0);
        location.setYaw(180);
        if (commandData.getStringArgument("type").equalsIgnoreCase("static"))
            StaticEntity.create(commandData.getStringArgument("model"), location);
        else if (commandData.getStringArgument("type").equalsIgnoreCase("dynamic"))
            DynamicEntity.create(commandData.getStringArgument("model"), (LivingEntity) location.getWorld().spawnEntity(location, EntityType.PIG));
    }
}
