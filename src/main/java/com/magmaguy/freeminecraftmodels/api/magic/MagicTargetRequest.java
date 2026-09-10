package com.magmaguy.freeminecraftmodels.api.magic;

import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

/** Immutable target candidate offered to an optional magic-weapon integration. */
public record MagicTargetRequest(
        UUID attackId,
        MagicAttackKind attackKind,
        Player attacker,
        LivingEntity target,
        ItemStack weapon) {

    public MagicTargetRequest {
        Objects.requireNonNull(attackId, "attackId");
        Objects.requireNonNull(attackKind, "attackKind");
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(weapon, "weapon");
        weapon = weapon.clone();
    }

    @Override
    public ItemStack weapon() {
        return weapon.clone();
    }
}
