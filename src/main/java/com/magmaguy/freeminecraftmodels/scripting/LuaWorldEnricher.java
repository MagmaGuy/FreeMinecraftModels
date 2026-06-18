package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.thirdparty.EliteMobsLootDropper;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.magmacore.scripting.tables.LuaWorldTable;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import com.magmaguy.shaded.luaj.vm2.Varargs;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/**
 * Adds FMM's optional third-party script helpers to {@code context.world}.
 */
public final class LuaWorldEnricher {

    private static boolean registered = false;

    private LuaWorldEnricher() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        LuaWorldTable.registerEnricher(LuaWorldEnricher::enrich);
    }

    private static void enrich(LuaTable table, World world) {
        table.set("drop_elitemobs_procedural_loot", LuaTableSupport.tableMethod(table, args ->
                dropEliteMobsProceduralLoot(world, args)));
        table.set("drop_elitemobs_random_loot", LuaTableSupport.tableMethod(table, args ->
                dropEliteMobsRandomLoot(world, args)));
    }

    private static LuaValue dropEliteMobsProceduralLoot(World defaultWorld, Varargs args) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            return LuaValue.FALSE;
        }

        Player player = resolvePlayer(args.arg(1));
        if (player == null) {
            return LuaValue.FALSE;
        }

        int level = args.checkint(2);
        Location location = resolveLocation(args.arg(3), defaultWorld);
        if (location == null || location.getWorld() == null) {
            location = player.getLocation();
        }

        try {
            return LuaValue.valueOf(EliteMobsLootDropper.dropProceduralLoot(player, level, location));
        } catch (NoClassDefFoundError ignored) {
            return LuaValue.FALSE;
        }
    }

    private static LuaValue dropEliteMobsRandomLoot(World defaultWorld, Varargs args) {
        if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            return LuaValue.FALSE;
        }

        Player player = resolvePlayer(args.arg(1));
        if (player == null) {
            return LuaValue.FALSE;
        }

        int level = args.checkint(2);
        Location location = resolveLocation(args.arg(3), defaultWorld);
        if (location == null || location.getWorld() == null) {
            location = player.getLocation();
        }

        try {
            return LuaValue.valueOf(EliteMobsLootDropper.dropRandomLoot(player, level, location));
        } catch (NoClassDefFoundError ignored) {
            return LuaValue.FALSE;
        }
    }

    private static Player resolvePlayer(LuaValue value) {
        if (value == null || value.isnil()) {
            return null;
        }

        if (value.istable()) {
            LuaValue uuidValue = value.checktable().get("uuid");
            if (uuidValue.isstring()) {
                return getPlayerFromString(uuidValue.tojstring());
            }
        }

        if (value.isstring()) {
            return getPlayerFromString(value.tojstring());
        }

        return null;
    }

    private static Player getPlayerFromString(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }

        try {
            Player player = Bukkit.getPlayer(UUID.fromString(rawValue));
            if (player != null) {
                return player;
            }
        } catch (IllegalArgumentException ignored) {
        }

        return Bukkit.getPlayerExact(rawValue);
    }

    private static Location resolveLocation(LuaValue value, World defaultWorld) {
        if (value == null || value.isnil() || !value.istable()) {
            return null;
        }

        try {
            LuaTable table = value.checktable();
            if (table.get("current_location").istable()) {
                table = table.get("current_location").checktable();
            }
            return LuaTableSupport.tableToLocation(table, defaultWorld);
        } catch (Exception ignored) {
            return null;
        }
    }
}
