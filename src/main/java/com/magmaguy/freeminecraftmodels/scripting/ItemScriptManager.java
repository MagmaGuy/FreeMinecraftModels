package com.magmaguy.freeminecraftmodels.scripting;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.config.BowStateDetector;
import com.magmaguy.freeminecraftmodels.config.props.PropScriptConfigFields;
import com.magmaguy.freeminecraftmodels.magic.MagicWeaponCatalog;
import com.magmaguy.magmacore.config.ContentFileSelector;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/** Model-adjacent authored item catalog. Item effects belong to the shared enchantment runtime. */
public final class ItemScriptManager {
    public static final NamespacedKey ITEM_ID_KEY = new NamespacedKey(MetadataHandler.PLUGIN, "fmm_item_id");

    public record ItemCatalog(Map<String, PropScriptConfigFields> definitions, Map<String, File> sources,
                              MagicWeaponCatalog weapons) {
        public ItemCatalog { definitions = Map.copyOf(definitions); sources = Map.copyOf(sources); }
    }
    private static volatile ItemCatalog catalog = new ItemCatalog(Map.of(), Map.of(), new MagicWeaponCatalog(List.of()));
    public static Map<String, PropScriptConfigFields> getItemDefinitions() { return catalog.definitions(); }
    public static Map<String, File> getItemSourceFiles() { return catalog.sources(); }
    public static MagicWeaponCatalog getWeaponCatalog() { return catalog.weapons(); }

    private ItemScriptManager() { }

    // ── 2. Custom item detection ─────────────────────────────────────────

    /**
     * Scans the models folder for YML configs that define custom items
     * (have a material field set). Uses the unified PropScriptConfigFields.
     *
     * @param modelsFolder the root models directory to scan
     */
    public static void scanForCustomItems(File modelsFolder) {
        activateCandidate(prepareCatalog(modelsFolder));
    }

    /** Read and validate a complete candidate before any live registry is cleared. */
    public static ItemCatalog prepareCatalog(File directory) {
        try {
            var root = directory.toPath().toRealPath();
            Map<String, PropScriptConfigFields> definitions = new LinkedHashMap<>();
            Map<String, File> sources = new LinkedHashMap<>();
            List<File> files;
            try (var walk = java.nio.file.Files.walk(root)) {
                files = walk.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".yml"))
                        .map(java.nio.file.Path::toFile).toList();
            }
            for (File file : ContentFileSelector.select(files, name -> name.toLowerCase(Locale.ROOT))) {
                if (!file.toPath().toRealPath().startsWith(root) || !file.isFile())
                    throw new IllegalArgumentException("Item configuration escapes the models folder: " + file);
                String stem = file.getName().substring(0, file.getName().length() - 4);
                if (BowStateDetector.isDrawStateSuffix(stem)) continue;
                String itemId = stem.toLowerCase(Locale.ROOT);
                YamlConfiguration yaml = new YamlConfiguration();
                yaml.load(file);
                PropScriptConfigFields fields = new PropScriptConfigFields(itemId + ".yml", true);
                fields.setFileConfiguration(yaml);
                fields.setFile(file);
                try {
                    fields.processConfigFields();
                } catch (IllegalArgumentException invalid) {
                    throw new IllegalArgumentException(file + ": " + invalid.getMessage(), invalid);
                }
                if (fields.getUnavailableReason() != null) {
                    Logger.warn("[FMM Items] Skipping " + file + ": " + fields.getUnavailableReason()
                            + ". The file was left unchanged; other content will continue loading.");
                    continue;
                }
                if (!fields.isEnabled() || !fields.isCustomItem()) continue;
                if (!itemId.matches("[a-z0-9._-]{1,128}")) throw new IllegalArgumentException("Invalid item filename: " + file);
                if (definitions.putIfAbsent(itemId, fields) != null) throw new IllegalArgumentException("Duplicate item identity: " + itemId);
                File bbmodel = new File(file.getParentFile(), stem + ".bbmodel");
                File fmmodel = new File(file.getParentFile(), stem + ".fmmodel");
                sources.put(itemId, bbmodel.isFile() ? bbmodel : fmmodel.isFile() ? fmmodel : file);
            }
            return new ItemCatalog(definitions, sources, new MagicWeaponCatalog(definitions.values().stream()
                    .map(PropScriptConfigFields::getWeapon).filter(Objects::nonNull).toList()));
        } catch (java.io.IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            throw new IllegalArgumentException("Invalid FMM item catalog: " + failure.getMessage(), failure);
        }
    }

    public static void activateCandidate(ItemCatalog candidate) {
        catalog = Objects.requireNonNull(candidate, "candidate");
        Logger.info("Loaded " + candidate.definitions().size() + " custom item definition(s).");
    }

    public static void shutdown() {
        catalog = new ItemCatalog(Map.of(), Map.of(), new MagicWeaponCatalog(List.of()));
    }
}
