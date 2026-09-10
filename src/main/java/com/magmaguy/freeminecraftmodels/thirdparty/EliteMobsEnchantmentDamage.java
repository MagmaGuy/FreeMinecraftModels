package com.magmaguy.freeminecraftmodels.thirdparty;

import com.magmaguy.magmacore.enchantments.EnchantmentActions;

/** Loaded only while the optional EM combat owner is enabled. */
public final class EliteMobsEnchantmentDamage {
    private EliteMobsEnchantmentDamage() { }

    public static void apply(EnchantmentActions.DamageInput damage, Runnable application) {
        com.magmaguy.elitemobs.combatsystem.EnchantmentDamage.apply(damage.attackId(), damage.equipment(), application);
    }
}
