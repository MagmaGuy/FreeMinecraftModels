package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.customentity.DynamicEntity;
import com.magmaguy.freeminecraftmodels.customentity.ModeledEntity;
import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.freeminecraftmodels.thirdparty.EliteMobsEntityEnricher;
import com.magmaguy.magmacore.scripting.tables.LuaEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;
import org.bukkit.Bukkit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;

import java.util.Map;

/**
 * Installed on plugin enable to contribute FMM-specific fields (is_modeled, is_prop,
 * and the nested {@code model} sub-table) to every Lua entity table built by the
 * shaded Magmacore scripting layer. Also forwards to {@link EliteMobsEntityEnricher}
 * when EliteMobs is present, so scripts running inside FMM see elite/boss flags
 * without Magmacore having to reflectively bridge the two plugins.
 */
public final class LuaEntityEnricher {

    private static boolean registered = false;

    private LuaEntityEnricher() {
    }

    public static synchronized void register() {
        if (registered) return;
        registered = true;
        LuaEntityTable.registerEnricher(LuaEntityEnricher::enrich);
    }

    private static void enrich(LuaTable table, Entity entity) {
        addFmmFields(table, entity);
        if (Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) {
            try {
                EliteMobsEntityEnricher.enrich(table, entity);
            } catch (NoClassDefFoundError ignored) {
                // EliteMobs not on classpath despite plugin being enabled — skip enrichment.
            }
        }
    }

    private static void addFmmFields(LuaTable table, Entity entity) {
        Map<Entity, ModeledEntity> loaded = ModeledEntity.getLoadedModeledEntitiesWithUnderlyingEntities();
        ModeledEntity modeledEntity = loaded != null ? loaded.get(entity) : null;
        boolean isModeled = modeledEntity != null;
        table.set("is_modeled", LuaValue.valueOf(isModeled));

        boolean isProp = entity instanceof ArmorStand armorStand && PropEntity.isPropEntity(armorStand);
        table.set("is_prop", LuaValue.valueOf(isProp));

        if (!isModeled) return;

        LuaTable modelTable = new LuaTable();
        String modelId = modeledEntity.getEntityID();
        modelTable.set("model_id", modelId != null ? LuaValue.valueOf(modelId) : LuaValue.NIL);

        final ModeledEntity capturedModel = modeledEntity;
        modelTable.set("play_animation", LuaTableSupport.tableMethod(modelTable, args -> {
            String animName = args.checkjstring(1);
            boolean blend = args.optboolean(2, false);
            boolean loop = args.optboolean(3, false);
            return LuaValue.valueOf(capturedModel.playAnimation(animName, blend, loop));
        }));
        modelTable.set("stop_animations", LuaTableSupport.tableMethod(modelTable, args -> {
            capturedModel.stopCurrentAnimations();
            return LuaValue.NIL;
        }));
        modelTable.set("remove", LuaTableSupport.tableMethod(modelTable, args -> {
            capturedModel.remove();
            return LuaValue.NIL;
        }));
        modelTable.set("is_dynamic", LuaValue.valueOf(DynamicEntity.isDynamicEntity(entity)));

        table.set("model", modelTable);
    }
}
