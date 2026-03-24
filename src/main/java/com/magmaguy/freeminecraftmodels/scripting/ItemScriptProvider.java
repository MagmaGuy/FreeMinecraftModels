package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.magmacore.scripting.ScriptHook;
import com.magmaguy.magmacore.scripting.ScriptProvider;

import java.nio.file.Path;

/**
 * {@link ScriptProvider} for item scripts in FreeMinecraftModels.
 * Resolves all 22 item-specific hooks plus ON_TICK.
 */
public class ItemScriptProvider implements ScriptProvider {

    private final Path scriptDirectory;

    public ItemScriptProvider(Path scriptDirectory) {
        this.scriptDirectory = scriptDirectory;
    }

    @Override
    public String getNamespace() {
        return "fmm_items";
    }

    @Override
    public Path getScriptDirectory() {
        return scriptDirectory;
    }

    @Override
    public ScriptHook resolveHook(String key) {
        return switch (key) {
            case "on_equip" -> ScriptableItem.ON_EQUIP;
            case "on_unequip" -> ScriptableItem.ON_UNEQUIP;
            case "on_attack_entity" -> ScriptableItem.ON_ATTACK_ENTITY;
            case "on_kill_entity" -> ScriptableItem.ON_KILL_ENTITY;
            case "on_take_damage" -> ScriptableItem.ON_TAKE_DAMAGE;
            case "on_shield_block" -> ScriptableItem.ON_SHIELD_BLOCK;
            case "on_shoot_bow" -> ScriptableItem.ON_SHOOT_BOW;
            case "on_projectile_hit" -> ScriptableItem.ON_PROJECTILE_HIT;
            case "on_projectile_launch" -> ScriptableItem.ON_PROJECTILE_LAUNCH;
            case "on_right_click" -> ScriptableItem.ON_RIGHT_CLICK;
            case "on_left_click" -> ScriptableItem.ON_LEFT_CLICK;
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
            case "on_game_tick" -> ScriptHook.ON_TICK;
            default -> null;
        };
    }
}
