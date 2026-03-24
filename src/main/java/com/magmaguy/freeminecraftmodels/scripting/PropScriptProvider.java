package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptProvider;

import java.nio.file.Path;

public class PropScriptProvider implements ScriptProvider {
    private final Path scriptDirectory;

    public PropScriptProvider(Path scriptDirectory) {
        this.scriptDirectory = scriptDirectory;
    }

    @Override
    public String getNamespace() { return "fmm"; }

    @Override
    public Path getScriptDirectory() { return scriptDirectory; }

    @Override
    public ScriptHook resolveHook(String key) {
        return switch (key) {
            // Prop hooks
            case "on_spawn" -> ScriptHook.ON_SPAWN;
            case "on_game_tick" -> ScriptHook.ON_TICK;
            case "on_zone_enter" -> ScriptHook.ON_ZONE_ENTER;
            case "on_zone_leave" -> ScriptHook.ON_ZONE_LEAVE;
            case "on_destroy" -> ScriptableProp.ON_DESTROY;
            // Shared hooks (used by both props and items)
            case "on_left_click" -> ScriptableProp.ON_LEFT_CLICK;
            case "on_right_click" -> ScriptableProp.ON_RIGHT_CLICK;
            case "on_projectile_hit" -> ScriptableProp.ON_PROJECTILE_HIT;
            // Item hooks
            case "on_equip" -> ScriptableItem.ON_EQUIP;
            case "on_unequip" -> ScriptableItem.ON_UNEQUIP;
            case "on_attack_entity" -> ScriptableItem.ON_ATTACK_ENTITY;
            case "on_kill_entity" -> ScriptableItem.ON_KILL_ENTITY;
            case "on_take_damage" -> ScriptableItem.ON_TAKE_DAMAGE;
            case "on_shield_block" -> ScriptableItem.ON_SHIELD_BLOCK;
            case "on_shoot_bow" -> ScriptableItem.ON_SHOOT_BOW;
            case "on_projectile_launch" -> ScriptableItem.ON_PROJECTILE_LAUNCH;
            case "on_shift_right_click" -> ScriptableItem.ON_SHIFT_RIGHT_CLICK;
            case "on_shift_left_click" -> ScriptableItem.ON_SHIFT_LEFT_CLICK;
            case "on_interact_entity" -> ScriptableItem.ON_INTERACT_ENTITY;
            case "on_swap_hands" -> ScriptableItem.ON_SWAP_HANDS;
            case "on_drop" -> ScriptableItem.ON_DROP;
            case "on_break_block" -> ScriptableItem.ON_BREAK_BLOCK;
            case "on_consume" -> ScriptableItem.ON_CONSUME;
            case "on_item_damage" -> ScriptableItem.ON_ITEM_DAMAGE;
            case "on_fish" -> ScriptableItem.ON_FISH;
            case "on_death" -> ScriptableItem.ON_DEATH;
            default -> null;
        };
    }
}
