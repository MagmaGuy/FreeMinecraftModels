package com.magmaguy.freeminecraftmodels.config;

import com.magmaguy.freeminecraftmodels.MetadataHandler;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class OutputFolder {
    private OutputFolder() {
    }

    public static void initializeConfig() {
        String path = MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath();
        String baseDirectory = path + File.separatorChar + "output";
        File mainFolder = new File(baseDirectory);
        try {
            if (mainFolder.exists()) FileUtils.deleteDirectory(mainFolder);
        } catch (Exception e) {
            Logger.warn("Failed to delete folder " + mainFolder.getAbsolutePath());
        }
        mainFolder.mkdir();
        generateDirectory(baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "textures");
        generateDirectory(baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "freeminecraftmodels" + File.separatorChar + "models");
        generateDirectory(baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases");
        generateFileFromResources("pack.mcmeta", baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.mcmeta");
        generateFileFromResources("pack.png", baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "pack.png");
        generateFileFromResources("blocks.json", baseDirectory + File.separatorChar + "FreeMinecraftModels" + File.separatorChar + "assets" + File.separatorChar + "minecraft" + File.separatorChar + "atlases" + File.separatorChar + "blocks.json");
    }

    public static void zipResourcePack() {
        ZipFile.zip(
                new File(MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels"),
                MetadataHandler.PLUGIN.getDataFolder().getAbsolutePath() + File.separatorChar + "output" + File.separatorChar + "FreeMinecraftModels.zip");
    }

    private static void generateFileFromResources(String filename, String destination) {
        try {
            InputStream inputStream = MetadataHandler.PLUGIN.getResource(filename);
            File newFile = new File(destination);
            newFile.mkdirs();
            if (!newFile.exists()) newFile.createNewFile();
            // Copy the InputStream to the file
            Files.copy(inputStream, newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            Logger.warn("Failed to generate default resource pack elements");
            e.printStackTrace();
        }
    }

    private static void generateDirectory(String path) {
        File file = new File(path);
        file.mkdirs();
        file.mkdir();
    }
}
