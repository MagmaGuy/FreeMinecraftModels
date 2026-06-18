package com.magmaguy.freeminecraftmodels.thirdparty;

import com.magmaguy.elitemobs.items.LootTables;
import com.magmaguy.elitemobs.items.customitems.CustomItem;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

/**
 * Thin direct-API wrapper around EliteMobs loot generation.
 * Isolated so EliteMobs classes are only loaded when a caller has already
 * verified that the EliteMobs plugin is enabled.
 */
public final class EliteMobsLootDropper {

    private EliteMobsLootDropper() {
    }

    public static boolean dropRandomLoot(Player player, int level, Location location) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null) {
            return false;
        }

        try {
            return LootTables.generateLoot(level, location, player) != null;
        } catch (Exception exception) {
            return false;
        }
    }

    public static boolean dropCustomItem(Player player, String filename, int level, Location location) {
        if (player == null || !player.isOnline() || filename == null || filename.isBlank()
                || location == null || location.getWorld() == null) {
            return false;
        }

        CustomItem customItem = CustomItem.getCustomItem(filename);
        if (customItem == null) {
            return false;
        }

        try {
            Item dropped = customItem.dropPlayerLootExact(player, level, location, null);
            return dropped != null && dropped.isValid();
        } catch (Exception exception) {
            return false;
        }
    }
}
