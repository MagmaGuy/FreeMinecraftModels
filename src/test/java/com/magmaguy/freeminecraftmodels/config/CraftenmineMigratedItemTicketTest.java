package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.dataconverter.FileModelConverter;
import com.magmaguy.freeminecraftmodels.MetadataHandler;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Temporary coverage for the Craftenmine Weapons Pack migration reported in
 * ticket 1547862691916423169.  The old pack used item scripts, which FMM must
 * reject; the current pack moves item effects to namespaced enchantments and
 * points EliteMobs items at FMM model IDs.
 */
class CraftenmineMigratedItemTicketTest {
    private static final List<String> ITEM_FILES = List.of(
            "em_craftenmine_basic_item_pack_aqua_prodder_spear.yml",
            "em_craftenmine_basic_item_pack_formula_7_sword.yml",
            "em_craftenmine_basic_item_pack_inertial_persuader_mace.yml",
            "em_craftenmine_basic_item_pack_velocity_enhancer_mk1_bow.yml",
            "em_craftenmine_basic_item_pack_velocity_enhancer_mk2_crossbow.yml");

    @TempDir
    Path temporaryDirectory;

    @Test
    void everyMigratedItemLoadsWithItsCurrentModelAndConvertedGeometry() throws Exception {
        Path models = temporaryDirectory.resolve("models");
        Files.createDirectories(models);
        Path pluginData = temporaryDirectory.resolve("plugin-data");
        Files.createDirectories(pluginData);
        MetadataHandler.PLUGIN = (Plugin) java.lang.reflect.Proxy.newProxyInstance(
                Plugin.class.getClassLoader(), new Class<?>[]{Plugin.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getDataFolder")) return pluginData.toFile();
                    if (method.getName().equals("getName")) return "FreeMinecraftModels";
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                });
        Bukkit.setServer((Server) java.lang.reflect.Proxy.newProxyInstance(
                Server.class.getClassLoader(), new Class<?>[]{Server.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getBukkitVersion")) return "26.2-R0.1-SNAPSHOT";
                    if (method.getName().equals("getLogger")) return java.util.logging.Logger.getLogger("FMM-ticket");
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    if (method.getReturnType() == long.class) return 0L;
                    return null;
                }));

        List<FileModelConverter> converted = new ArrayList<>();
        try (var files = getClass().getResourceAsStream("/ticket-220/current-models.list")) {
            assertNotNull(files, "current model fixture manifest");
            for (String name : new String(files.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
                if (name.isBlank()) continue;
                Path target = models.resolve(name);
                try (InputStream source = getClass().getResourceAsStream("/ticket-220/current/models/" + name)) {
                    assertNotNull(source, "model fixture " + name);
                    Files.copy(source, target);
                }
                converted.add(new FileModelConverter(target.toFile()));
            }
        }

        assertFalse(converted.isEmpty(), "the migrated model fixture must not be empty");
        for (FileModelConverter model : converted)
            assertTrue(model.isRegisteredModel() && model.getSkeletonBlueprint() != null,
                    () -> "model was not converted: " + model.getSourceFile());

        for (String itemFile : ITEM_FILES) {
            YamlConfiguration yaml = load("/ticket-220/current/customitems/" + itemFile);
            String modelId = yaml.getString("fmmItemModel", "").trim().toLowerCase(Locale.ROOT);
            assertFalse(modelId.isEmpty(), itemFile + " must reference an FMM model");
            assertTrue(yaml.getStringList("scripts").isEmpty(), itemFile + " still uses retired item scripts");

            assertTrue(yaml.getBoolean("isEnabled", false), itemFile + " must remain enabled");
            assertFalse(yaml.getString("material", "").isBlank(), itemFile + " must remain a custom item");
            List<String> enchantments = yaml.getStringList("enchantments");
            assertFalse(enchantments.isEmpty(), itemFile + " must use namespaced enchantments");
            assertTrue(enchantments.stream().allMatch(value -> value.matches("[a-z0-9._-]+:[a-z0-9._-]+,[1-9][0-9]*")),
                    itemFile + " contains a retired unnamespaced enchantment");

            boolean direct = FileModelConverter.containsModel(modelId);
            boolean stateVariant = converted.stream().anyMatch(model ->
                    model.getID().startsWith(modelId + "_"));
            assertTrue(direct || stateVariant,
                    () -> itemFile + " points at model " + modelId + " with no converted model or draw-state variants");
        }
    }

    private YamlConfiguration load(String resource) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, resource);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            return yaml;
        }
    }
}
