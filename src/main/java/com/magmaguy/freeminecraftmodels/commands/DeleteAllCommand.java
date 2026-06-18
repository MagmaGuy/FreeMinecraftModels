package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.freeminecraftmodels.api.ModeledEntityManager;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropCleanupRegistry;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.arguments.IntegerCommandArgument;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Command to delete all modeled entities in the current world.
 */
public class DeleteAllCommand extends AdvancedCommand {

    public DeleteAllCommand() {
        super(List.of("deleteall"));
        addOptionalArgument("radius", new IntegerCommandArgument(List.of(10, 25, 50, 100), "[radius]"));
        setDescription("Delete all loaded modeled entities and persistent props");
        setPermission("freeminecraftmodels.deleteall");
        setUsage("/fmm deleteall [radius]");
    }

    @Override
    public void execute(CommandData commandData) {
        Integer radius = commandData.getIntegerArgument("radius");
        Player player = commandData.getCommandSender() instanceof Player playerSender ? playerSender : null;
        if (radius != null && player == null) {
            Logger.sendMessage(commandData.getCommandSender(), ChatColor.RED + "Only players can use a delete radius.");
            return;
        }
        if (radius != null && radius <= 0) {
            Logger.sendMessage(commandData.getCommandSender(), ChatColor.RED + "Radius must be greater than 0.");
            return;
        }

        final Location center = player != null && radius != null ? player.getLocation() : null;
        final double radiusSquared = radius != null ? radius * radius : 0;
        int removedCount = 0;
        Set<UUID> removedUnderlyingEntities = new HashSet<>();

        for (ModeledEntity allEntity : ModeledEntityManager.getAllEntities()) {
            if (!isWithinRadius(allEntity, center, radiusSquared)) continue;
            if (allEntity instanceof PropEntity propEntity) {
                if (propEntity.getUnderlyingEntity() != null) {
                    removedUnderlyingEntities.add(propEntity.getUnderlyingEntity().getUniqueId());
                }
                propEntity.permanentlyRemove();
            } else {
                allEntity.remove();
            }
            removedCount++;
        }

        removedCount += PropCleanupRegistry.clearRegisteredProps(entity -> isWithinRadius(entity, center, radiusSquared));
        removedCount += PropCleanupRegistry.clearLoadedUnregisteredProps(removedUnderlyingEntities,
                armorStand -> isWithinRadius(armorStand, center, radiusSquared));

        if (removedCount > 0) {
            String radiusDescription = radius != null ? " within " + radius + " blocks" : "";
            Logger.sendMessage(commandData.getCommandSender(), ChatColor.GREEN + "Successfully deleted "
                    + removedCount + " modeled entities" + radiusDescription + ".");
        } else {
            Logger.sendMessage(commandData.getCommandSender(), ChatColor.YELLOW + "No modeled entities found to delete.");
        }
    }

    private boolean isWithinRadius(ModeledEntity modeledEntity, Location center, double radiusSquared) {
        if (center == null) return true;
        if (modeledEntity.getWorld() == null || modeledEntity.getLocation() == null) return false;
        if (!modeledEntity.getWorld().equals(center.getWorld())) return false;
        return modeledEntity.getLocation().distanceSquared(center) <= radiusSquared;
    }

    private boolean isWithinRadius(Entity entity, Location center, double radiusSquared) {
        if (center == null) return true;
        if (entity.getWorld() == null || entity.getLocation() == null) return false;
        if (!entity.getWorld().equals(center.getWorld())) return false;
        return entity.getLocation().distanceSquared(center) <= radiusSquared;
    }
}
