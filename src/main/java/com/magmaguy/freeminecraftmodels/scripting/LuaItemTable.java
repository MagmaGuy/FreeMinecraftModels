package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@code context.item} Lua table exposed to item scripts.
 * Provides id, material, amount, uses, name, and lore accessors.
 */
public final class LuaItemTable {

    private static final NamespacedKey KEY_ITEM_ID =
            new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_id");
    private static final NamespacedKey KEY_ITEM_USES =
            new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_uses");

    private LuaItemTable() {}

    /**
     * Builds the Lua table for an item identified by {@code itemId} equipped by {@code player}.
     */
    public static LuaTable build(Player player, String itemId) {
        LuaTable table = new LuaTable();

        // id — string field
        table.set("id", LuaValue.valueOf(itemId));

        // material() — returns the item type name
        table.set("material", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null) return LuaValue.NIL;
            return LuaValue.valueOf(item.getType().name());
        }));

        // get_amount() — returns stack size
        table.set("get_amount", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null) return LuaValue.valueOf(0);
            return LuaValue.valueOf(item.getAmount());
        }));

        // set_amount(n) — sets stack size on the main thread
        table.set("set_amount", LuaTableSupport.tableMethod(table, args -> {
            int n = args.checkint(1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item != null) {
                    item.setAmount(n);
                }
            });
            return LuaValue.NIL;
        }));

        // consume(n) — decrement amount by n (default 1), remove if <= 0
        table.set("consume", LuaTableSupport.tableMethod(table, args -> {
            int n = args.optint(1, 1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item != null) {
                    int newAmount = item.getAmount() - n;
                    if (newAmount <= 0) {
                        item.setAmount(0);
                    } else {
                        item.setAmount(newAmount);
                    }
                }
            });
            return LuaValue.NIL;
        }));

        // get_uses() — PDC-stored use counter
        table.set("get_uses", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null) return LuaValue.valueOf(0);
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return LuaValue.valueOf(0);
            Integer uses = meta.getPersistentDataContainer().get(KEY_ITEM_USES, PersistentDataType.INTEGER);
            return LuaValue.valueOf(uses != null ? uses : 0);
        }));

        // set_uses(n) — sets PDC use counter on the main thread
        table.set("set_uses", LuaTableSupport.tableMethod(table, args -> {
            int n = args.checkint(1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item == null) return;
                ItemMeta meta = item.getItemMeta();
                if (meta == null) return;
                meta.getPersistentDataContainer().set(KEY_ITEM_USES, PersistentDataType.INTEGER, n);
                item.setItemMeta(meta);
            });
            return LuaValue.NIL;
        }));

        // get_durability() — returns {current, max} or nil if item has no durability bar
        table.set("get_durability", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null || item.getType().getMaxDurability() == 0) return LuaValue.NIL;
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable damageable)) return LuaValue.NIL;
            int max = item.getType().getMaxDurability();
            LuaTable result = new LuaTable();
            result.set("current", max - damageable.getDamage());
            result.set("max", max);
            return result;
        }));

        // use_durability(amount, can_break) — reduces durability by a flat amount
        table.set("use_durability", LuaTableSupport.tableMethod(table, args -> {
            int amount = args.checkint(1);
            boolean canBreak = args.narg() >= 2 && !args.arg(2).isnil() && args.arg(2).checkboolean();
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item == null || item.getType().getMaxDurability() == 0) return;
                ItemMeta meta = item.getItemMeta();
                if (!(meta instanceof Damageable damageable)) return;
                int max = item.getType().getMaxDurability();
                int newDamage = damageable.getDamage() + amount;
                if (newDamage >= max) {
                    if (canBreak) {
                        item.setAmount(0);
                    } else {
                        damageable.setDamage(max - 1);
                        item.setItemMeta(damageable);
                    }
                } else {
                    damageable.setDamage(Math.max(0, newDamage));
                    item.setItemMeta(damageable);
                }
            });
            return LuaValue.NIL;
        }));

        // use_durability_percentage(fraction, can_break) — reduces durability by a percentage of max
        table.set("use_durability_percentage", LuaTableSupport.tableMethod(table, args -> {
            double fraction = args.checkdouble(1);
            boolean canBreak = args.narg() >= 2 && !args.arg(2).isnil() && args.arg(2).checkboolean();
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item == null || item.getType().getMaxDurability() == 0) return;
                ItemMeta meta = item.getItemMeta();
                if (!(meta instanceof Damageable damageable)) return;
                int max = item.getType().getMaxDurability();
                int amount = (int) Math.ceil(max * fraction);
                int newDamage = damageable.getDamage() + amount;
                if (newDamage >= max) {
                    if (canBreak) {
                        item.setAmount(0);
                    } else {
                        damageable.setDamage(max - 1);
                        item.setItemMeta(damageable);
                    }
                } else {
                    damageable.setDamage(Math.max(0, newDamage));
                    item.setItemMeta(damageable);
                }
            });
            return LuaValue.NIL;
        }));

        // get_name() — display name
        table.set("get_name", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null) return LuaValue.NIL;
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasDisplayName()) return LuaValue.NIL;
            return LuaValue.valueOf(meta.getDisplayName());
        }));

        // set_name(s) — sets display name on the main thread
        table.set("set_name", LuaTableSupport.tableMethod(table, args -> {
            String name = args.checkjstring(1);
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item == null) return;
                ItemMeta meta = item.getItemMeta();
                if (meta == null) return;
                meta.setDisplayName(ChatColorConverter.convert(name));
                item.setItemMeta(meta);
            });
            return LuaValue.NIL;
        }));

        // get_lore() — returns lore as a Lua table of strings
        table.set("get_lore", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null) return new LuaTable();
            ItemMeta meta = item.getItemMeta();
            if (meta == null || !meta.hasLore()) return new LuaTable();
            LuaTable loreTable = new LuaTable();
            List<String> lore = meta.getLore();
            if (lore != null) {
                for (int i = 0; i < lore.size(); i++) {
                    loreTable.set(i + 1, LuaValue.valueOf(lore.get(i)));
                }
            }
            return loreTable;
        }));

        // set_lore(table) — sets lore from a Lua table of strings on the main thread
        table.set("set_lore", LuaTableSupport.tableMethod(table, args -> {
            LuaTable loreTable = args.checktable(1);
            List<String> loreLines = new ArrayList<>();
            for (int i = 1; i <= loreTable.length(); i++) {
                loreLines.add(ChatColorConverter.convert(loreTable.get(i).tojstring()));
            }
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item == null) return;
                ItemMeta meta = item.getItemMeta();
                if (meta == null) return;
                meta.setLore(loreLines);
                item.setItemMeta(meta);
            });
            return LuaValue.NIL;
        }));

        return table;
    }

    /**
     * Scans the player's equipment slots (main hand, off hand, armor) for an
     * {@link ItemStack} whose PDC contains {@code fmm_item_id} matching the given id.
     *
     * @return the first matching ItemStack, or null if not found
     */
    static ItemStack findEquippedItem(Player player, String itemId) {
        if (player == null || itemId == null) return null;

        ItemStack[] candidates = {
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand(),
                player.getInventory().getHelmet(),
                player.getInventory().getChestplate(),
                player.getInventory().getLeggings(),
                player.getInventory().getBoots()
        };

        for (ItemStack item : candidates) {
            if (item == null || item.getType().isAir()) continue;
            ItemMeta meta = item.getItemMeta();
            if (meta == null) continue;
            String id = meta.getPersistentDataContainer().get(KEY_ITEM_ID, PersistentDataType.STRING);
            if (itemId.equals(id)) return item;
        }

        return null;
    }
}
