package com.magmaguy.freeminecraftmodels.api.magic;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

/** Immutable context captured by FMM before a projectile leaves the weapon. */
public record MagicAttackRequest(
        UUID attackId,
        MagicAttackKind attackKind,
        Player attacker,
        LivingEntity target,
        ItemStack weapon,
        MagicAttackBalance balance) {

    public MagicAttackRequest {
        Objects.requireNonNull(attackId, "attackId");
        Objects.requireNonNull(attackKind, "attackKind");
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(balance, "balance");
        weapon = weapon.clone();
    }

    @Override
    public ItemStack weapon() {
        return weapon.clone();
    }
}
