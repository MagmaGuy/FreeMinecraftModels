package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.config.props.PropsConfig;
import com.magmaguy.freeminecraftmodels.config.props.PropsConfigFields;
import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.StaticEntity;
import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.List;

public class SpawnCommand extends AdvancedCommand {

    List<String> spawnTypes = List.of("static", "dynamic", "prop");
    List<String> entityIDs = new ArrayList<>();

    public SpawnCommand() {
        super(List.of("spawn"));
        addArgument("type", new ListStringCommandArgument(List.of("STATIC", "DYNAMIC", "PROP"), "<spawn>"));
        entityIDs = new ArrayList<>();
        FileModelConverter.getConvertedFileModels().values().forEach(fileModelConverter -> entityIDs.add(fileModelConverter.getID()));
        addArgument("model", new ListStringCommandArgument(entityIDs, "<model>"));
        setDescription("Spawns custom models or creates props");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm spawn <static/dynamic/prop> <modelID>");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String modelID = commandData.getStringArgument("model");

        if (!entityIDs.contains(modelID)) {
            Logger.sendMessage(commandData.getCommandSender(), "Invalid entity ID!");
            return;
        }

        String type = commandData.getStringArgument("type").toLowerCase();

        if (type.equals("prop")) {
            createProp(player, modelID);
            return;
        }

        // Handle static and dynamic types (existing functionality)
        RayTraceResult rayTraceResult = player.rayTraceBlocks(300);
        if (rayTraceResult == null) {
            Logger.sendMessage(commandData.getCommandSender(), "You need to be looking at the ground to spawn a mob!");
            return;
        }

        Location location = rayTraceResult.getHitBlock().getLocation().add(0.5, 1, 0.5);
        location.setPitch(0);
        location.setYaw(180);

        if (type.equals("static")) {
            StaticEntity.create(modelID, location);
        } else if (type.equals("dynamic")) {
            DynamicEntity.create(modelID, (LivingEntity) location.getWorld().spawnEntity(location, EntityType.PIG));
        }
    }

    private void createProp(Player player, String propFilename) {
        // Get or create the prop configuration, messaging is now handled in PropsConfig
        PropsConfigFields prop = PropsConfig.addPropConfigurationFile(propFilename, player);
        prop.permanentlyAddLocation(player.getLocation());
        Logger.sendMessage(player, "Successfully added prop!");
    }
}