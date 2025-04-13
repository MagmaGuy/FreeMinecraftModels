package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.core.ModeledEntityOBBExtension;
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
        super(List.of("obbdebug"));
        addArgument("action", new ListStringCommandArgument(
                List.of("visualize", "info", "reload", "help"),
                "<action>"));
        // Optional duration argument for visualize
        addArgument("duration", new ListStringCommandArgument(
                List.of("60", "100", "200", "600"),
                "[duration]"));

        setDescription("Debug commands for OBB hitbox system");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm obbdebug <visualize|info|reload|help> [duration]");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        String action = commandData.getStringArgument("action");

        switch (action.toLowerCase()) {
            case "visualize":
                handleVisualize(player, commandData);
                break;
            case "info":
                handleInfo(player);
                break;
            case "reload":
                handleReload(player);
                break;
            case "help":
            default:
                showHelp(player);
                break;
        }
    }

    private void handleVisualize(Player player, CommandData commandData) {
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
            ModeledEntityOBBExtension.visualizeOBB(entity, finalDuration);
        });

        Logger.sendMessage(player, ChatColor.GREEN + "Visualizing " + nearbyEntities.size() +
                " modeled entities for " + (finalDuration / 20.0) + " seconds.");
    }

    private void handleInfo(Player player) {
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

        Logger.sendMessage(player, ChatColor.GREEN + "Found " + nearbyEntities.size() + " modeled entities nearby:");

        for (ModeledEntity entity : nearbyEntities) {
            Logger.sendMessage(player, ChatColor.YELLOW + "- Entity ID: " + entity.getEntityID());
            Logger.sendMessage(player, ChatColor.YELLOW + "  Location: " + formatLocation(entity.getLocation()));

            if (entity.getSkeletonBlueprint() != null && entity.getSkeletonBlueprint().getHitbox() != null) {
                Logger.sendMessage(player, ChatColor.YELLOW + "  Size: " +
                        entity.getSkeletonBlueprint().getHitbox().getWidth() + " x " +
                        entity.getSkeletonBlueprint().getHitbox().getHeight());
            } else {
                Logger.sendMessage(player, ChatColor.YELLOW + "  No hitbox defined in model.");
            }
        }
    }

    private String formatLocation(org.bukkit.Location location) {
        return String.format("(%.1f, %.1f, %.1f)",
                location.getX(), location.getY(), location.getZ());
    }

    private void handleReload(Player player) {
        // Reload OBB system
        Logger.sendMessage(player, ChatColor.YELLOW + "Reloading OBB hitbox system...");

        // Clear the OBB cache
        OrientedBoundingBoxRayTracer.clearCache();

        // Recalculate OBBs for all entities
        List<ModeledEntity> allEntities = ModeledEntityManager.getAllEntities();
        for (ModeledEntity entity : allEntities) {
            ModeledEntityOBBExtension.setOBBFromHitboxProperties(entity);
        }

        Logger.sendMessage(player, ChatColor.GREEN + "OBB hitbox system reloaded for " +
                allEntities.size() + " entities.");
    }

    private void showHelp(Player player) {
        Logger.sendMessage(player, ChatColor.GREEN + "=== OBB Hitbox Debug Commands ===");
        Logger.sendMessage(player, ChatColor.YELLOW + "/fmm obbdebug visualize [duration] " +
                ChatColor.WHITE + "- Visualize hitboxes of nearby entities");
        Logger.sendMessage(player, ChatColor.YELLOW + "/fmm obbdebug info " +
                ChatColor.WHITE + "- Show info about nearby modeled entities");
        Logger.sendMessage(player, ChatColor.YELLOW + "/fmm obbdebug reload " +
                ChatColor.WHITE + "- Reload all entity hitboxes");
        Logger.sendMessage(player, ChatColor.YELLOW + "/fmm obbdebug help " +
                ChatColor.WHITE + "- Show this help message");
    }
}