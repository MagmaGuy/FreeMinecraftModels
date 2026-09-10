package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;

import java.util.Map;
import java.util.Objects;

/** Immutable built-in weapon mechanics and base-power coefficients. */
public record MagicWeaponDefinition(
        String itemId,
        MagicWeaponKind kind,
        MagicWeaponTraits traits,
        Map<MagicAttackKind, Double> basePowers,
        Map<MagicAttackKind, Integer> reloadTicks) {

    public MagicWeaponDefinition {
        if (itemId == null || itemId.isBlank()) throw new IllegalArgumentException("itemId must not be blank");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(traits, "traits");
        basePowers = Map.copyOf(basePowers);
        reloadTicks = Map.copyOf(reloadTicks);
        if (basePowers.isEmpty()) throw new IllegalArgumentException("A magic weapon requires an attack");
        for (Map.Entry<MagicAttackKind, Double> entry : basePowers.entrySet()) {
            if (entry.getKey().weaponKind() != kind)
                throw new IllegalArgumentException("Attack family does not match weapon family");
            if (!Double.isFinite(entry.getValue()) || entry.getValue() <= 0D)
                throw new IllegalArgumentException("Base power must be positive and finite");
        }
        if (!reloadTicks.keySet().equals(basePowers.keySet()))
            throw new IllegalArgumentException("Every attack requires one reload duration");
        for (int ticks : reloadTicks.values())
            if (ticks < 1) throw new IllegalArgumentException("Reload ticks must be positive");
    }

    public double basePower(MagicAttackKind attackKind) {
        Double value = basePowers.get(attackKind);
        if (value == null) throw new IllegalArgumentException("Unsupported attack " + attackKind);
        return value;
    }

    public int reloadTicks(MagicAttackKind attackKind) {
        Integer value = reloadTicks.get(attackKind);
        if (value == null) throw new IllegalArgumentException("Unsupported attack " + attackKind);
        return value;
    }

    public MagicWeaponDefinition withTraits(MagicWeaponTraits effectiveTraits) {
        return new MagicWeaponDefinition(itemId, kind,
                Objects.requireNonNull(effectiveTraits, "effectiveTraits"), basePowers, reloadTicks);
    }

    public MagicWeaponDefinition withBasePower(MagicAttackKind attackKind, double basePower) {
        Map<MagicAttackKind, Double> changed = new java.util.EnumMap<>(basePowers);
        if (!changed.containsKey(attackKind))
            throw new IllegalArgumentException("Unsupported attack " + attackKind);
        changed.put(attackKind, basePower);
        return new MagicWeaponDefinition(itemId, kind, traits, changed, reloadTicks);
    }

    public MagicWeaponDefinition withReloadTicks(MagicAttackKind attackKind, int ticks) {
        Map<MagicAttackKind, Integer> changed = new java.util.EnumMap<>(reloadTicks);
        if (!changed.containsKey(attackKind))
            throw new IllegalArgumentException("Unsupported attack " + attackKind);
        changed.put(attackKind, ticks);
        return new MagicWeaponDefinition(itemId, kind, traits, basePowers, changed);
    }
}
