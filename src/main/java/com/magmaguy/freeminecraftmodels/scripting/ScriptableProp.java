package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.customentity.PropEntity;
import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.scripting.ScriptableEntity;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapts a {@link PropEntity} for Lua scripting via the Magmacore scripting engine.
 */
public class ScriptableProp extends ScriptableEntity {

    // FMM-specific hooks
    public static final ScriptHook ON_DESTROY = new ScriptHook("on_destroy");
    public static final ScriptHook ON_LEFT_CLICK = new ScriptHook("on_left_click");
    public static final ScriptHook ON_RIGHT_CLICK = new ScriptHook("on_right_click");
    public static final ScriptHook ON_PROJECTILE_HIT = new ScriptHook("on_projectile_hit");

    private static final Set<ScriptHook> SUPPORTED_HOOKS = Set.of(
            ScriptHook.ON_SPAWN,
            ScriptHook.ON_TICK,
            ScriptHook.ON_ZONE_ENTER,
            ScriptHook.ON_ZONE_LEAVE,
            ON_DESTROY,
            ON_LEFT_CLICK,
            ON_RIGHT_CLICK,
            ON_PROJECTILE_HIT
    );

    private final PropEntity propEntity;

    public ScriptableProp(PropEntity propEntity) {
        this.propEntity = propEntity;
    }

    public PropEntity getPropEntity() {
        return propEntity;
    }

    @Override
    public LuaTable buildContextTable(ScriptInstance instance) {
        return LuaPropTable.build(propEntity);
    }

    @Override
    public String getContextKey() {
        return "prop";
    }

    @Override
    public Set<ScriptHook> getSupportedHooks() {
        return SUPPORTED_HOOKS;
    }

    @Override
    public Entity getBukkitEntity() {
        return propEntity.getUnderlyingEntity();
    }

    @Override
    public Location getLocation() {
        return propEntity.getLocation();
    }

    @Override
    public LuaValue resolveExtraContext(String key, ScriptInstance instance) {
        return LuaValue.NIL;
    }

    // ── Global cooldown (shared per PropEntity) ────────────────────────

    private static final Map<PropEntity, Map<String, Long>> propGlobalCooldowns = new ConcurrentHashMap<>();

    @Override
    public Map<String, Long> getGlobalCooldownStore() {
        return propGlobalCooldowns.computeIfAbsent(propEntity, k -> new HashMap<>());
    }

    public static void clearGlobalCooldowns(PropEntity prop) {
        propGlobalCooldowns.remove(prop);
    }
}
