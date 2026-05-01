package com.magmaguy.freeminecraftmodels.thirdparty;

import com.magmaguy.elitemobs.api.internal.RemovalReason;
import com.magmaguy.elitemobs.entitytracker.EntityTracker;
import com.magmaguy.elitemobs.mobconstructor.EliteEntity;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import org.bukkit.entity.Entity;

/**
 * Adds EliteMobs-specific fields (is_elite, is_custom_boss, is_significant_boss,
 * and the nested {@code elite} sub-table) to Lua entity tables built by FMM's
 * shaded Magmacore. Only invoked from {@code LuaEntityEnricher} when EliteMobs
 * is enabled, so the direct {@code com.magmaguy.elitemobs.*} imports below only
 * get loaded when the plugin is actually present.
 */
public final class EliteMobsEntityEnricher {

    private EliteMobsEntityEnricher() {
    }

    public static void enrich(LuaTable table, Entity entity) {
        boolean isElite = EntityTracker.isEliteMob(entity);
        table.set("is_elite", LuaValue.valueOf(isElite));

        if (!isElite) {
            table.set("is_custom_boss", LuaValue.FALSE);
            table.set("is_significant_boss", LuaValue.FALSE);
            return;
        }

        EliteEntity eliteEntity = EntityTracker.getEliteMobEntity(entity);
        if (eliteEntity == null) {
            table.set("is_custom_boss", LuaValue.FALSE);
            table.set("is_significant_boss", LuaValue.FALSE);
            return;
        }

        LuaTable eliteTable = new LuaTable();
        eliteTable.set("level", LuaValue.valueOf(eliteEntity.getLevel()));
        String name = eliteEntity.getName();
        eliteTable.set("name", name != null ? LuaValue.valueOf(name) : LuaValue.NIL);
        eliteTable.set("health", LuaValue.valueOf(eliteEntity.getHealth()));
        eliteTable.set("max_health", LuaValue.valueOf(eliteEntity.getMaxHealth()));

        boolean isCustomBoss = eliteEntity.isCustomBossEntity();
        eliteTable.set("is_custom_boss", LuaValue.valueOf(isCustomBoss));
        table.set("is_custom_boss", LuaValue.valueOf(isCustomBoss));

        double healthMultiplier = eliteEntity.getHealthMultiplier();
        double damageMultiplier = eliteEntity.getDamageMultiplier();
        eliteTable.set("health_multiplier", LuaValue.valueOf(healthMultiplier));
        eliteTable.set("damage_multiplier", LuaValue.valueOf(damageMultiplier));
        table.set("is_significant_boss", LuaValue.valueOf(isCustomBoss && healthMultiplier > 1));

        final EliteEntity capturedElite = eliteEntity;
        eliteTable.set("remove", LuaTableSupport.tableMethod(eliteTable, args -> {
            capturedElite.remove(RemovalReason.OTHER);
            return LuaValue.NIL;
        }));

        table.set("elite", eliteTable);
    }
}
