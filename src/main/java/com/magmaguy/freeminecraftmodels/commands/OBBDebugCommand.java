package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.OrientedBoundingBoxRayTracer;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.command.arguments.ListStringCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Debug command for the OBB hitbox system
 */
public class OBBDebugCommand extends AdvancedCommand {

    public OBBDebugCommand() {
        super(List.of("hitbox"));
        addArgument("action", new ListStringCommandArgument(
                List.of("visualize"),
                "<action>"));
        // Optional duration argument for visualize
        addArgument("duration", new ListStringCommandArgument(
                List.of("60", "100", "200", "600"),
                "[duration]"));

        setDescription("Debug commands for OBB hitbox system");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm hitbox visualize [duration]");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        int duration = 100; // Default duration in ticks (5 seconds)

        String durationArg = commandData.getStringArgument("duration");
        if (durationArg != null && !durationArg.isEmpty()) {
            try {
                duration = Integer.parseInt(durationArg);
                if (duration <= 0) {
                    duration = 100;
                } else if (duration > 1200) { // Cap at 60 seconds
                    duration = 1200;
                }
            } catch (NumberFormatException e) {
                Logger.sendMessage(player, "Invalid duration. Using default of 5 seconds.");
            }
        }

        // Get all nearby modeled entities
        List<ModeledEntity> nearbyEntities = ModeledEntityManager.getAllEntities().stream()
                .filter(entity -> entity.getWorld() != null &&
                        entity.getWorld().equals(player.getWorld()) &&
                        entity.getLocation().distanceSquared(player.getLocation()) < 100) // Within 10 blocks
                .collect(Collectors.toList());

        if (nearbyEntities.isEmpty()) {
            Logger.sendMessage(player, ChatColor.RED + "No modeled entities found nearby.");
            return;
        }

        int finalDuration = duration;
        nearbyEntities.forEach(entity -> {
            OrientedBoundingBoxRayTracer.visualizeOBB(entity, finalDuration);
        });

        Logger.sendMessage(player, ChatColor.GREEN + "Visualizing " + nearbyEntities.size() +
                " modeled entities for " + (finalDuration / 20.0) + " seconds.");
    }

}