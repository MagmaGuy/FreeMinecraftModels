package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.MetadataHandler;

import java.io.File;

public class OutputFolder {
    public static void initializeConfig() {
        ConfigurationEngine.directoryCreator("output");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "textures");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "models");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases");
        MetadataHandler.PLUGIN.saveResource("output/FreeMinecraftModels/pack.mcmeta", false);
        MetadataHandler.PLUGIN.saveResource("output/FreeMinecraftModels/pack.png", false);
        MetadataHandler.PLUGIN.saveResource("output/FreeMinecraftModels/assets/minecraft/atlases/blocks.json", false);
    }
}
