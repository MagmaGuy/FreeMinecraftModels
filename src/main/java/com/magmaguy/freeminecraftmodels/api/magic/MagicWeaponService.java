package com.magmaguy.freeminecraftmodels.api.magic;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Runtime service behind the stable magic-weapon API facade. */
public interface MagicWeaponService {
    boolean isOperational();

    boolean isBuiltInWeapon(String itemId);

    boolean applyBuiltInWeaponData(ItemStack itemStack, String itemId);

    boolean registerResolver(Plugin owner, MagicAttackResolver resolver);

    void unregisterResolver(Plugin owner);
}
