package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.freeminecraftmodels.api.magic.MagicAttackKind;
import com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind;
import org.bukkit.configuration.ConfigurationSection;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** The weapon section of FMM's existing model-adjacent item definition. */
public final class MagicWeaponConfig {
    private static final Set<String> FIELDS = Set.of("type", "attacks", "projectileSpeed", "range", "travelTicks", "impactRadius", "aimAssistDegrees");
    private MagicWeaponConfig() { }

    public static MagicWeaponDefinition parse(String itemId, ConfigurationSection section) {
        requireKeys(section, FIELDS, "weapon");
        if (!(section.get("type") instanceof String text)) throw new IllegalArgumentException("weapon.type must be WAND or STAFF");
        MagicWeaponKind kind = MagicWeaponKind.valueOf(text);
        MagicWeaponDefinition defaults = BuiltInMagicWeapons.defaults(itemId, kind);
        Map<MagicAttackKind, Double> powers = new EnumMap<>(defaults.basePowers());
        Map<MagicAttackKind, Integer> reloads = new EnumMap<>(defaults.reloadTicks());
        if (section.contains("attacks")) {
            ConfigurationSection attacks = section.getConfigurationSection("attacks");
            if (attacks == null || !attacks.getKeys(false).equals(defaults.basePowers().keySet().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet())))
                throw new IllegalArgumentException("weapon.attacks must contain exactly the attacks for " + kind);
            for (MagicAttackKind attack : defaults.basePowers().keySet()) {
                ConfigurationSection entry = attacks.getConfigurationSection(attack.name());
                requireKeys(entry, Set.of("basePower", "reloadTicks"), "weapon.attacks." + attack);
                powers.put(attack, number(entry, "basePower", null, false));
                reloads.put(attack, integer(entry, "reloadTicks", null));
            }
        }
        MagicWeaponTraits base = defaults.traits();
        double aim = number(section, "aimAssistDegrees", base.aimAssistDegrees(), true);
        if (aim > 180) throw new IllegalArgumentException("weapon.aimAssistDegrees must be at most 180");
        MagicWeaponTraits traits = new MagicWeaponTraits(1, 0, number(section, "impactRadius", base.impactRadius(), true), 0,
                number(section, "projectileSpeed", base.projectileSpeed(), false), number(section, "range", base.range(), false),
                integer(section, "travelTicks", base.travelTicks()), aim);
        return new MagicWeaponDefinition(itemId, kind, traits, powers, reloads);
    }

    private static void requireKeys(ConfigurationSection section, Set<String> allowed, String path) {
        if (section == null || !allowed.containsAll(section.getKeys(false))) throw new IllegalArgumentException("Unknown or malformed fields at " + path);
    }
    private static double number(ConfigurationSection section, String key, Double fallback, boolean zeroAllowed) {
        Object raw = section.get(key);
        if (raw == null && !section.contains(key) && fallback != null) return fallback;
        if (!(raw instanceof Number value) || !Double.isFinite(value.doubleValue())
                || (zeroAllowed ? value.doubleValue() < 0 : value.doubleValue() <= 0))
            throw new IllegalArgumentException("weapon." + key + " must be a " + (zeroAllowed ? "nonnegative" : "positive") + " finite number");
        return value.doubleValue();
    }
    private static int integer(ConfigurationSection section, String key, Integer fallback) {
        Object raw = section.get(key);
        if (raw == null && !section.contains(key) && fallback != null) return fallback;
        if (!(raw instanceof Integer value) || value < 1) throw new IllegalArgumentException("weapon." + key + " must be a positive integer");
        return value;
    }
}
