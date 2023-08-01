package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.freeminecraftmodels.utils.Developer;
import com.magmaguy.freeminecraftmodels.utils.ZipFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class OutputFolder {
    public static void initializeConfig() {
        ConfigurationEngine.directoryCreator("output");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "textures");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "models");
        ConfigurationEngine.directoryCreator("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases");
        MetadataHandler.PLUGIN.saveResource("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.mcmeta", true);
        MetadataHandler.PLUGIN.saveResource("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.png", true);
        MetadataHandler.PLUGIN.saveResource("output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases/blocks.json", true);
        ZipFile.zip(new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels"), MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels.zip");
    }
}
