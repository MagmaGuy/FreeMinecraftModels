package com.magmaguy.freeminecraftmodels.thirdparty;

import com.magmaguy.elitemobs.config.ItemSettingsConfig;
import com.magmaguy.elitemobs.config.ProceduralItemGenerationSettingsConfig;
import com.magmaguy.elitemobs.items.RareDropEffect;
import com.magmaguy.elitemobs.items.LootTables;
import com.magmaguy.elitemobs.items.customenchantments.SoulbindEnchantment;
import com.magmaguy.elitemobs.items.customitems.CustomItem;
import com.magmaguy.elitemobs.items.itemconstructor.ItemConstructor;
import org.bukkit.Location;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;

/**
 * Thin direct-API wrapper around EliteMobs loot generation.
 * Isolated so EliteMobs classes are only loaded when a caller has already
 * verified that the EliteMobs plugin is enabled.
 */
public final class EliteMobsLootDropper {

    private EliteMobsLootDropper() {
    }

    public static boolean dropProceduralLoot(Player player, int level, Location location) {
        if (player == null || !player.isOnline() || location == null || location.getWorld() == null
                || !ProceduralItemGenerationSettingsConfig.isDoProceduralItemDrops()) {
            return false;
        }

        try {
            ItemStack itemStack = ItemConstructor.constructItem(level, null, player, false);
            if (itemStack == null) {
                return false;
            }

            if (ItemSettingsConfig.isPutLootDirectlyIntoPlayerInventory()) {
                HashMap<Integer, ItemStack> leftOvers = player.getInventory().addItem(itemStack);
                leftOvers.values().forEach(leftOver -> player.getWorld().dropItem(player.getLocation(), leftOver));
                return true;
            }

            Item dropped = location.getWorld().dropItem(location, itemStack);
            if (dropped.getItemStack().hasItemMeta() && dropped.getItemStack().getItemMeta().hasDisplayName()) {
                dropped.setCustomName(dropped.getItemStack().getItemMeta().getDisplayName());
                dropped.setCustomNameVisible(true);
            }
            SoulbindEnchantment.addPhysicalDisplay(dropped, player);
            RareDropEffect.runEffect(dropped);
            return dropped.isValid();
        } catch (Exception exception) {
            return false;
        }
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

    public static boolean dropCustomLoot(Player player, String filename, int level, Location location) {
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
