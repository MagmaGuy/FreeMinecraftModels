package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.scripting.tables.LuaEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

/**
 * Builds the {@code context.prop} Lua table exposed to scripts bound to a {@link PropEntity}.
 * Uses {@link LuaEntityTable#build(Entity)} as the base when the prop has a backing armor stand.
 */
public final class LuaPropTable {

    private LuaPropTable() {}

    public static LuaTable build(PropEntity prop) {
        // Start from the entity table if we have a backing armor stand
        Entity underlying = prop.getUnderlyingEntity();
        LuaTable table = (underlying != null) ? LuaEntityTable.build(underlying) : new LuaTable();

        // model_id — the blueprint model name
        if (prop.getSkeletonBlueprint() != null) {
            table.set("model_id", prop.getSkeletonBlueprint().getModelName());
        }

        // current_location — always up-to-date
        Location loc = prop.getLocation();
        if (loc != null) {
            table.set("current_location", LuaTableSupport.locationToTable(loc));
        }

        // play_animation(name) — plays the named animation blended and looped
        table.set("play_animation", LuaTableSupport.tableMethod(table, args -> {
            String name = args.checkjstring(1);
            boolean blend = args.optboolean(2, true);
            boolean loop = args.optboolean(3, true);
            boolean success = prop.playAnimation(name, blend, loop);
            return LuaValue.valueOf(success);
        }));

        // stop_animation(name) — stops all current animations
        table.set("stop_animation", LuaTableSupport.tableMethod(table, args -> {
            prop.stopCurrentAnimations();
            return LuaValue.NIL;
        }));

        // pickup() — removes the prop and drops a placement item at its location
        table.set("pickup", LuaTableSupport.tableMethod(table, args -> {
            Location propLoc = prop.getLocation();
            String modelId = prop.getEntityID();
            // Remove the prop entity on the main thread
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                prop.permanentlyRemove();
                // Drop a Paper item with the model_id PDC key matching ItemifyCommand/ModelItemListener
                if (propLoc != null && propLoc.getWorld() != null) {
                    ItemStack item = new ItemStack(Material.PAPER);
                    ItemMeta meta = item.getItemMeta();
                    if (meta != null) {
                        meta.setDisplayName("\u00a76Model Placer: " + modelId);
                        NamespacedKey key = new NamespacedKey(MetadataHandler.PLUGIN, "model_id");
                        meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, modelId);
                        item.setItemMeta(meta);
                    }
                    propLoc.getWorld().dropItemNaturally(propLoc, item);
                }
            });
            return LuaValue.NIL;
        }));

        return table;
    }
}
