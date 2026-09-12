package com.magmaguy.freeminecraftmodels.magic;

import com.magmaguy.magmacore.enchantments.EnchantmentCatalog;
import com.magmaguy.magmacore.enchantments.EnchantmentDefinition;
import com.magmaguy.magmacore.enchantments.EnchantmentDefinitions;
import com.magmaguy.magmacore.scripting.ScriptHook;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
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
    private static final Set<ScriptHook> ALL_HOOKS = java.util.stream.Stream.concat(HOOKS.stream(),
            com.magmaguy.magmacore.enchantments.EnchantmentInputs.HOOKS.stream()).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final EnchantmentDefinitions.HostedCatalog hosted;
    private final com.magmaguy.magmacore.enchantments.EnchantmentAnvil.Registration anvil;

    public MagicEnchantmentCatalog(JavaPlugin plugin, EnchantmentCatalog candidate,
                                   java.util.function.Predicate<Map<String, Object>> damage) {
        hosted = EnchantmentDefinitions.publishActions(plugin, candidate, Set.of(), HOOKS,
                com.magmaguy.magmacore.enchantments.EnchantmentInputs.HOOKS,
                (operation, request) -> operation == com.magmaguy.magmacore.enchantments.EnchantmentProviders.Operation.EVALUATE
                        && "attributed_damage".equals(request.get("kind"))
                        ? Map.of("applied", damage.test(request)) : Map.of("supported", false));
        anvil = com.magmaguy.magmacore.enchantments.EnchantmentAnvil.register(
                plugin, MagicEnchantmentCatalog::itemProfile, MagicEnchantmentCatalog::anvilRejection);
    }

    private static com.magmaguy.magmacore.enchantments.EnchantmentItemProfile itemProfile(org.bukkit.inventory.ItemStack item) {
        if (!item.getItemMeta().getPersistentDataContainer().has(
                com.magmaguy.freeminecraftmodels.scripting.ItemScriptManager.ITEM_ID_KEY,
                org.bukkit.persistence.PersistentDataType.STRING)) return null;
        try {
            return com.magmaguy.freeminecraftmodels.utils.ModelItemFactory.enchantmentProfile(item);
        } catch (IllegalArgumentException unavailable) {
            return null;
        }
    }

    private static String anvilRejection(org.bukkit.inventory.ItemStack item) {
        try {
            com.magmaguy.freeminecraftmodels.utils.ModelItemFactory.enchantmentProfile(item);
            return null;
        } catch (IllegalArgumentException unavailable) {
            return unavailable.getMessage();
        }
    }

    /** Called during asynchronous preflight, before any active content is torn down. */
    public static EnchantmentCatalog prepare(JavaPlugin plugin) throws IOException {
        Path directory = plugin.getDataFolder().toPath().resolve("enchantments");
        EnchantmentCatalog.initializeDefaults(plugin, directory, java.util.List.of("multicast", "blast_radius", "ignition", "inertial_persuader",
                    "velocity_enhancer_mk1", "aquatic_relocator", "super_hunters_bow", "cave_compendium", "sediment_surveyor", "brrrpack", "aqua_prodder", "velocity_enhancer_mk2", "arboreal_terminator",
                    "chicken_staff", "entropy_scythe", "formula_7"));
        return EnchantmentCatalog.load("freeminecraftmodels", directory, ALL_HOOKS);
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
