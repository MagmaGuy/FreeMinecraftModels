package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.util.ZipFile;

import java.io.File;

public class OutputFolder {
    private OutputFolder() {
    }

    public static void initializeConfig() {
        String path = MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath();
        new File(path + File.separatorChar + "output").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "textures").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "models").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.mcmeta").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.png").mkdir();
        new File(path + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases/blocks.json").mkdir();
        ZipFile.zip(
                new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels"),
                MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels.zip");
    }
}
