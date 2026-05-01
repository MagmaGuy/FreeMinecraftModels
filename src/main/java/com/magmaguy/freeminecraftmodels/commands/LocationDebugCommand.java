package com.magmaguy.freeminecraftmodels.commands;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandData;
import com.magmaguy.magmacore.command.SenderType;
import com.magmaguy.magmacore.location.LocationQueryRegistry;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Diagnostic command for the Lua {@code em.location.is_protected} /
 * {@code em.location.is_in_dungeon} pipeline. Reports how many providers are
 * registered with this FMM instance's shaded registry and what the predicates
 * return for the player's current location.
 */
public class LocationDebugCommand extends AdvancedCommand {

    public LocationDebugCommand() {
        super(List.of("location"));
        setDescription("Reports registered protection/dungeon providers and tests them against your location.");
        setPermission("freeminecraftmodels.*");
        setUsage("/fmm location");
        setSenderType(SenderType.PLAYER);
    }

    @Override
    public void execute(CommandData commandData) {
        Player player = commandData.getPlayerSender();
        Location loc = player.getLocation();

        int dungeonCount = LocationQueryRegistry.getDungeonLocatorCount();
        int protectionCount = LocationQueryRegistry.getProtectionProviderCount();

        Logger.sendMessage(player, "&6FMM location debug for " + loc.getWorld().getName()
                + " (" + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + ")");
        Logger.sendMessage(player, "&7Registered dungeon locators: &f" + dungeonCount);
        Logger.sendMessage(player, "&7Registered protection providers: &f" + protectionCount);

        boolean inDungeon = LocationQueryRegistry.isInAnyDungeon(loc);
        boolean protectedRegion = LocationQueryRegistry.isInAnyProtectedRegion(loc);
        Logger.sendMessage(player, "&7is_in_dungeon(here) = &f" + inDungeon);
        Logger.sendMessage(player, "&7is_protected(here) = &f" + protectedRegion);

        if (dungeonCount == 0)
            Logger.sendMessage(player, "&cNo dungeon locators registered. If EliteMobs is installed, check that its plugin jar is up to date and loaded.");
        if (protectionCount == 0)
            Logger.sendMessage(player, "&cNo protection providers registered. Install WorldGuard and its WorldEdit dependency, or GriefPrevention.");
    }
}
