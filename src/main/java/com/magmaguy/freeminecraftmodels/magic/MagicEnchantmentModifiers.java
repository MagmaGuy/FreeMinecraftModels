package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.magmacore.enchantments.*;
import com.magmaguy.magmacore.scripting.ScriptHook;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Captures shared Lua contributions once, after input/cooldown admission and before launching. */
final class MagicEnchantmentModifiers {
    private static final EnchantmentItems ITEMS = new EnchantmentItems(EnchantmentDefinitions::resolve, EnchantmentItemProfile::vanilla);
    private MagicEnchantmentModifiers() { }

    static MagicWeaponDefinition apply(Player player, ItemStack source, MagicWeaponDefinition base, MagicAttackKind attack) {
        var levels = ITEMS.inspect(source);
        if (levels.isEmpty()) return base;
        var requests = new ArrayList<EnchantmentQueries.Query>();
        Set<ScriptHook> hooks = switch (attack) {
            case WAND_MISSILE -> Set.of(MagicEnchantmentCatalog.MISSILE_COUNT, MagicEnchantmentCatalog.MISSILE_DAMAGE_FACTOR);
            case STAFF_FIREBALL -> Set.of(MagicEnchantmentCatalog.BLAST_RADIUS_FACTOR, MagicEnchantmentCatalog.IGNITION_TICKS);
            default -> Set.of();
        };
        for (var entry : new TreeMap<>(levels).entrySet()) {
            if (entry.getKey().startsWith("minecraft:")) continue;
            var resolved = EnchantmentDefinitions.resolve(entry.getKey());
            if (resolved == null || !resolved.available())
                throw new IllegalArgumentException("Unavailable enchantment " + entry.getKey());
            if (entry.getValue() > resolved.definition().maxLevel())
                throw new IllegalArgumentException("Enchantment exceeds its authored level limit: " + entry.getKey());
            if (!resolved.definition().attackKinds().isEmpty() && !resolved.definition().attackKinds().contains(attack.name())) continue;
            if (!resolved.provider().capabilities().contains(EnchantmentQueries.CAPABILITY)) continue;
            for (ScriptHook hook : hooks.stream().sorted(Comparator.comparing(ScriptHook::getKey)).toList())
                requests.add(new EnchantmentQueries.Query(resolved.provider(), entry.getKey(), hook,
                        entry.getValue(), Map.of("attack_kind", attack.name()), player.getUniqueId(), null));
        }
        if (requests.isEmpty()) return base;
        var result = EnchantmentQueries.evaluate(requests, new EnchantmentQueries.Limits(128, 50_000_000L, 250_000L));
        if (result.status() != EnchantmentQueries.Status.OK)
            throw new IllegalArgumentException("Enchantment cast query failed: " + result.status());
        Integer missileCount = null;
        double damageFactor = 1D, radiusFactor = 1D;
        int fireTicks = base.traits().ignitionTicks();
        for (var contribution : result.contributions()) {
            if (contribution.value() == null) continue;
            double value = contribution.value();
            if (contribution.hook().equals(MagicEnchantmentCatalog.MISSILE_COUNT.getKey())) {
                if (missileCount != null) throw new IllegalArgumentException("Multiple enchantments override missile count");
                missileCount = integer(value, 1, "missile count");
            } else if (contribution.hook().equals(MagicEnchantmentCatalog.MISSILE_DAMAGE_FACTOR.getKey())) {
                damageFactor *= positive(value, "missile damage factor");
            } else if (contribution.hook().equals(MagicEnchantmentCatalog.BLAST_RADIUS_FACTOR.getKey())) {
                radiusFactor *= positive(value, "blast radius factor");
            } else if (contribution.hook().equals(MagicEnchantmentCatalog.IGNITION_TICKS.getKey())) {
                fireTicks = Math.max(fireTicks, integer(value, 0, "ignition ticks"));
            }
        }
        var traits = base.traits();
        var effective = new MagicWeaponTraits(missileCount == null ? traits.missileCount() : missileCount,
                traits.spreadDegrees(), traits.impactRadius() * radiusFactor, fireTicks, traits.projectileSpeed(),
                traits.range(), traits.travelTicks(), traits.aimAssistDegrees());
        var powers = new HashMap<>(base.basePowers());
        if (attack == MagicAttackKind.WAND_MISSILE) powers.put(attack, positive(base.basePower(attack) * damageFactor, "missile power"));
        return new MagicWeaponDefinition(base.itemId(), base.kind(), effective, powers, base.reloadTicks());
    }

    private static int integer(double value, int minimum, String name) {
        if (!Double.isFinite(value) || value < minimum || value > Integer.MAX_VALUE || value != Math.rint(value))
            throw new IllegalArgumentException("Invalid " + name);
        return (int) value;
    }
    private static double positive(double value, String name) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("Invalid " + name);
        return value;
    }
}
