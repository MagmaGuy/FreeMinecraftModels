package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import org.bukkit.NamespacedKey;

import java.util.Map;
import java.util.Objects;

/** Immutable weapon mechanics, base-power coefficients and the attack behaviors its item file names. */
public record MagicWeaponDefinition(
        String itemId,
        MagicWeaponKind kind,
        MagicWeaponTraits traits,
        Map<MagicAttackKind, Double> basePowers,
        Map<MagicAttackKind, Integer> reloadTicks,
        Map<MagicAttackKind, NamespacedKey> behaviors) {

    /** A weapon whose item file names no attack behavior. */
    public MagicWeaponDefinition(
            String itemId,
            MagicWeaponKind kind,
            MagicWeaponTraits traits,
            Map<MagicAttackKind, Double> basePowers,
            Map<MagicAttackKind, Integer> reloadTicks) {
        this(itemId, kind, traits, basePowers, reloadTicks, Map.of());
    }

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
        behaviors = Map.copyOf(behaviors);
        if (!basePowers.keySet().containsAll(behaviors.keySet()))
            throw new IllegalArgumentException("An attack behavior must stand in for one of the weapon's attacks");
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
                Objects.requireNonNull(effectiveTraits, "effectiveTraits"), basePowers, reloadTicks, behaviors);
    }
}
