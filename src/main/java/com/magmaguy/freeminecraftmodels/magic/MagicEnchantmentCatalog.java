package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.magmacore.enchantments.EnchantmentCatalog;
import com.magmaguy.magmacore.enchantments.EnchantmentDefinition;
import com.magmaguy.magmacore.enchantments.EnchantmentDefinitions;
import com.magmaguy.magmacore.scripting.ScriptHook;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** FMM owns its authored enchantments; MagmaCore owns their loading and query execution. */
public final class MagicEnchantmentCatalog implements AutoCloseable {
    public static final String MULTICAST = "freeminecraftmodels:multicast";
    public static final ScriptHook MISSILE_COUNT = new ScriptHook("on_missile_count");
    public static final ScriptHook MISSILE_DAMAGE_FACTOR = new ScriptHook("on_missile_damage_factor");
    public static final ScriptHook BLAST_RADIUS_FACTOR = new ScriptHook("on_blast_radius_factor");
    public static final ScriptHook IGNITION_TICKS = new ScriptHook("on_ignition_ticks");
    private static final Set<ScriptHook> HOOKS = Set.of(MISSILE_COUNT, MISSILE_DAMAGE_FACTOR, BLAST_RADIUS_FACTOR, IGNITION_TICKS);
    private final EnchantmentDefinitions.HostedCatalog hosted;
    private final com.magmaguy.magmacore.enchantments.EnchantmentAnvil.Registration anvil;

    public MagicEnchantmentCatalog(JavaPlugin plugin, EnchantmentCatalog candidate) {
        hosted = EnchantmentDefinitions.publishQueries(plugin, candidate, Set.of(), HOOKS,
                (operation, request) -> Map.of("supported", false));
        anvil = com.magmaguy.magmacore.enchantments.EnchantmentAnvil.register(plugin, MagicEnchantmentCatalog::itemProfile, item -> null);
    }

    private static com.magmaguy.magmacore.enchantments.EnchantmentItemProfile itemProfile(org.bukkit.inventory.ItemStack item) {
        String id = item.getItemMeta().getPersistentDataContainer().get(
                com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager.ITEM_ID_KEY,
                org.bukkit.persistence.PersistentDataType.STRING);
        if (id == null) return null;
        var fields = com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager.getItemDefinitions().get(id);
        if (fields == null || !fields.isEnabled() || org.bukkit.Material.matchMaterial(fields.getMaterial()) != item.getType())
            throw new IllegalArgumentException("FMM item definition is unavailable or its material changed");
        var weapon = fields.getWeapon();
        if (weapon == null) return com.magmaguy.magmacore.enchantments.EnchantmentItemProfile.vanilla(item);
        boolean wand = weapon.kind() == com.magmaguy.freeminecraftmodels.api.magic.MagicWeaponKind.WAND;
        return new com.magmaguy.magmacore.enchantments.EnchantmentItemProfile(
                wand ? EnchantmentDefinition.ItemType.WAND : EnchantmentDefinition.ItemType.STAFF,
                Set.of(EnchantmentDefinition.Slot.MAINHAND),
                wand ? Set.of("WAND_MISSILE") : Set.of("STAFF_FIREBALL", "STAFF_MELEE"));
    }

    /** Called during asynchronous preflight, before any active content is torn down. */
    public static EnchantmentCatalog prepare(JavaPlugin plugin) throws IOException {
        Path directory = plugin.getDataFolder().toPath().resolve("enchantments");
        if (Files.notExists(directory)) {
            Files.createDirectories(directory);
            // Install once. Moving or removing an authored definition must not recreate a
            // competing root copy at every reload. Administrators can disable it in YAML.
            for (String name : java.util.List.of("multicast", "blast_radius", "ignition")) {
                plugin.saveResource("enchantments/" + name + ".yml", false);
                plugin.saveResource("enchantments/" + name + ".lua", false);
            }
        }
        EnchantmentCatalog candidate = EnchantmentCatalog.load("freeminecraftmodels", directory, HOOKS);
        EnchantmentDefinition multicast = candidate.definitions().get(MULTICAST);
        if (multicast != null) {
            if (multicast.maxLevel() != 3
                    || !multicast.validSlots().equals(Set.of(EnchantmentDefinition.Slot.MAINHAND))
                    || !multicast.itemTypes().equals(Set.of(EnchantmentDefinition.ItemType.WAND))
                    || !multicast.attackKinds().equals(Set.of("WAND_MISSILE"))
                    || multicast.stacking() != EnchantmentDefinition.Stacking.SOURCE_ITEM
                    || !candidate.script(MULTICAST).orElseThrow().getHooks().equals(Set.of(MISSILE_COUNT, MISSILE_DAMAGE_FACTOR)))
                throw new IOException("Multicast requires levels I-III, main-hand wands, WAND_MISSILE, "
                        + "source_item stacking and both missile query hooks");
        }
        return candidate;
    }

    /** Publishes only a fully prepared candidate on the server thread. */
    public void reload(EnchantmentCatalog candidate) {
        hosted.reload(candidate);
    }

    @Override
    public void close() {
        anvil.close();
        hosted.close();
    }
}
