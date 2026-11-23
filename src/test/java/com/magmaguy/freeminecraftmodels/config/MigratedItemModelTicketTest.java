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
import java.util.Map;
import org.bukkit.plugin.Plugin;
import org.bukkit.Bukkit;
import org.bukkit.Server;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Temporary coverage for the item migration reported in ticket 1547862691916423169.  The old
 * format used item scripts, which FMM must reject; the current format moves item effects to
 * namespaced enchantments and points EliteMobs items at FMM model IDs, either one model or a
 * set of draw-state variants named {@code <id>_<state>}.
 * <p>
 * The models and item files are stand-ins: FMM's own bundled models under made-up names, and
 * item files written here. The pack this ticket was about is paid content and stays out of this
 * public repository.
 */
class MigratedItemModelTicketTest {
    private static final String BUNDLED_MODELS = "/models/fmm_default_magic/";

    /** Model file under {@code models/} to the bundled model it is a copy of. */
    private static final Map<String, String> MODEL_FILES = Map.of(
            "standin_blade.bbmodel", "fmm_default_arcane_wand.bbmodel",
            "standin_bow_idle.bbmodel", "fmm_default_arcane_staff.bbmodel",
            "standin_bow_draw_full.bbmodel", "fmm_default_arcane_staff.bbmodel");

    /** Item file name to its contents: one item with a single model, one with draw-state variants. */
    private static final Map<String, String> ITEM_FILES = Map.of(
            "standin_blade.yml", """
                    isEnabled: true
                    material: IRON_SWORD
                    name: "Stand-in blade"
                    enchantments:
                    - "minecraft:sharpness,5"
                    - "standin:spark,1"
                    fmmItemModel: standin_blade
                    """,
            "standin_bow.yml", """
                    isEnabled: true
                    material: BOW
                    name: "Stand-in bow"
                    enchantments:
                    - "minecraft:power,3"
                    fmmItemModel: standin_bow
                    """);

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
        for (Map.Entry<String, String> model : MODEL_FILES.entrySet()) {
            Path target = models.resolve(model.getKey());
            try (InputStream source = getClass().getResourceAsStream(BUNDLED_MODELS + model.getValue())) {
                assertNotNull(source, "bundled model " + model.getValue());
                Files.copy(source, target);
            }
            converted.add(new FileModelConverter(target.toFile()));
        }

        assertFalse(converted.isEmpty(), "the migrated model fixture must not be empty");
        for (FileModelConverter model : converted)
            assertTrue(model.isRegisteredModel() && model.getSkeletonBlueprint() != null,
                    () -> "model was not converted: " + model.getSourceFile());

        for (Map.Entry<String, String> item : ITEM_FILES.entrySet()) {
            String itemFile = item.getKey();
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(item.getValue());
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
}
