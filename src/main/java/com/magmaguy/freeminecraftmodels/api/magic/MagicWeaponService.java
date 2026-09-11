package com.magmaguy.freeminecraftmodels.api.magic;

import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Runtime service behind the stable magic-weapon API facade. */
public interface MagicWeaponService {
    boolean isOperational();

    boolean isWeapon(String itemId);

    MagicWeaponKind weaponKind(String itemId);

    boolean applyWeaponData(ItemStack itemStack, String itemId);

    boolean registerResolver(Plugin owner, MagicAttackResolver resolver);

    void unregisterResolver(Plugin owner);
}
