package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

/** Immutable launch snapshot retained until the FMM-owned flight resolves. */
record MagicCast(
        UUID attackId,
        Player owner,
        ItemStack weapon,
        MagicWeaponDefinition definition,
        MagicAttackKind attackKind,
        com.magmaguy.freeminecraftmodels.api.magic.MagicAttackResolver resolver)
        implements com.magmaguy.magmacore.projectiles.MagicProjectileEngine.Source {
    MagicCast {
        Objects.requireNonNull(attackId, "attackId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(attackKind, "attackKind");
        weapon = weapon.clone();
    }

    @Override
    public ItemStack weapon() {
        return weapon.clone();
    }

    @Override public MagicWeaponTraits traits() { return definition.traits(); }
}
