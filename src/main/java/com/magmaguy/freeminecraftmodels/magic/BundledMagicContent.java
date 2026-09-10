package com.magmaguy.freeminecraftmodels.magic;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Installs FMM's code-matched magic defaults into the normal models pipeline. */
public final class BundledMagicContent {
    public static final String RESOURCE_DIRECTORY = "models/fmm_default_magic";

    private static final AssetSet STAFF = new AssetSet(
            RESOURCE_DIRECTORY, BuiltInMagicWeapons.DEFAULT_STAFF_ID, true);
    private static final AssetSet WAND = new AssetSet(
            RESOURCE_DIRECTORY, BuiltInMagicWeapons.DEFAULT_WAND_ID, false);
    private static final List<AssetSet> ASSETS = List.of(STAFF, WAND);

    private BundledMagicContent() {
    }

    /** Copies only missing defaults, preserving any administrator edits already on disk. */
    public static void installDefaults(JavaPlugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        for (AssetSet assets : ASSETS) {
            for (String resource : assets.resources()) {
                File destination = new File(plugin.getDataFolder(), resource);
                if (!destination.isFile()) plugin.saveResource(resource, false);
            }
        }
    }

    public static AssetSet staffAssets() {
        return STAFF;
    }

    public static List<AssetSet> assetSets() {
        return ASSETS;
    }

    public record AssetSet(String resourceDirectory, String modelId, boolean modelAssets) {
        public AssetSet {
            Objects.requireNonNull(resourceDirectory, "resourceDirectory");
            Objects.requireNonNull(modelId, "modelId");
        }

        List<String> resources() {
            List<String> paths = new ArrayList<>();
            String prefix = resourceDirectory + "/" + modelId;
            if (modelAssets) {
                paths.add(prefix + ".bbmodel");
                paths.add(prefix + ".json");
            }
            paths.add(prefix + ".yml");
            return List.copyOf(paths);
        }
    }
}
