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
        MagicAttackBalance balance,
        java.util.Map<String, ItemStack> equipment,
        java.util.Map<String, Double> resolverFacts) {

    public MagicAttackRequest {
        Objects.requireNonNull(attackId, "attackId");
        Objects.requireNonNull(attackKind, "attackKind");
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(weapon, "weapon");
        Objects.requireNonNull(balance, "balance");
        weapon = weapon.clone();
        equipment = copyEquipment(equipment);
        resolverFacts = copyResolverFacts(resolverFacts);
    }

    @Override
    public ItemStack weapon() {
        return weapon.clone();
    }

    @Override public java.util.Map<String, ItemStack> equipment() { return copyEquipment(equipment); }

    public static java.util.Map<String, Double> copyResolverFacts(java.util.Map<String, Double> facts) {
        var copy = java.util.Map.copyOf(Objects.requireNonNull(facts, "resolverFacts"));
        copy.forEach((name, value) -> {
            if (name.isBlank() || !Double.isFinite(value)) throw new IllegalArgumentException("Invalid resolver fact: " + name);
        });
        return copy;
    }

    /** Bukkit values and JDK collections can cross plugin loaders without a shaded DTO. */
    public static java.util.Map<String, ItemStack> copyEquipment(java.util.Map<String, ItemStack> equipment) {
        var copy = new java.util.LinkedHashMap<String, ItemStack>();
        Objects.requireNonNull(equipment, "equipment").forEach((slot, item) -> {
            if (!java.util.Set.of("MAINHAND", "OFFHAND", "HEAD", "CHEST", "LEGS", "FEET").contains(slot))
                throw new IllegalArgumentException("Unknown equipment slot: " + slot);
            copy.put(slot, Objects.requireNonNull(item, "equipment item").clone());
        });
        return java.util.Map.copyOf(copy);
    }
}
