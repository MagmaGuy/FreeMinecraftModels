package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptInstance;
import com.magmaguy.magmacore.scripting.ScriptableEntity;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import com.magmaguy.shaded.luaj.vm2.LuaTable;
import com.magmaguy.shaded.luaj.vm2.LuaValue;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adapts a per-player item for Lua scripting via the MagmaCore scripting engine.
 * Each instance represents a specific item type held/equipped by a specific player.
 */
public class ScriptableItem extends ScriptableEntity {

    // ── Item-specific script hooks ──────────────────────────────────────
    public static final ScriptHook ON_EQUIP = new ScriptHook("on_equip");
    public static final ScriptHook ON_UNEQUIP = new ScriptHook("on_unequip");
    public static final ScriptHook ON_ATTACK_ENTITY = new ScriptHook("on_attack_entity");
    public static final ScriptHook ON_KILL_ENTITY = new ScriptHook("on_kill_entity");
    public static final ScriptHook ON_TAKE_DAMAGE = new ScriptHook("on_take_damage");
    public static final ScriptHook ON_SHIELD_BLOCK = new ScriptHook("on_shield_block");
    public static final ScriptHook ON_SHOOT_BOW = new ScriptHook("on_shoot_bow");
    public static final ScriptHook ON_PROJECTILE_HIT = new ScriptHook("on_projectile_hit");
    public static final ScriptHook ON_PROJECTILE_LAUNCH = new ScriptHook("on_projectile_launch");
    public static final ScriptHook ON_RIGHT_CLICK = new ScriptHook("on_right_click");
    public static final ScriptHook ON_LEFT_CLICK = new ScriptHook("on_left_click");
    public static final ScriptHook ON_SHIFT_RIGHT_CLICK = new ScriptHook("on_shift_right_click");
    public static final ScriptHook ON_SHIFT_LEFT_CLICK = new ScriptHook("on_shift_left_click");
    public static final ScriptHook ON_INTERACT_ENTITY = new ScriptHook("on_interact_entity");
    public static final ScriptHook ON_SWAP_HANDS = new ScriptHook("on_swap_hands");
    public static final ScriptHook ON_DROP = new ScriptHook("on_drop");
    public static final ScriptHook ON_BREAK_BLOCK = new ScriptHook("on_break_block");
    public static final ScriptHook ON_CONSUME = new ScriptHook("on_consume");
    public static final ScriptHook ON_ITEM_DAMAGE = new ScriptHook("on_item_damage");
    public static final ScriptHook ON_FISH = new ScriptHook("on_fish");
    public static final ScriptHook ON_DEATH = new ScriptHook("on_death");

    /** All hooks supported by item scripts (22 custom + ON_TICK). */
    private static final Set<ScriptHook> SUPPORTED_HOOKS = Set.of(
            ON_EQUIP, ON_UNEQUIP, ON_ATTACK_ENTITY, ON_KILL_ENTITY, ON_TAKE_DAMAGE,
            ON_SHIELD_BLOCK, ON_SHOOT_BOW, ON_PROJECTILE_HIT, ON_PROJECTILE_LAUNCH,
            ON_RIGHT_CLICK, ON_LEFT_CLICK, ON_SHIFT_RIGHT_CLICK, ON_SHIFT_LEFT_CLICK,
            ON_INTERACT_ENTITY, ON_SWAP_HANDS, ON_DROP, ON_BREAK_BLOCK, ON_CONSUME,
            ON_ITEM_DAMAGE, ON_FISH, ON_DEATH,
            ScriptHook.ON_TICK
    );

    private final Player player;
    private final String itemId;

    public ScriptableItem(Player player, String itemId) {
        this.player = player;
        this.itemId = itemId;
    }

    public Player getPlayer() {
        return player;
    }

    public String getItemId() {
        return itemId;
    }

    @Override
    public LuaTable buildContextTable(ScriptInstance instance) {
        return LuaItemTable.build(player, itemId);
    }

    @Override
    public String getContextKey() {
        return "item";
    }

    @Override
    public Set<ScriptHook> getSupportedHooks() {
        return SUPPORTED_HOOKS;
    }

    @Override
    public Entity getBukkitEntity() {
        return null; // items are not entities
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
    }

    @Override
    public LuaValue resolveExtraContext(String key, ScriptInstance instance) {
        if ("player".equals(key)) {
            return LuaLivingEntityTable.build(player);
        }
        return LuaValue.NIL;
    }

    // ── Global cooldown (shared per player across all items) ───────────

    private static final Map<UUID, Map<String, Long>> playerGlobalCooldowns = new ConcurrentHashMap<>();

    @Override
    public Map<String, Long> getGlobalCooldownStore() {
        return playerGlobalCooldowns.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
    }

    public static void clearGlobalCooldowns(UUID playerUUID) {
        playerGlobalCooldowns.remove(playerUUID);
    }
}
